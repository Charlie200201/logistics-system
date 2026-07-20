package com.logistics.logistics.feign.fallback;

import com.logistics.common.result.Result;
import com.logistics.logistics.feign.OrderFeignClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class OrderFeignClientFallback implements OrderFeignClient {

    @Override
    public Result<List<Map<String, Object>>> getExpiredOrders(int minutes) {
        log.error("order-service 调用失败，触发熔断降级: getExpiredOrders({})", minutes);
        return Result.ok(Collections.emptyList());
    }

    @Override
    public Result<?> cancelOrder(Long id) {
        log.error("order-service 调用失败，触发熔断降级: cancelOrder({})", id);
        return Result.fail(429, "订单服务暂不可用，请稍后重试");
    }

    @Override
    public Result<Map<String, Object>> getDailyStats(String date) {
        log.error("order-service 调用失败，触发熔断降级: getDailyStats({})", date);
        return Result.fail(429, "订单服务暂不可用，请稍后重试");
    }
}
