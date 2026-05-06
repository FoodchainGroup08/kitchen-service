package com.microservices.kitchen.kafka;

import com.microservices.kitchen.dto.KitchenDtos;
import com.microservices.kitchen.service.KitchenQueueService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class KitchenEventConsumer {

    @Autowired
    private KitchenQueueService kitchenQueueService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @KafkaListener(topics = "order.received", groupId = "kitchen-service-group")
    public void handleOrderReceived(String message) {
        try {
            KitchenDtos.OrderReceivedEvent event = objectMapper.readValue(message, KitchenDtos.OrderReceivedEvent.class);
            log.info("Received order.received event: {}", event.getOrderNumber());
            kitchenQueueService.addOrderToQueue(event);
        } catch (Exception e) {
            log.error("Error processing order.received event", e);
        }
    }

    @KafkaListener(topics = "order.status.updated", groupId = "kitchen-service-group")
    public void handleOrderStatusUpdated(String message) {
        try {
            log.info("Received order.status.updated event: {}", message);
            // Update kitchen queue based on status change
        } catch (Exception e) {
            log.error("Error processing order.status.updated event", e);
        }
    }
}
