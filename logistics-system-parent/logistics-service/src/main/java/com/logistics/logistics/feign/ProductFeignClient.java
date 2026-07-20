package com.logistics.logistics.feign;

import com.logistics.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

import com.logistics.logistics.feign.fallback.ProductFeignClientFallback;

@FeignClient(name = "product-service", fallback = ProductFeignClientFallback.class)
public interface ProductFeignClient {

    @PostMapping("/api/products/{id}/restore-stock")
    Result<Boolean> restoreStock(@PathVariable("id") Long id, @RequestBody Map<String, Integer> body);
}
