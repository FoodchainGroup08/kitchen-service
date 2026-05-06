package com.microservices.kitchen.service;

import com.microservices.kitchen.dto.KitchenDtos;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;

@Slf4j
@Service
public class KitchenQueueService {

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public void addOrderToQueue(KitchenDtos.OrderReceivedEvent event) {
        try {
            String branchId = event.getBranchId().toString();
            String orderId = event.getOrderId().toString();
            
            // Create queue item
            KitchenDtos.KitchenQueueItem queueItem = KitchenDtos.KitchenQueueItem.builder()
                    .orderId(event.getOrderId())
                    .orderNumber(event.getOrderNumber())
                    .status("RECEIVED")
                    .items(event.getItems())
                    .receivedAt(event.getCreatedAt())
                    .build();

            String queueItemJson = objectMapper.writeValueAsString(queueItem);

            // Add to received queue: kitchen:queue:{branchId}:received
            redisTemplate.opsForList().rightPush("kitchen:queue:" + branchId + ":received", queueItemJson);

            // Add to sorted set for SLA tracking: kitchen:queue:{branchId}:ready
            long timestamp = event.getCreatedAt()
                    .atZone(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli();
            redisTemplate.opsForZSet().add("kitchen:queue:" + branchId + ":ready", orderId, timestamp);

            // Publish to WebSocket
            messagingTemplate.convertAndSend("/topic/kitchen/" + branchId, 
                    "Order " + event.getOrderNumber() + " received");

            log.info("Order {} added to kitchen queue for branch {}", orderId, branchId);
        } catch (Exception e) {
            log.error("Error adding order to queue", e);
        }
    }

    public void updateOrderStatus(Long branchId, Long orderId, String newStatus) {
        try {
            String branchIdStr = branchId.toString();
            String orderIdStr = orderId.toString();

            // Move order between queues based on status
            switch (newStatus) {
                case "PREPARING":
                    // Move from received to preparing
                    redisTemplate.opsForList().leftPop("kitchen:queue:" + branchIdStr + ":received");
                    // Add to preparing queue
                    break;
                case "READY":
                    // Move from preparing to ready
                    redisTemplate.opsForList().leftPop("kitchen:queue:" + branchIdStr + ":preparing");
                    break;
            }

            // Publish update to WebSocket
            messagingTemplate.convertAndSend("/topic/kitchen/" + branchIdStr, 
                    "Order updated to status: " + newStatus);

        } catch (Exception e) {
            log.error("Error updating order status", e);
        }
    }
}
