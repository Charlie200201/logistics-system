package com.logistics.logistics.job;

import com.logistics.common.result.Result;
import com.logistics.logistics.feign.OrderFeignClient;
import com.logistics.logistics.feign.ProductFeignClient;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderTimeoutJob {

    private final OrderFeignClient orderFeignClient;
    private final ProductFeignClient productFeignClient;

    @XxlJob("orderTimeoutCancelJob")
    public void execute() {
        log.info("========== 超时订单自动取消任务开始 ==========");
        try {
            Result<List<Map<String, Object>>> result = orderFeignClient.getExpiredOrders(30);
            if (result.getCode() != 200 || result.getData() == null) {
                log.warn("查询超时订单失败: code={}", result.getCode());
                return;
            }
            List<Map<String, Object>> expiredOrders = result.getData();
            log.info("发现超时未支付订单数量: {}", expiredOrders.size());

            for (Map<String, Object> order : expiredOrders) {
                Long orderId = Long.valueOf(order.get("id").toString());
                Long productId = Long.valueOf(order.get("productId").toString());
                Integer quantity = Integer.valueOf(order.get("quantity").toString());

                try {
                    // 取消订单
                    orderFeignClient.cancelOrder(orderId);
                    // 恢复库存
                    Map<String, Integer> body = new HashMap<>();
                    body.put("quantity", quantity);
                    productFeignClient.restoreStock(productId, body);
                    log.info("超时订单已取消并恢复库存: orderId={}, productId={}, quantity={}",
                            orderId, productId, quantity);
                } catch (Exception e) {
                    log.error("处理超时订单失败: orderId={}", orderId, e);
                }
            }
        } catch (Exception e) {
            log.error("超时订单取消任务执行异常", e);
        }
        log.info("========== 超时订单自动取消任务结束 ==========");
    }
}
