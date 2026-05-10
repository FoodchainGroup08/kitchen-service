package com.microservices.kitchen.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microservices.kitchen.dto.KitchenDtos;
import com.microservices.kitchen.exception.ResourceNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
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

    @Autowired private StringRedisTemplate    redisTemplate;
    @Autowired private ObjectMapper           objectMapper;
    @Autowired private SimpMessagingTemplate  ws;
    @Autowired private RestTemplate           restTemplate;

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
    public KitchenDtos.KitchenQueueResponse getQueue(String branchId) {
        return KitchenDtos.KitchenQueueResponse.builder()
                .branchId(branchId)
                .received(fetchByStatus(branchId, "received"))
                .preparing(fetchByStatus(branchId, "preparing"))
                .ready(fetchByStatus(branchId, "ready"))
                .build();
    }

    // ── Kitchen actions ───────────────────────────────────────────────────────

    @Override
    public KitchenDtos.KitchenOrder acceptOrder(String orderId, String staffId, String notes) {
        KitchenDtos.KitchenOrder order = loadOrThrow(orderId);
        String st = normalizedStatus(order.getStatus());
        // Duplicate taps / retries after a successful accept must not fail with 409.
        if ("PREPARING".equals(st)) {
            log.info("Order {} already PREPARING — accept is idempotent, skipping transition", orderId);
            return order;
        }
        if (!"RECEIVED".equals(st)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Expected order in RECEIVED but was " + order.getStatus());
        }

        order.setStatus("PREPARING");
        order.setAcceptedAt(LocalDateTime.now());
        saveOrder(order);
        moveInZSet(order.getBranchId(), orderId, "received", "preparing", toScore(order.getAcceptedAt()));

        callOrderService(orderId, "PREPARING", coalesce(staffId, "kitchen"), notes);
        broadcast(order.getBranchId(), "PREPARING", orderId);
        log.info("Order {} accepted → PREPARING", orderId);
        return order;
    }

    @Override
    public KitchenDtos.KitchenOrder markReady(String orderId, String staffId, String notes) {
        KitchenDtos.KitchenOrder order = loadOrThrow(orderId);
        assertStatus(order, "PREPARING");

        order.setStatus("READY");
        order.setReadyAt(LocalDateTime.now());
        saveOrder(order);
        moveInZSet(order.getBranchId(), orderId, "preparing", "ready", toScore(order.getReadyAt()));

        callOrderService(orderId, "READY", coalesce(staffId, "kitchen"), notes);
        broadcast(order.getBranchId(), "READY", orderId);
        log.info("Order {} marked → READY", orderId);
        return order;
    }

    @Override
    public KitchenDtos.KitchenOrder serveOrder(String orderId, String staffId, String notes) {
        KitchenDtos.KitchenOrder order = loadOrThrow(orderId);
        assertStatus(order, "READY");

        removeFromQueue(order.getBranchId(), orderId, "ready");
        callOrderService(orderId, "COMPLETED",
                coalesce(staffId, "kitchen"), coalesce(notes, "Served at table"));
        broadcast(order.getBranchId(), "COMPLETED", orderId);
        deleteOrder(orderId);
        log.info("Order {} served → COMPLETED", orderId);
        return order;
    }

    @Override
    public KitchenDtos.KitchenOrder pickupOrder(String orderId, String staffId, String notes) {
        KitchenDtos.KitchenOrder order = loadOrThrow(orderId);
        assertStatus(order, "READY");

        removeFromQueue(order.getBranchId(), orderId, "ready");
        callOrderService(orderId, "COMPLETED",
                coalesce(staffId, "kitchen"), coalesce(notes, "Order picked up"));
        broadcast(order.getBranchId(), "COMPLETED", orderId);
        deleteOrder(orderId);
        log.info("Order {} picked up → COMPLETED", orderId);
        return order;
    }

    // ── Redis helpers ─────────────────────────────────────────────────────────

    private KitchenDtos.KitchenOrder loadOrThrow(String orderId) {
        String json = redisTemplate.opsForValue().get(ORDER_KEY + orderId);
        if (json == null) {
            throw new ResourceNotFoundException("Order not found in kitchen queue: " + orderId);
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

    private List<KitchenDtos.KitchenOrder> fetchByStatus(String branchId, String status) {
        Set<String> orderIds = redisTemplate.opsForZSet()
                .rangeByScore(branchStatusKey(branchId, status), Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY);
        if (orderIds == null || orderIds.isEmpty()) return new ArrayList<>();

        return orderIds.stream()
                .map(id -> {
                    try {
                        String json = redisTemplate.opsForValue().get(ORDER_KEY + id);
                        return json != null ? objectMapper.readValue(json, KitchenDtos.KitchenOrder.class) : null;
                    } catch (Exception e) {
                        log.warn("Could not deserialize order {}: {}", id, e.getMessage());
                        return null;
                    }
                })
                .filter(o -> o != null)
                .collect(Collectors.toList());
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
            restTemplate.exchange(
                    "http://order-service/api/orders/{orderId}/status",
                    HttpMethod.PUT,
                    new HttpEntity<>(body),
                    Void.class,
                    orderId);
        } catch (Exception e) {
            log.error("Failed to update order {} status to {} in order-service: {}",
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
        String actual = normalizedStatus(order.getStatus());
        String exp = normalizedStatus(expected);
        if (!exp.equals(actual)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Expected order in " + expected + " but was " + order.getStatus());
        }
    }

    private static String normalizedStatus(String status) {
        if (status == null || status.isBlank()) {
            return "";
        }
        return status.trim().toUpperCase();
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
