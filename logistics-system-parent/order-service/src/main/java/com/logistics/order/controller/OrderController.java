package com.logistics.order.controller;

import com.logistics.common.result.Result;
import com.logistics.order.entity.Order;
import com.logistics.order.service.OrderService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Api(tags = "订单服务")
@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    @ApiOperation("创建订单")
    @PostMapping
    public Result<Order> create(@RequestBody Order order) {
        return Result.ok(orderService.createOrder(order));
    }

    @ApiOperation("查询订单详情")
    @GetMapping("/{id}")
    public Result<Order> getById(@PathVariable Long id) {
        return Result.ok(orderService.getOrderById(id));
    }

    @ApiOperation("查询用户所有订单")
    @GetMapping("/user/{userId}")
    public Result<List<Order>> getByUserId(@PathVariable Long userId) {
        return Result.ok(orderService.getOrdersByUserId(userId));
    }

    @ApiOperation("更新订单状态（物流回调）")
    @PutMapping("/{id}/status")
    public Result<?> updateStatus(@PathVariable Long id, @RequestBody Map<String, String> body) {
        orderService.updateOrderStatus(id, body.get("status"));
        return Result.ok();
    }

    @ApiOperation("查询超时未支付订单")
    @GetMapping("/expired")
    public Result<List<Order>> getExpiredOrders(@RequestParam(defaultValue = "30") int minutes) {
        return Result.ok(orderService.getExpiredOrders(minutes));
    }

    @ApiOperation("每日订单统计")
    @GetMapping("/stats/daily")
    public Result<Map<String, Object>> getDailyStats(@RequestParam String date) {
        return Result.ok(orderService.getDailyStats(date));
    }

    @ApiOperation("取消订单")
    @PutMapping("/{id}/cancel")
    public Result<?> cancelOrder(@PathVariable Long id) {
        orderService.cancelOrder(id);
        return Result.ok();
    }
}
