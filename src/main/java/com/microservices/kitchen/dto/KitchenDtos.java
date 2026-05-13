package com.microservices.kitchen.dto;

import com.fasterxml.jackson.annotation.JsonGetter;
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
        private String customerName;
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
        /** Alias exposed to the frontend as {@code id}. */
        @JsonGetter("id")
        public String getId() { return orderId; }

        private String customerId;
        private String customerName;
        private String branchId;
        private String orderType;
        private String tableNumber;
        private String notes;
        private BigDecimal totalAmount;
        /**
         * Stored internally as uppercase (RECEIVED, PREPARING, READY).
         * The {@link #getDisplayStatus()} getter serialises it as lowercase
         * for the frontend.
         */
        private String status;
        private List<KitchenOrderItem> items;
        private LocalDateTime receivedAt;
        private LocalDateTime acceptedAt;
        private LocalDateTime readyAt;

        /**
         * Returns the status in lowercase for frontend consumption
         * (e.g. "received", "preparing", "ready").
         */
        @JsonGetter("displayStatus")
        public String getDisplayStatus() {
            return status != null ? status.toLowerCase() : null;
        }

        /**
         * Returns the orderType in frontend hyphenated-lowercase format
         * (DINE_IN → "dine-in", TAKEAWAY → "takeaway", DELIVERY → "delivery").
         */
        @JsonGetter("displayOrderType")
        public String getDisplayOrderType() {
            if (orderType == null) return null;
            return switch (orderType.toUpperCase()) {
                case "DINE_IN" -> "dine-in";
                case "TAKEAWAY" -> "takeaway";
                case "DELIVERY" -> "delivery";
                default -> orderType.toLowerCase().replace("_", "-");
            };
        }
    }

    @Data @NoArgsConstructor @AllArgsConstructor @Builder
    public static class KitchenOrderItem {
        private String menuItemId;
        /** Alias exposed to the frontend as {@code id}. */
        @JsonGetter("id")
        public String getId() { return menuItemId; }

        private String menuItemName;
        /** Alias exposed to the frontend as {@code name}. */
        @JsonGetter("name")
        public String getName() { return menuItemName; }

        private Integer quantity;
        private String specialInstructions;
    }

    // ── REST responses ────────────────────────────────────────────────────────

    @Data @NoArgsConstructor @AllArgsConstructor @Builder
    public static class StatusGroup {
        private long total;
        private int page;
        private int size;
        private List<KitchenOrder> orders;
    }

    @Data @NoArgsConstructor @AllArgsConstructor @Builder
    public static class KitchenQueueResponse {
        private String branchId;
        private int page;
        private int size;
        private StatusGroup received;
        private StatusGroup preparing;
        private StatusGroup ready;
    }

    // ── REST request for kitchen actions ──────────────────────────────────────

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class KitchenActionRequest {
        private String staffId;
        private String notes;
    }

    // ── REST request for unified status update (PATCH endpoint) ──────────────

    public record KitchenStatusUpdateRequest(String newStatus, String staffId, String notes) {}

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
