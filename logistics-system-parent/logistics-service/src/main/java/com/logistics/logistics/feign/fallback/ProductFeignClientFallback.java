package com.logistics.logistics.feign.fallback;

import com.logistics.common.result.Result;
import com.logistics.logistics.feign.ProductFeignClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
public class ProductFeignClientFallback implements ProductFeignClient {

    @Override
    public Result<Boolean> restoreStock(Long id, Map<String, Integer> body) {
        log.error("product-service 调用失败，触发熔断降级: restoreStock({})", id);
        return Result.fail(429, "商品服务暂不可用，请稍后重试");
    }
}
