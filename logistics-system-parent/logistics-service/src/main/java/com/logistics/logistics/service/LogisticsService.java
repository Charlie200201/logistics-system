package com.logistics.logistics.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.logistics.logistics.entity.Logistics;
import com.logistics.logistics.entity.LogisticsTrack;

import java.util.List;

public interface LogisticsService extends IService<Logistics> {
    Logistics createLogistics(String logisticsNo, Long orderId);
    Logistics getByLogisticsNo(String logisticsNo);
    Logistics getByOrderId(Long orderId);
    List<LogisticsTrack> getTracks(Long logisticsId);
    void addTrack(Long logisticsId, String location, String description);
    void updateCurrentLocation(Long logisticsId, String location);
}
