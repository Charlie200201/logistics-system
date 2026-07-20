package com.logistics.order.feign.fallback;

import com.logistics.common.result.Result;
import com.logistics.order.feign.UserFeignClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
public class UserFeignClientFallback implements UserFeignClient {

    @Override
    public Result<Map<String, Object>> getUserById(Long id) {
        log.error("user-service 调用失败，触发熔断降级: getUserById({})", id);
        return Result.fail(429, "用户服务暂不可用，请稍后重试");
    }

    @Override
    public Result<Boolean> verifyToken(String token) {
        log.error("user-service 调用失败，触发熔断降级: verifyToken");
        return Result.fail(429, "用户服务暂不可用，请稍后重试");
    }
}
