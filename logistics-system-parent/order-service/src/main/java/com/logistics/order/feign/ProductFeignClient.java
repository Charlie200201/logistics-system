package com.logistics.order.feign;

import com.logistics.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

import com.logistics.order.feign.fallback.ProductFeignClientFallback;

@FeignClient(name = "product-service", fallback = ProductFeignClientFallback.class)
public interface ProductFeignClient {

    @GetMapping("/api/products/{id}")
    Result<Map<String, Object>> getProductById(@PathVariable("id") Long id);

    @PostMapping("/api/products/{id}/deduct-stock")
    Result<Boolean> deductStock(@PathVariable("id") Long id, @RequestBody Map<String, Integer> body);
}
