package com.microservices.kitchen.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microservices.kitchen.dto.KitchenDtos;
import com.microservices.kitchen.service.KitchenQueueService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class KitchenEventConsumer {

    @Autowired private KitchenQueueService kitchenQueueService;
    @Autowired private ObjectMapper objectMapper;

    @KafkaListener(topics = "order.received", groupId = "kitchen-service-group")
    public void handleOrderReceived(String message) {
        try {
            KitchenDtos.OrderReceivedEvent event =
                    objectMapper.readValue(message, KitchenDtos.OrderReceivedEvent.class);
            log.info("Kitchen received order: orderId={} branchId={} type={}",
                    event.getOrderId(), event.getBranchId(), event.getOrderType());
            kitchenQueueService.enqueue(event);
        } catch (Exception e) {
            log.error("Failed to process order.received: {}", e.getMessage(), e);
        }
    }
}
