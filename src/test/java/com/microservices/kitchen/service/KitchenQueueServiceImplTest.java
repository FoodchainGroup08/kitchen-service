package com.microservices.kitchen.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microservices.kitchen.dto.KitchenDtos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class KitchenQueueServiceImplTest {

    @Mock private StringRedisTemplate              redisTemplate;
    @Mock private ObjectMapper                     objectMapper;
    @Mock private SimpMessagingTemplate            ws;
    @Mock private RestTemplate                     restTemplate;
    @Mock private ValueOperations<String, String>  valueOps;
    @Mock private ZSetOperations<String, String>   zsetOps;

    @InjectMocks
    private KitchenQueueServiceImpl service;

    private static final String ORDER_ID  = "order-123";
    private static final String BRANCH_ID = "branch-456";

    private static final String ORDER_KEY  = "kitchen:order:" + ORDER_ID;
    private static final String RECEIVED_KEY  = "kitchen:branch:" + BRANCH_ID + ":received";
    private static final String PREPARING_KEY = "kitchen:branch:" + BRANCH_ID + ":preparing";
    private static final String READY_KEY     = "kitchen:branch:" + BRANCH_ID + ":ready";

    @BeforeEach
    void setUpRedisStubs() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
        lenient().when(redisTemplate.opsForZSet()).thenReturn(zsetOps);
    }

    // ── enqueue ───────────────────────────────────────────────────────────────

    @Test
    void enqueue_storesOrderInRedisAndAddsToReceivedZSet() throws Exception {
        when(objectMapper.writeValueAsString(any())).thenReturn("{\"orderId\":\"order-123\"}");

        service.enqueue(buildEvent());

        verify(valueOps).set(eq(ORDER_KEY), anyString(), any());
        verify(zsetOps).add(eq(RECEIVED_KEY), eq(ORDER_ID), anyDouble());
        verify(ws).convertAndSend(eq("/topic/kitchen/" + BRANCH_ID), anyString());
    }

    @Test
    void enqueue_withNullItems_stillSucceeds() throws Exception {
        KitchenDtos.OrderReceivedEvent event = buildEvent();
        event.setItems(null);
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");

        assertThatCode(() -> service.enqueue(event)).doesNotThrowAnyException();
        verify(valueOps).set(anyString(), anyString(), any());
    }

    @Test
    void enqueue_whenSaveFails_swallowsException() throws Exception {
        when(objectMapper.writeValueAsString(any())).thenThrow(new RuntimeException("serialization error"));

        assertThatCode(() -> service.enqueue(buildEvent())).doesNotThrowAnyException();
        verify(valueOps, never()).set(anyString(), anyString(), any());
    }

    // ── getQueue ──────────────────────────────────────────────────────────────

    @Test
    void getQueue_returnsOrdersGroupedByStatus() throws Exception {
        KitchenDtos.KitchenOrder received = buildOrder("order-1", "RECEIVED");
        String json = "{\"orderId\":\"order-1\",\"status\":\"RECEIVED\"}";

        when(zsetOps.rangeByScore(eq(RECEIVED_KEY), anyDouble(), anyDouble()))
                .thenReturn(new LinkedHashSet<>(List.of("order-1")));
        when(zsetOps.rangeByScore(eq(PREPARING_KEY), anyDouble(), anyDouble()))
                .thenReturn(Set.of());
        when(zsetOps.rangeByScore(eq(READY_KEY), anyDouble(), anyDouble()))
                .thenReturn(Set.of());
        when(valueOps.get("kitchen:order:order-1")).thenReturn(json);
        when(objectMapper.readValue(json, KitchenDtos.KitchenOrder.class)).thenReturn(received);

        KitchenDtos.KitchenQueueResponse result = service.getQueue(BRANCH_ID);

        assertThat(result.getBranchId()).isEqualTo(BRANCH_ID);
        assertThat(result.getReceived()).hasSize(1).extracting(KitchenDtos.KitchenOrder::getOrderId).containsExactly("order-1");
        assertThat(result.getPreparing()).isEmpty();
        assertThat(result.getReady()).isEmpty();
    }

    @Test
    void getQueue_whenZSetReturnsNull_returnsEmptyLists() {
        when(zsetOps.rangeByScore(anyString(), anyDouble(), anyDouble())).thenReturn(null);

        KitchenDtos.KitchenQueueResponse result = service.getQueue(BRANCH_ID);

        assertThat(result.getReceived()).isEmpty();
        assertThat(result.getPreparing()).isEmpty();
        assertThat(result.getReady()).isEmpty();
    }

    @Test
    void getQueue_skipsOrderThatCannotBeDeserialized() throws Exception {
        when(zsetOps.rangeByScore(eq(RECEIVED_KEY), anyDouble(), anyDouble()))
                .thenReturn(new LinkedHashSet<>(List.of("bad-order")));
        when(zsetOps.rangeByScore(eq(PREPARING_KEY), anyDouble(), anyDouble())).thenReturn(Set.of());
        when(zsetOps.rangeByScore(eq(READY_KEY), anyDouble(), anyDouble())).thenReturn(Set.of());
        when(valueOps.get("kitchen:order:bad-order")).thenReturn("{invalid}");
        when(objectMapper.readValue("{invalid}", KitchenDtos.KitchenOrder.class))
                .thenThrow(new RuntimeException("parse error"));

        KitchenDtos.KitchenQueueResponse result = service.getQueue(BRANCH_ID);

        assertThat(result.getReceived()).isEmpty();
    }

    // ── acceptOrder ───────────────────────────────────────────────────────────

    @Test
    void acceptOrder_movesOrderToPreparingAndNotifiesOrderService() throws Exception {
        KitchenDtos.KitchenOrder order = buildOrder(ORDER_ID, "RECEIVED");
        stubLoad(order);
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");

        KitchenDtos.KitchenOrder result = service.acceptOrder(ORDER_ID, "staff-1", "quick order");

        assertThat(result.getStatus()).isEqualTo("PREPARING");
        assertThat(result.getAcceptedAt()).isNotNull();
        verify(zsetOps).remove(RECEIVED_KEY, ORDER_ID);
        verify(zsetOps).add(eq(PREPARING_KEY), eq(ORDER_ID), anyDouble());
        verify(restTemplate).exchange(anyString(), eq(HttpMethod.PUT), any(HttpEntity.class), eq(Void.class), eq(ORDER_ID));
        verify(ws).convertAndSend(eq("/topic/kitchen/" + BRANCH_ID), contains("PREPARING"));
    }

    @Test
    void acceptOrder_withNullStaffId_fallsBackToKitchenDefault() throws Exception {
        KitchenDtos.KitchenOrder order = buildOrder(ORDER_ID, "RECEIVED");
        stubLoad(order);
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");

        service.acceptOrder(ORDER_ID, null, null);

        verify(restTemplate).exchange(anyString(), any(), any(HttpEntity.class), eq(Void.class), eq(ORDER_ID));
    }

    @Test
    void acceptOrder_whenOrderNotFound_throws404() {
        when(valueOps.get(ORDER_KEY)).thenReturn(null);

        assertThatThrownBy(() -> service.acceptOrder(ORDER_ID, null, null))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void acceptOrder_whenOrderAlreadyPreparing_throws409() throws Exception {
        stubLoad(buildOrder(ORDER_ID, "PREPARING"));

        assertThatThrownBy(() -> service.acceptOrder(ORDER_ID, null, null))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void acceptOrder_whenOrderServiceFails_throws502() throws Exception {
        stubLoad(buildOrder(ORDER_ID, "RECEIVED"));
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        when(restTemplate.exchange(anyString(), any(), any(), eq(Void.class), eq(ORDER_ID)))
                .thenThrow(new RuntimeException("Connection refused"));

        assertThatThrownBy(() -> service.acceptOrder(ORDER_ID, null, null))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_GATEWAY));
    }

    // ── markReady ─────────────────────────────────────────────────────────────

    @Test
    void markReady_movesOrderToReadyAndNotifiesOrderService() throws Exception {
        stubLoad(buildOrder(ORDER_ID, "PREPARING"));
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");

        KitchenDtos.KitchenOrder result = service.markReady(ORDER_ID, "staff-1", "plated");

        assertThat(result.getStatus()).isEqualTo("READY");
        assertThat(result.getReadyAt()).isNotNull();
        verify(zsetOps).remove(PREPARING_KEY, ORDER_ID);
        verify(zsetOps).add(eq(READY_KEY), eq(ORDER_ID), anyDouble());
        verify(restTemplate).exchange(anyString(), eq(HttpMethod.PUT), any(HttpEntity.class), eq(Void.class), eq(ORDER_ID));
        verify(ws).convertAndSend(eq("/topic/kitchen/" + BRANCH_ID), contains("READY"));
    }

    @Test
    void markReady_whenOrderNotFound_throws404() {
        when(valueOps.get(ORDER_KEY)).thenReturn(null);

        assertThatThrownBy(() -> service.markReady(ORDER_ID, null, null))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void markReady_whenOrderIsReceived_throws409() throws Exception {
        stubLoad(buildOrder(ORDER_ID, "RECEIVED"));

        assertThatThrownBy(() -> service.markReady(ORDER_ID, null, null))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void markReady_whenOrderServiceFails_throws502() throws Exception {
        stubLoad(buildOrder(ORDER_ID, "PREPARING"));
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        when(restTemplate.exchange(anyString(), any(), any(), eq(Void.class), eq(ORDER_ID)))
                .thenThrow(new RuntimeException("timeout"));

        assertThatThrownBy(() -> service.markReady(ORDER_ID, null, null))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_GATEWAY));
    }

    // ── serveOrder ────────────────────────────────────────────────────────────

    @Test
    void serveOrder_removesOrderFromQueueAndBroadcastsCompleted() throws Exception {
        KitchenDtos.KitchenOrder order = buildOrder(ORDER_ID, "READY");
        stubLoad(order);

        KitchenDtos.KitchenOrder result = service.serveOrder(ORDER_ID, "staff-1", null);

        assertThat(result.getOrderId()).isEqualTo(ORDER_ID);
        verify(zsetOps).remove(READY_KEY, ORDER_ID);
        verify(redisTemplate).delete(ORDER_KEY);
        verify(restTemplate).exchange(anyString(), eq(HttpMethod.PUT), any(HttpEntity.class), eq(Void.class), eq(ORDER_ID));
        verify(ws).convertAndSend(eq("/topic/kitchen/" + BRANCH_ID), contains("COMPLETED"));
    }

    @Test
    void serveOrder_withNullNotes_usesDefaultServedMessage() throws Exception {
        stubLoad(buildOrder(ORDER_ID, "READY"));

        service.serveOrder(ORDER_ID, null, null);

        verify(restTemplate).exchange(anyString(), any(), any(HttpEntity.class), eq(Void.class), eq(ORDER_ID));
    }

    @Test
    void serveOrder_whenOrderNotFound_throws404() {
        when(valueOps.get(ORDER_KEY)).thenReturn(null);

        assertThatThrownBy(() -> service.serveOrder(ORDER_ID, null, null))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void serveOrder_whenOrderIsPreparing_throws409() throws Exception {
        stubLoad(buildOrder(ORDER_ID, "PREPARING"));

        assertThatThrownBy(() -> service.serveOrder(ORDER_ID, null, null))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void serveOrder_whenOrderServiceFails_throws502() throws Exception {
        stubLoad(buildOrder(ORDER_ID, "READY"));
        when(restTemplate.exchange(anyString(), any(), any(), eq(Void.class), eq(ORDER_ID)))
                .thenThrow(new RuntimeException("Connection refused"));

        assertThatThrownBy(() -> service.serveOrder(ORDER_ID, null, null))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_GATEWAY));
    }

    // ── pickupOrder ───────────────────────────────────────────────────────────

    @Test
    void pickupOrder_removesOrderFromQueueAndBroadcastsCompleted() throws Exception {
        KitchenDtos.KitchenOrder order = buildOrder(ORDER_ID, "READY");
        stubLoad(order);

        KitchenDtos.KitchenOrder result = service.pickupOrder(ORDER_ID, "rider-1", null);

        assertThat(result.getOrderId()).isEqualTo(ORDER_ID);
        verify(zsetOps).remove(READY_KEY, ORDER_ID);
        verify(redisTemplate).delete(ORDER_KEY);
        verify(ws).convertAndSend(eq("/topic/kitchen/" + BRANCH_ID), contains("COMPLETED"));
    }

    @Test
    void pickupOrder_withNullNotes_usesDefaultPickedUpMessage() throws Exception {
        stubLoad(buildOrder(ORDER_ID, "READY"));

        service.pickupOrder(ORDER_ID, null, null);

        verify(restTemplate).exchange(anyString(), any(), any(HttpEntity.class), eq(Void.class), eq(ORDER_ID));
    }

    @Test
    void pickupOrder_whenOrderNotFound_throws404() {
        when(valueOps.get(ORDER_KEY)).thenReturn(null);

        assertThatThrownBy(() -> service.pickupOrder(ORDER_ID, null, null))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void pickupOrder_whenOrderIsReceived_throws409() throws Exception {
        stubLoad(buildOrder(ORDER_ID, "RECEIVED"));

        assertThatThrownBy(() -> service.pickupOrder(ORDER_ID, null, null))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void pickupOrder_whenOrderServiceFails_throws502() throws Exception {
        stubLoad(buildOrder(ORDER_ID, "READY"));
        when(restTemplate.exchange(anyString(), any(), any(), eq(Void.class), eq(ORDER_ID)))
                .thenThrow(new RuntimeException("timeout"));

        assertThatThrownBy(() -> service.pickupOrder(ORDER_ID, null, null))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_GATEWAY));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private void stubLoad(KitchenDtos.KitchenOrder order) throws Exception {
        String json = String.format("{\"orderId\":\"%s\",\"status\":\"%s\"}",
                order.getOrderId(), order.getStatus());
        when(valueOps.get("kitchen:order:" + order.getOrderId())).thenReturn(json);
        when(objectMapper.readValue(json, KitchenDtos.KitchenOrder.class)).thenReturn(order);
    }

    private KitchenDtos.KitchenOrder buildOrder(String orderId, String status) {
        return KitchenDtos.KitchenOrder.builder()
                .orderId(orderId)
                .customerId("cust-1")
                .branchId(BRANCH_ID)
                .orderType("DINE_IN")
                .tableNumber("T3")
                .status(status)
                .totalAmount(BigDecimal.TEN)
                .receivedAt(LocalDateTime.now())
                .items(List.of())
                .build();
    }

    private KitchenDtos.OrderReceivedEvent buildEvent() {
        return KitchenDtos.OrderReceivedEvent.builder()
                .orderId(ORDER_ID)
                .customerId("cust-1")
                .branchId(BRANCH_ID)
                .orderType("DINE_IN")
                .totalAmount(BigDecimal.TEN)
                .items(List.of(
                        KitchenDtos.OrderItemEvent.builder()
                                .menuItemId("item-1")
                                .menuItemName("Jollof Rice")
                                .quantity(2)
                                .unitPrice(BigDecimal.valueOf(5))
                                .build()))
                .build();
    }
}
