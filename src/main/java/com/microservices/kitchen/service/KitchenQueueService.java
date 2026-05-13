package com.microservices.kitchen.service;

import com.microservices.kitchen.dto.KitchenDtos;

public interface KitchenQueueService {
    void enqueue(KitchenDtos.OrderReceivedEvent event);
    KitchenDtos.KitchenQueueResponse getQueue(String branchId, int page, int size);
    KitchenDtos.KitchenOrder acceptOrder(String orderId, String staffId, String notes);
    KitchenDtos.KitchenOrder markReady(String orderId, String staffId, String notes);
    KitchenDtos.KitchenOrder serveOrder(String orderId, String staffId, String notes);
    KitchenDtos.KitchenOrder pickupOrder(String orderId, String staffId, String notes);
}
