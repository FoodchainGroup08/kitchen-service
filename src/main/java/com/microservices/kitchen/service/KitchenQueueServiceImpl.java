package com.microservices.kitchen.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microservices.kitchen.dto.KitchenDtos;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
public class KitchenQueueServiceImpl implements KitchenQueueService {

    private static final Duration ORDER_TTL    = Duration.ofHours(24);
    private static final String   ORDER_KEY    = "kitchen:order:";
    private static final String   BRANCH_KEY   = "kitchen:branch:";

    @Value("${app.order-service.url:http://order-service:8083}")
    private String orderServiceUrl;

    @Autowired private StringRedisTemplate    redisTemplate;
    @Autowired private ObjectMapper           objectMapper;
    @Autowired private SimpMessagingTemplate  ws;
    @Autowired private RestTemplate           restTemplate;           // load-balanced (Eureka)
    @Autowired @Qualifier("directRestTemplate")
    private RestTemplate directRestTemplate;                          // Docker bridge DNS

    // ── Kafka ingest ──────────────────────────────────────────────────────────

    @Override
    public void enqueue(KitchenDtos.OrderReceivedEvent event) {
        try {
            List<KitchenDtos.KitchenOrderItem> items = mapItems(event.getItems());

            KitchenDtos.KitchenOrder order = KitchenDtos.KitchenOrder.builder()
                    .orderId(event.getOrderId())
                    .customerId(event.getCustomerId())
                    .branchId(event.getBranchId())
                    .orderType(event.getOrderType())
                    .tableNumber(event.getTableNumber())
                    .notes(event.getNotes())
                    .totalAmount(event.getTotalAmount())
                    .status("RECEIVED")
                    .items(items)
                    .receivedAt(LocalDateTime.now())
                    .build();

            saveOrder(order);
            long score = toScore(order.getReceivedAt());
            redisTemplate.opsForZSet()
                    .add(branchStatusKey(event.getBranchId(), "received"), event.getOrderId(), score);

            broadcast(event.getBranchId(), "RECEIVED", event.getOrderId());
            log.info("Enqueued order {} for branch {}", event.getOrderId(), event.getBranchId());
        } catch (Exception e) {
            log.error("Failed to enqueue order {}: {}", event.getOrderId(), e.getMessage(), e);
        }
    }

    // ── Queue read ────────────────────────────────────────────────────────────

    @Override
    public KitchenDtos.KitchenQueueResponse getQueue(String branchId, int page, int size) {
        return KitchenDtos.KitchenQueueResponse.builder()
                .branchId(branchId)
                .page(page)
                .size(size)
                .received(fetchByStatus(branchId, "received", page, size))
                .preparing(fetchByStatus(branchId, "preparing", page, size))
                .ready(fetchByStatus(branchId, "ready", page, size))
                .build();
    }

    // ── Kitchen actions ───────────────────────────────────────────────────────

    @Override
    public KitchenDtos.KitchenOrder acceptOrder(String orderId, String staffId, String notes) {
        KitchenDtos.KitchenOrder order = loadOrThrow(orderId);
        assertStatus(order, "RECEIVED");

        // Call order-service first — if it fails, Redis is not mutated so staff can retry
        callOrderService(orderId, "PREPARING", coalesce(staffId, "kitchen"), notes);

        order.setStatus("PREPARING");
        order.setAcceptedAt(LocalDateTime.now());
        saveOrder(order);
        moveInZSet(order.getBranchId(), orderId, "received", "preparing", toScore(order.getAcceptedAt()));

        broadcast(order.getBranchId(), "PREPARING", orderId);
        log.info("Order {} accepted → PREPARING", orderId);
        return order;
    }

    @Override
    public KitchenDtos.KitchenOrder markReady(String orderId, String staffId, String notes) {
        KitchenDtos.KitchenOrder order = loadOrThrow(orderId);
        assertStatus(order, "PREPARING");

        callOrderService(orderId, "READY", coalesce(staffId, "kitchen"), notes);

        order.setStatus("READY");
        order.setReadyAt(LocalDateTime.now());
        saveOrder(order);
        moveInZSet(order.getBranchId(), orderId, "preparing", "ready", toScore(order.getReadyAt()));

        broadcast(order.getBranchId(), "READY", orderId);
        log.info("Order {} marked → READY", orderId);
        return order;
    }

    @Override
    public KitchenDtos.KitchenOrder serveOrder(String orderId, String staffId, String notes) {
        KitchenDtos.KitchenOrder order = loadOrThrow(orderId);
        assertStatus(order, "READY");

        callOrderService(orderId, "COMPLETED",
                coalesce(staffId, "kitchen"), coalesce(notes, "Served at table"));
        removeFromQueue(order.getBranchId(), orderId, "ready");
        broadcast(order.getBranchId(), "COMPLETED", orderId);
        deleteOrder(orderId);
        log.info("Order {} served → COMPLETED", orderId);
        return order;
    }

    @Override
    public KitchenDtos.KitchenOrder pickupOrder(String orderId, String staffId, String notes) {
        KitchenDtos.KitchenOrder order = loadOrThrow(orderId);
        assertStatus(order, "READY");

        callOrderService(orderId, "COMPLETED",
                coalesce(staffId, "kitchen"), coalesce(notes, "Order picked up"));
        removeFromQueue(order.getBranchId(), orderId, "ready");
        broadcast(order.getBranchId(), "COMPLETED", orderId);
        deleteOrder(orderId);
        log.info("Order {} picked up → COMPLETED", orderId);
        return order;
    }

    // ── Redis helpers ─────────────────────────────────────────────────────────

    private KitchenDtos.KitchenOrder loadOrThrow(String orderId) {
        String json = redisTemplate.opsForValue().get(ORDER_KEY + orderId);
        if (json == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Order not found in kitchen queue: " + orderId);
        }
        try {
            return objectMapper.readValue(json, KitchenDtos.KitchenOrder.class);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to deserialize kitchen order: " + orderId);
        }
    }

    private void saveOrder(KitchenDtos.KitchenOrder order) {
        try {
            redisTemplate.opsForValue()
                    .set(ORDER_KEY + order.getOrderId(),
                            objectMapper.writeValueAsString(order), ORDER_TTL);
        } catch (Exception e) {
            log.error("Failed to save kitchen order {}: {}", order.getOrderId(), e.getMessage());
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Redis write failed");
        }
    }

    private void deleteOrder(String orderId) {
        redisTemplate.delete(ORDER_KEY + orderId);
    }

    private KitchenDtos.StatusGroup fetchByStatus(String branchId, String status, int page, int size) {
        String key = branchStatusKey(branchId, status);

        // Fetch all IDs from the ZSet so we can skip stale ones and paginate correctly
        Set<String> allIds = redisTemplate.opsForZSet()
                .rangeByScore(key, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY);

        List<KitchenDtos.KitchenOrder> valid = new ArrayList<>();
        if (allIds != null) {
            for (String id : allIds) {
                try {
                    String json = redisTemplate.opsForValue().get(ORDER_KEY + id);
                    if (json != null) {
                        valid.add(objectMapper.readValue(json, KitchenDtos.KitchenOrder.class));
                    } else {
                        // Detail key expired — remove orphaned ZSet entry
                        redisTemplate.opsForZSet().remove(key, id);
                        log.debug("Removed stale ZSet entry {} from {}", id, key);
                    }
                } catch (Exception e) {
                    log.warn("Could not deserialize order {}: {}", id, e.getMessage());
                }
            }
        }

        long total = valid.size();
        int offset = page * size;
        List<KitchenDtos.KitchenOrder> pageOrders = valid.stream()
                .skip(offset)
                .limit(size)
                .collect(Collectors.toList());

        return KitchenDtos.StatusGroup.builder()
                .total(total)
                .page(page)
                .size(size)
                .orders(pageOrders)
                .build();
    }

    private void moveInZSet(String branchId, String orderId, String fromStatus, String toStatus, double score) {
        redisTemplate.opsForZSet().remove(branchStatusKey(branchId, fromStatus), orderId);
        redisTemplate.opsForZSet().add(branchStatusKey(branchId, toStatus), orderId, score);
    }

    private void removeFromQueue(String branchId, String orderId, String status) {
        redisTemplate.opsForZSet().remove(branchStatusKey(branchId, status), orderId);
    }

    private String branchStatusKey(String branchId, String status) {
        return BRANCH_KEY + branchId + ":" + status;
    }

    // ── Order-service REST call ────────────────────────────────────────────────

    private void callOrderService(String orderId, String newStatus, String updatedBy, String notes) {
        try {
            KitchenDtos.UpdateStatusRequest body = KitchenDtos.UpdateStatusRequest.builder()
                    .newStatus(newStatus)
                    .updatedBy(updatedBy)
                    .notes(notes)
                    .build();
            HttpHeaders headers = new HttpHeaders();
            headers.set("X-User-Id", "kitchen-service");
            headers.set("X-User-Role", "KITCHEN_STAFF");
            String url = orderServiceUrl + "/api/v1/orders/{orderId}/status";
            log.debug("Calling order-service: PUT {}", url.replace("{orderId}", orderId));
            directRestTemplate.exchange(url, HttpMethod.PUT,
                    new HttpEntity<>(body, headers), Void.class, orderId);
        } catch (HttpStatusCodeException e) {
            log.error("Order-service rejected status update for order={} newStatus={} — HTTP {}: {}",
                    orderId, newStatus, e.getStatusCode().value(), e.getResponseBodyAsString());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Order-service rejected status update: " + e.getStatusCode().value());
        } catch (Exception e) {
            log.error("Failed to reach order-service for order={} newStatus={}: {}",
                    orderId, newStatus, e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Order-service unavailable — status update failed");
        }
    }

    // ── WebSocket broadcast ───────────────────────────────────────────────────

    private void broadcast(String branchId, String status, String orderId) {
        try {
            ws.convertAndSend("/topic/kitchen/" + branchId,
                    "{\"orderId\":\"" + orderId + "\",\"status\":\"" + status + "\"}");
        } catch (Exception e) {
            log.warn("WebSocket broadcast failed for order {}: {}", orderId, e.getMessage());
        }
    }

    // ── Misc helpers ──────────────────────────────────────────────────────────

    private void assertStatus(KitchenDtos.KitchenOrder order, String expected) {
        if (!expected.equals(order.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Expected order in " + expected + " but was " + order.getStatus());
        }
    }

    private List<KitchenDtos.KitchenOrderItem> mapItems(List<KitchenDtos.OrderItemEvent> events) {
        if (events == null) return new ArrayList<>();
        return events.stream()
                .map(e -> KitchenDtos.KitchenOrderItem.builder()
                        .menuItemId(e.getMenuItemId())
                        .menuItemName(e.getMenuItemName())
                        .quantity(e.getQuantity())
                        .specialInstructions(e.getSpecialInstructions())
                        .build())
                .collect(Collectors.toList());
    }

    private long toScore(LocalDateTime dt) {
        return dt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    private String coalesce(String value, String fallback) {
        return (value != null && !value.isBlank()) ? value : fallback;
    }

    // ── Package-private: used by SLAMonitoringService ─────────────────────────

    StringRedisTemplate getRedisTemplate() { return redisTemplate; }
}
