package com.logistics.logistics.job;

import com.logistics.common.result.Result;
import com.logistics.logistics.feign.OrderFeignClient;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class DailyStatisticsJob {

    private final OrderFeignClient orderFeignClient;

    @XxlJob("dailyOrderStatisticsJob")
    public void execute() {
        log.info("========== 每日订单统计任务开始 ==========");
        try {
            String yesterday = LocalDate.now().minusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE);
            Result<Map<String, Object>> result = orderFeignClient.getDailyStats(yesterday);

            if (result.getCode() == 200 && result.getData() != null) {
                Map<String, Object> stats = result.getData();
                log.info("===== 订单统计 ({}) =====", yesterday);
                log.info("订单总数: {}", stats.get("totalCount"));
                log.info("订单总金额: {}", stats.get("totalAmount"));
                log.info("==============================");
            } else {
                log.warn("获取每日统计失败: code={}", result.getCode());
            }
        } catch (Exception e) {
            log.error("每日订单统计任务执行异常", e);
        }
        log.info("========== 每日订单统计任务结束 ==========");
    }
}
