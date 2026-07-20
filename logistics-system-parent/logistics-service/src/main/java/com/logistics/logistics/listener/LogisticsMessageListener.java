package com.logistics.logistics.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.logistics.logistics.service.LogisticsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Random;

@Slf4j
@Component
@RequiredArgsConstructor
public class LogisticsMessageListener {

    private final LogisticsService logisticsService;
    private final ObjectMapper objectMapper;

    @RabbitListener(queues = "logistics.queue")
    public void handleOrderCreated(String message) {
        try {
            log.info("收到物流创建消息: {}", message);
            Map<String, Object> msg = objectMapper.readValue(message, Map.class);
            Long orderId = Long.valueOf(msg.get("orderId").toString());

            String logisticsNo = generateLogisticsNo();
            logisticsService.createLogistics(logisticsNo, orderId);

            log.info("物流单已创建: orderId={}, logisticsNo={}", orderId, logisticsNo);
        } catch (Exception e) {
            log.error("处理物流创建消息失败: {}", message, e);
        }
    }

    private String generateLogisticsNo() {
        String date = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        String random = String.format("%04d", new Random().nextInt(10000));
        return "LOG" + date + random;
    }
}
