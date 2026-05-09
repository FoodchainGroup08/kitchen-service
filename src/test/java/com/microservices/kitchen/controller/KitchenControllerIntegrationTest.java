package com.microservices.kitchen.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microservices.kitchen.dto.KitchenDtos;
import com.microservices.kitchen.service.KitchenQueueService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(KitchenController.class)
@TestPropertySource(properties = {
    "eureka.client.enabled=false",
    "spring.cloud.config.enabled=false"
})
class KitchenControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private KitchenQueueService kitchenQueueService;

    // ── GET /kitchen/queue/{branchId} ─────────────────────────────────────────

    @Test
    void getQueue_returnsQueueGroupedByStatus() throws Exception {
        KitchenDtos.KitchenQueueResponse response = KitchenDtos.KitchenQueueResponse.builder()
                .branchId("branch-1")
                .received(List.of(sampleOrder("order-1", "RECEIVED")))
                .preparing(List.of())
                .ready(List.of())
                .build();

        when(kitchenQueueService.getQueue("branch-1")).thenReturn(response);

        mockMvc.perform(get("/kitchen/queue/branch-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.branchId").value("branch-1"))
                .andExpect(jsonPath("$.received[0].orderId").value("order-1"))
                .andExpect(jsonPath("$.received[0].status").value("RECEIVED"))
                .andExpect(jsonPath("$.preparing").isArray())
                .andExpect(jsonPath("$.ready").isArray());
    }

    @Test
    void getQueue_whenNoOrders_returnsEmptyLists() throws Exception {
        KitchenDtos.KitchenQueueResponse response = KitchenDtos.KitchenQueueResponse.builder()
                .branchId("branch-99")
                .received(List.of())
                .preparing(List.of())
                .ready(List.of())
                .build();

        when(kitchenQueueService.getQueue("branch-99")).thenReturn(response);

        mockMvc.perform(get("/kitchen/queue/branch-99"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.received").isEmpty())
                .andExpect(jsonPath("$.preparing").isEmpty())
                .andExpect(jsonPath("$.ready").isEmpty());
    }

    // ── POST /kitchen/orders/{orderId}/accept ─────────────────────────────────

    @Test
    void acceptOrder_withStaffBody_returnsPreparingOrder() throws Exception {
        KitchenDtos.KitchenOrder order = sampleOrder("order-1", "PREPARING");
        KitchenDtos.KitchenActionRequest body = new KitchenDtos.KitchenActionRequest("staff-1", "starting now");

        when(kitchenQueueService.acceptOrder("order-1", "staff-1", "starting now")).thenReturn(order);

        mockMvc.perform(post("/kitchen/orders/order-1/accept")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value("order-1"))
                .andExpect(jsonPath("$.status").value("PREPARING"));
    }

    @Test
    void acceptOrder_withoutBody_defaultsNullStaffAndNotes() throws Exception {
        KitchenDtos.KitchenOrder order = sampleOrder("order-1", "PREPARING");

        when(kitchenQueueService.acceptOrder("order-1", null, null)).thenReturn(order);

        mockMvc.perform(post("/kitchen/orders/order-1/accept"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PREPARING"));
    }

    @Test
    void acceptOrder_whenOrderNotFound_returns404() throws Exception {
        when(kitchenQueueService.acceptOrder(anyString(), any(), any()))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found in kitchen queue: missing"));

        mockMvc.perform(post("/kitchen/orders/missing/accept"))
                .andExpect(status().isNotFound());
    }

    @Test
    void acceptOrder_whenWrongStatus_returns409() throws Exception {
        when(kitchenQueueService.acceptOrder(anyString(), any(), any()))
                .thenThrow(new ResponseStatusException(HttpStatus.CONFLICT, "Expected order in RECEIVED but was PREPARING"));

        mockMvc.perform(post("/kitchen/orders/order-1/accept"))
                .andExpect(status().isConflict());
    }

    @Test
    void acceptOrder_whenOrderServiceDown_returns502() throws Exception {
        when(kitchenQueueService.acceptOrder(anyString(), any(), any()))
                .thenThrow(new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Order-service unavailable"));

        mockMvc.perform(post("/kitchen/orders/order-1/accept"))
                .andExpect(status().isBadGateway());
    }

    // ── POST /kitchen/orders/{orderId}/ready ──────────────────────────────────

    @Test
    void markReady_withBody_returnsReadyOrder() throws Exception {
        KitchenDtos.KitchenOrder order = sampleOrder("order-2", "READY");
        KitchenDtos.KitchenActionRequest body = new KitchenDtos.KitchenActionRequest("staff-2", null);

        when(kitchenQueueService.markReady("order-2", "staff-2", null)).thenReturn(order);

        mockMvc.perform(post("/kitchen/orders/order-2/ready")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value("order-2"))
                .andExpect(jsonPath("$.status").value("READY"));
    }

    @Test
    void markReady_whenOrderNotFound_returns404() throws Exception {
        when(kitchenQueueService.markReady(anyString(), any(), any()))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found"));

        mockMvc.perform(post("/kitchen/orders/ghost/ready"))
                .andExpect(status().isNotFound());
    }

    @Test
    void markReady_whenWrongStatus_returns409() throws Exception {
        when(kitchenQueueService.markReady(anyString(), any(), any()))
                .thenThrow(new ResponseStatusException(HttpStatus.CONFLICT, "Expected order in PREPARING but was RECEIVED"));

        mockMvc.perform(post("/kitchen/orders/order-1/ready"))
                .andExpect(status().isConflict());
    }

    // ── POST /kitchen/orders/{orderId}/serve ──────────────────────────────────

    @Test
    void serveOrder_returnsOrderWithBranchId() throws Exception {
        KitchenDtos.KitchenOrder order = sampleOrder("order-3", "READY");
        KitchenDtos.KitchenActionRequest body = new KitchenDtos.KitchenActionRequest("staff-3", "table 5");

        when(kitchenQueueService.serveOrder("order-3", "staff-3", "table 5")).thenReturn(order);

        mockMvc.perform(post("/kitchen/orders/order-3/serve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value("order-3"))
                .andExpect(jsonPath("$.branchId").value("branch-1"));
    }

    @Test
    void serveOrder_whenOrderNotFound_returns404() throws Exception {
        when(kitchenQueueService.serveOrder(anyString(), any(), any()))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found"));

        mockMvc.perform(post("/kitchen/orders/ghost/serve"))
                .andExpect(status().isNotFound());
    }

    @Test
    void serveOrder_whenWrongStatus_returns409() throws Exception {
        when(kitchenQueueService.serveOrder(anyString(), any(), any()))
                .thenThrow(new ResponseStatusException(HttpStatus.CONFLICT, "Expected order in READY but was PREPARING"));

        mockMvc.perform(post("/kitchen/orders/order-1/serve"))
                .andExpect(status().isConflict());
    }

    @Test
    void serveOrder_whenOrderServiceDown_returns502() throws Exception {
        when(kitchenQueueService.serveOrder(anyString(), any(), any()))
                .thenThrow(new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Order-service unavailable"));

        mockMvc.perform(post("/kitchen/orders/order-1/serve"))
                .andExpect(status().isBadGateway());
    }

    // ── POST /kitchen/orders/{orderId}/pickup ─────────────────────────────────

    @Test
    void pickupOrder_withoutBody_returnsOrder() throws Exception {
        KitchenDtos.KitchenOrder order = sampleOrder("order-4", "READY");

        when(kitchenQueueService.pickupOrder("order-4", null, null)).thenReturn(order);

        mockMvc.perform(post("/kitchen/orders/order-4/pickup"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value("order-4"));
    }

    @Test
    void pickupOrder_whenOrderNotFound_returns404() throws Exception {
        when(kitchenQueueService.pickupOrder(anyString(), any(), any()))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found"));

        mockMvc.perform(post("/kitchen/orders/ghost/pickup"))
                .andExpect(status().isNotFound());
    }

    @Test
    void pickupOrder_whenWrongStatus_returns409() throws Exception {
        when(kitchenQueueService.pickupOrder(anyString(), any(), any()))
                .thenThrow(new ResponseStatusException(HttpStatus.CONFLICT, "Expected order in READY but was RECEIVED"));

        mockMvc.perform(post("/kitchen/orders/order-1/pickup"))
                .andExpect(status().isConflict());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private KitchenDtos.KitchenOrder sampleOrder(String orderId, String status) {
        return KitchenDtos.KitchenOrder.builder()
                .orderId(orderId)
                .customerId("cust-1")
                .branchId("branch-1")
                .orderType("DINE_IN")
                .tableNumber("T5")
                .status(status)
                .totalAmount(BigDecimal.valueOf(25.50))
                .receivedAt(LocalDateTime.now())
                .items(List.of())
                .build();
    }
}
