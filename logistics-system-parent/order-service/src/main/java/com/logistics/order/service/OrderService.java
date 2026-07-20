package com.logistics.order.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.logistics.order.entity.Order;

import java.util.List;
import java.util.Map;

public interface OrderService extends IService<Order> {
    Order createOrder(Order order);
    Order getOrderById(Long id);
    List<Order> getOrdersByUserId(Long userId);
    void updateOrderStatus(Long orderId, String status);
    List<Order> getExpiredOrders(int minutes);
    Map<String, Object> getDailyStats(String date);
    void cancelOrder(Long orderId);
}
