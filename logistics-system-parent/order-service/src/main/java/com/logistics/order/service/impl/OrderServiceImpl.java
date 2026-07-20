package com.logistics.order.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.logistics.common.exception.BusinessException;
import com.logistics.common.result.Result;
import com.logistics.common.result.ResultCode;
import com.logistics.order.entity.Order;
import com.logistics.order.feign.ProductFeignClient;
import com.logistics.order.feign.UserFeignClient;
import com.logistics.order.mapper.OrderMapper;
import com.logistics.order.service.OrderService;
import io.seata.spring.annotation.GlobalTransactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderServiceImpl extends ServiceImpl<OrderMapper, Order> implements OrderService {

    private final UserFeignClient userFeignClient;
    private final ProductFeignClient productFeignClient;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;

    private static final String EXCHANGE = "logistics.exchange";
    private static final String ROUTING_KEY = "logistics.create";

    @Override
    @GlobalTransactional(name = "create-order-tx", timeoutMills = 300000)
    public Order createOrder(Order order) {
        // ① 验证用户是否存在
        Result<Map<String, Object>> userResult = userFeignClient.getUserById(order.getUserId());
        if (userResult.getCode() != 200 || userResult.getData() == null) {
            throw new BusinessException(ResultCode.USER_NOT_FOUND);
        }
        log.info("用户验证通过: userId={}", order.getUserId());

        // ② 查询商品信息获取价格
        Result<Map<String, Object>> productResult = productFeignClient.getProductById(order.getProductId());
        if (productResult.getCode() != 200 || productResult.getData() == null) {
            throw new BusinessException(ResultCode.PRODUCT_NOT_FOUND);
        }
        Map<String, Object> productData = productResult.getData();
        BigDecimal price = new BigDecimal(productData.get("price").toString());
        log.info("商品查询成功: productId={}, price={}", order.getProductId(), price);

        // ③ Feign 调用扣减库存（分布式锁在product-service内部，Seata保证分布式事务）
        Map<String, Integer> body = new HashMap<>();
        body.put("quantity", order.getQuantity());
        Result<Boolean> deductResult = productFeignClient.deductStock(order.getProductId(), body);
        if (deductResult.getCode() != 200 || deductResult.getData() == null || !deductResult.getData()) {
            throw new BusinessException(ResultCode.STOCK_INSUFFICIENT);
        }
        log.info("库存扣减成功: productId={}, quantity={}", order.getProductId(), order.getQuantity());

        // ④ 本地写入订单（Seata全局事务的一部分）
        order.setOrderNo(generateOrderNo());
        order.setTotalAmount(price.multiply(BigDecimal.valueOf(order.getQuantity())));
        order.setStatus("PENDING_PAYMENT");
        this.save(order);
        log.info("订单创建成功: orderNo={}, orderId={}", order.getOrderNo(), order.getId());

        // ⑤ 事务提交后发送消息到 RabbitMQ（保证数据一致性）
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    Map<String, Object> message = new LinkedHashMap<>();
                    message.put("orderId", order.getId());
                    message.put("userId", order.getUserId());
                    message.put("productId", order.getProductId());
                    message.put("quantity", order.getQuantity());
                    message.put("address", order.getAddress());
                    rabbitTemplate.convertAndSend(EXCHANGE, ROUTING_KEY, objectMapper.writeValueAsString(message));
                    log.info("物流消息已发送: orderId={}", order.getId());
                } catch (Exception e) {
                    log.error("发送物流消息失败: orderId={}", order.getId(), e);
                }
            }
        });

        return order;
    }

    @Override
    public Order getOrderById(Long id) {
        Order order = this.getById(id);
        if (order == null) {
            throw new BusinessException(ResultCode.ORDER_NOT_FOUND);
        }
        return order;
    }

    @Override
    public List<Order> getOrdersByUserId(Long userId) {
        LambdaQueryWrapper<Order> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Order::getUserId, userId).orderByDesc(Order::getCreatedAt);
        return this.list(wrapper);
    }

    @Override
    public void updateOrderStatus(Long orderId, String status) {
        Order order = this.getById(orderId);
        if (order == null) {
            throw new BusinessException(ResultCode.ORDER_NOT_FOUND);
        }
        order.setStatus(status);
        this.updateById(order);
        log.info("订单状态更新: orderId={}, status={}", orderId, status);
    }

    private String generateOrderNo() {
        String date = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        String random = String.format("%04d", new Random().nextInt(10000));
        return "ORD" + date + random;
    }

    @Override
    public List<Order> getExpiredOrders(int minutes) {
        LocalDateTime deadline = LocalDateTime.now().minusMinutes(minutes);
        LambdaQueryWrapper<Order> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Order::getStatus, "PENDING_PAYMENT")
                .lt(Order::getCreatedAt, deadline);
        return this.list(wrapper);
    }

    @Override
    public Map<String, Object> getDailyStats(String date) {
        LocalDateTime start = LocalDateTime.parse(date + "T00:00:00");
        LocalDateTime end = LocalDateTime.parse(date + "T23:59:59");
        LambdaQueryWrapper<Order> wrapper = new LambdaQueryWrapper<>();
        wrapper.between(Order::getCreatedAt, start, end);
        List<Order> orders = this.list(wrapper);

        int totalCount = orders.size();
        BigDecimal totalAmount = orders.stream()
                .map(Order::getTotalAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("date", date);
        stats.put("totalCount", totalCount);
        stats.put("totalAmount", totalAmount);
        return stats;
    }

    @Override
    @Transactional
    public void cancelOrder(Long orderId) {
        Order order = this.getById(orderId);
        if (order == null) {
            throw new BusinessException(ResultCode.ORDER_NOT_FOUND);
        }
        order.setStatus("CANCELLED");
        this.updateById(order);
        log.info("订单已取消: orderId={}", orderId);
    }
}
