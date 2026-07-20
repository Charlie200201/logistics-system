package com.logistics.logistics.feign;

import com.logistics.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

import com.logistics.logistics.feign.fallback.OrderFeignClientFallback;

@FeignClient(name = "order-service", fallback = OrderFeignClientFallback.class)
public interface OrderFeignClient {

    @GetMapping("/api/orders/expired")
    Result<List<Map<String, Object>>> getExpiredOrders(@RequestParam("minutes") int minutes);

    @PutMapping("/api/orders/{id}/cancel")
    Result<?> cancelOrder(@PathVariable("id") Long id);

    @GetMapping("/api/orders/stats/daily")
    Result<Map<String, Object>> getDailyStats(@RequestParam("date") String date);
}
