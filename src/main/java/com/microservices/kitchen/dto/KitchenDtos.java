package com.microservices.kitchen.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public class KitchenDtos {

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class OrderReceivedEvent {
        private Long orderId;
        private String orderNumber;
        private Long branchId;
        private List<OrderItem> items;
        private LocalDateTime createdAt;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class OrderItem {
        private String itemName;
        private Integer quantity;
        private String specialInstructions;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class KitchenQueueItem {
        private Long orderId;
        private String orderNumber;
        private String status; // RECEIVED, PREPARING, READY
        private List<OrderItem> items;
        private LocalDateTime receivedAt;
        private LocalDateTime readyAt;
        private Long slaMinutes; // Minutes since received
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class SLAAlert {
        private Long orderId;
        private String severity; // AMBER (>15min), RED (>25min)
        private Long minutesOverdue;
        private String message;
    }
}
