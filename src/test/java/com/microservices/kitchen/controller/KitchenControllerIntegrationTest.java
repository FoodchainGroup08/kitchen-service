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

    // ── GET /kitchen/queue  (header / query param) ────────────────────────────

    @Test
    void getQueueFromHeader_withHeader_returns200() throws Exception {
        KitchenDtos.KitchenQueueResponse response = KitchenDtos.KitchenQueueResponse.builder()
                .branchId("branch-1")
                .received(List.of(sampleOrder("order-1", "RECEIVED")))
                .preparing(List.of())
                .ready(List.of())
                .build();

        when(kitchenQueueService.getQueue("branch-1")).thenReturn(response);

        mockMvc.perform(get("/kitchen/queue")
                        .header("X-User-BranchId", "branch-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.branchId").value("branch-1"))
                .andExpect(jsonPath("$.received[0].orderId").value("order-1"));
    }

    @Test
    void getQueueFromHeader_withQueryParam_returns200() throws Exception {
        KitchenDtos.KitchenQueueResponse response = KitchenDtos.KitchenQueueResponse.builder()
                .branchId("branch-2")
                .received(List.of())
                .preparing(List.of())
                .ready(List.of())
                .build();

        when(kitchenQueueService.getQueue("branch-2")).thenReturn(response);

        mockMvc.perform(get("/kitchen/queue").param("branchId", "branch-2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.branchId").value("branch-2"));
    }

    @Test
    void getQueueFromHeader_queryParamTakesPriorityOverHeader() throws Exception {
        KitchenDtos.KitchenQueueResponse response = KitchenDtos.KitchenQueueResponse.builder()
                .branchId("branch-param")
                .received(List.of())
                .preparing(List.of())
                .ready(List.of())
                .build();

        when(kitchenQueueService.getQueue("branch-param")).thenReturn(response);

        mockMvc.perform(get("/kitchen/queue")
                        .header("X-User-BranchId", "branch-header")
                        .param("branchId", "branch-param"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.branchId").value("branch-param"));
    }

    @Test
    void getQueueFromHeader_withoutBranchId_returns400() throws Exception {
        mockMvc.perform(get("/kitchen/queue"))
                .andExpect(status().isBadRequest());
    }

    // ── PATCH /kitchen/orders/{orderId}/status ────────────────────────────────

    @Test
    void updateStatus_PREPARING_delegatesToAcceptOrder() throws Exception {
        KitchenDtos.KitchenOrder order = sampleOrder("order-1", "PREPARING");
        when(kitchenQueueService.acceptOrder("order-1", null, null)).thenReturn(order);

        mockMvc.perform(patch("/kitchen/orders/order-1/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newStatus\":\"PREPARING\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PREPARING"));
    }

    @Test
    void updateStatus_READY_delegatesToMarkReady() throws Exception {
        KitchenDtos.KitchenOrder order = sampleOrder("order-1", "READY");
        when(kitchenQueueService.markReady("order-1", "staff-1", "all done")).thenReturn(order);

        mockMvc.perform(patch("/kitchen/orders/order-1/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newStatus\":\"READY\",\"staffId\":\"staff-1\",\"notes\":\"all done\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"));
    }

    @Test
    void updateStatus_SERVED_delegatesToServeOrder() throws Exception {
        KitchenDtos.KitchenOrder order = sampleOrder("order-1", "READY");
        when(kitchenQueueService.serveOrder("order-1", null, null)).thenReturn(order);

        mockMvc.perform(patch("/kitchen/orders/order-1/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newStatus\":\"SERVED\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void updateStatus_PICKED_UP_delegatesToPickupOrder() throws Exception {
        KitchenDtos.KitchenOrder order = sampleOrder("order-1", "READY");
        when(kitchenQueueService.pickupOrder("order-1", null, null)).thenReturn(order);

        mockMvc.perform(patch("/kitchen/orders/order-1/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newStatus\":\"PICKED_UP\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void updateStatus_caseInsensitive_PREPARING_works() throws Exception {
        KitchenDtos.KitchenOrder order = sampleOrder("order-1", "PREPARING");
        when(kitchenQueueService.acceptOrder("order-1", null, null)).thenReturn(order);

        mockMvc.perform(patch("/kitchen/orders/order-1/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newStatus\":\"preparing\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void updateStatus_invalidStatus_returns400() throws Exception {
        mockMvc.perform(patch("/kitchen/orders/order-1/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newStatus\":\"CANCELLED\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updateStatus_orderNotFound_returns404() throws Exception {
        when(kitchenQueueService.acceptOrder(anyString(), any(), any()))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found in kitchen queue: ghost"));

        mockMvc.perform(patch("/kitchen/orders/ghost/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newStatus\":\"PREPARING\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void updateStatus_wrongTransition_returns409() throws Exception {
        when(kitchenQueueService.markReady(anyString(), any(), any()))
                .thenThrow(new ResponseStatusException(HttpStatus.CONFLICT, "Expected order in PREPARING but was RECEIVED"));

        mockMvc.perform(patch("/kitchen/orders/order-1/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newStatus\":\"READY\"}"))
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
