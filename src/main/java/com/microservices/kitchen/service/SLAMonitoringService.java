package com.microservices.kitchen.service;

import com.microservices.kitchen.dto.KitchenDtos;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Set;

/**
 * Runs every 30 seconds and pushes AMBER (>15 min) / RED (>25 min) alerts
 * to the branch WebSocket topic so kitchen displays can highlight stale orders.
 */
@Slf4j
@Service
public class SLAMonitoringService {

    private static final long AMBER_MINUTES = 15;
    private static final long RED_MINUTES   = 25;

    @Autowired private KitchenQueueServiceImpl queueService;
    @Autowired private SimpMessagingTemplate    ws;

    @Scheduled(fixedDelay = 30_000)
    public void checkSLAAlerts() {
        StringRedisTemplate redis = queueService.getRedisTemplate();
        try {
            // Find all active branch:received ZSets
            Set<String> keys = redis.keys("kitchen:branch:*:received");
            if (keys == null || keys.isEmpty()) return;

            long nowMs  = LocalDateTime.now().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
            long amberMs = AMBER_MINUTES * 60 * 1000;
            long redMs   = RED_MINUTES   * 60 * 1000;

            for (String key : keys) {
                String branchId = key.split(":")[2];
                // Get all orderIds with their score (= receivedAt epoch millis)
                Set<org.springframework.data.redis.core.ZSetOperations.TypedTuple<String>> entries =
                        redis.opsForZSet().rangeWithScores(key, 0, -1);
                if (entries == null) continue;

                for (var entry : entries) {
                    if (entry.getValue() == null || entry.getScore() == null) continue;
                    long waitMs = nowMs - entry.getScore().longValue();
                    if (waitMs < amberMs * 60 * 1000 && waitMs < redMs * 60 * 1000) {
                        // Re-using minutes for readability
                    }
                    long minutesWaiting = ChronoUnit.MINUTES.between(
                            LocalDateTime.ofInstant(
                                    java.time.Instant.ofEpochMilli(entry.getScore().longValue()),
                                    ZoneId.systemDefault()),
                            LocalDateTime.now());

                    String severity = null;
                    if (minutesWaiting >= RED_MINUTES)   severity = "RED";
                    else if (minutesWaiting >= AMBER_MINUTES) severity = "AMBER";

                    if (severity != null) {
                        KitchenDtos.SLAAlert alert = KitchenDtos.SLAAlert.builder()
                                .orderId(entry.getValue())
                                .branchId(branchId)
                                .severity(severity)
                                .minutesWaiting(minutesWaiting)
                                .message("Order waiting " + minutesWaiting + " min — " + severity)
                                .build();
                        ws.convertAndSend("/topic/kitchen/" + branchId + "/sla", alert);
                        log.warn("SLA {} — order {} branch {} waiting {}min",
                                severity, entry.getValue(), branchId, minutesWaiting);
                    }
                }
            }
        } catch (Exception e) {
            log.error("SLA check failed: {}", e.getMessage());
        }
    }
}
