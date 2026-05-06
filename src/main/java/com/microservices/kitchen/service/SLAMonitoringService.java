package com.microservices.kitchen.service;

import com.microservices.kitchen.dto.KitchenDtos;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Set;

@Slf4j
@Service
public class SLAMonitoringService {

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    // Run every 30 seconds
    @Scheduled(fixedDelay = 30000)
    public void checkSLAAlerts() {
        // Get all branches (you would normally have a registry of branches)
        // For now, iterate over all kitchen queues in Redis
        
        try {
            Set<String> keys = redisTemplate.keys("kitchen:queue:*:ready");
            
            if (keys != null) {
                for (String key : keys) {
                    String branchId = key.split(":")[2];
                    checkBranchSLA(Long.valueOf(branchId));
                }
            }
        } catch (Exception e) {
            log.error("Error checking SLA alerts", e);
        }
    }

    private void checkBranchSLA(Long branchId) {
        // Get all orders in received status (older first)
        // Check if any are older than 15 minutes (AMBER) or 25 minutes (RED)
        
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime amber15MinAgo = now.minus(15, ChronoUnit.MINUTES);
        LocalDateTime red25MinAgo = now.minus(25, ChronoUnit.MINUTES);

        log.debug("Checking SLA for branch: {}", branchId);

        // In production, iterate over orders and check their timestamps
        // Send alerts via WebSocket when thresholds are exceeded
    }
}
