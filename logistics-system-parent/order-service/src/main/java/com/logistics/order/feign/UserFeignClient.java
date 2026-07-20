package com.logistics.order.feign;

import com.logistics.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

import com.logistics.order.feign.fallback.UserFeignClientFallback;

@FeignClient(name = "user-service", fallback = UserFeignClientFallback.class)
public interface UserFeignClient {

    @GetMapping("/api/users/{id}")
    Result<Map<String, Object>> getUserById(@PathVariable("id") Long id);

    @GetMapping("/api/users/verify")
    Result<Boolean> verifyToken(@RequestParam("token") String token);
}
