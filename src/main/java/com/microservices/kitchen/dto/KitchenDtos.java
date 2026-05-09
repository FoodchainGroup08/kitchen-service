package com.microservices.kitchen.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public class KitchenDtos {

    // ── Inbound Kafka event (order.received from order-service) ───────────────

    @Data @NoArgsConstructor @AllArgsConstructor @Builder
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class OrderReceivedEvent {
        private String orderId;
        private String customerId;
        private String branchId;
        private String status;
        private BigDecimal totalAmount;
        private String orderType;    // DINE_IN, TAKEAWAY, DELIVERY
        private String tableNumber;
        private String notes;
        private List<OrderItemEvent> items;
    }

    @Data @NoArgsConstructor @AllArgsConstructor @Builder
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class OrderItemEvent {
        private String menuItemId;
        private String menuItemName;
        private Integer quantity;
        private BigDecimal unitPrice;
        private String specialInstructions;
    }

    // ── Internal Redis model ──────────────────────────────────────────────────

    @Data @NoArgsConstructor @AllArgsConstructor @Builder
    public static class KitchenOrder {
        private String orderId;
        private String customerId;
        private String branchId;
        private String orderType;
        private String tableNumber;
        private String notes;
        private BigDecimal totalAmount;
        private String status;           // RECEIVED, PREPARING, READY
        private List<KitchenOrderItem> items;
        private LocalDateTime receivedAt;
        private LocalDateTime acceptedAt;
        private LocalDateTime readyAt;
    }

    @Data @NoArgsConstructor @AllArgsConstructor @Builder
    public static class KitchenOrderItem {
        private String menuItemId;
        private String menuItemName;
        private Integer quantity;
        private String specialInstructions;
    }

    // ── REST responses ────────────────────────────────────────────────────────

    @Data @NoArgsConstructor @AllArgsConstructor @Builder
    public static class KitchenQueueResponse {
        private String branchId;
        private List<KitchenOrder> received;
        private List<KitchenOrder> preparing;
        private List<KitchenOrder> ready;
    }

    // ── REST request for kitchen actions ──────────────────────────────────────

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class KitchenActionRequest {
        private String staffId;
        private String notes;
    }

    // ── SLA alert (used by WebSocket broadcast) ───────────────────────────────

    @Data @NoArgsConstructor @AllArgsConstructor @Builder
    public static class SLAAlert {
        private String orderId;
        private String branchId;
        private String severity;     // AMBER (>15 min), RED (>25 min)
        private long minutesWaiting;
        private String message;
    }

    // ── Outbound to order-service REST API ────────────────────────────────────

    @Data @NoArgsConstructor @AllArgsConstructor @Builder
    public static class UpdateStatusRequest {
        private String newStatus;
        private String updatedBy;
        private String notes;
    }
}
