package com.logistics.logistics.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.logistics.common.exception.BusinessException;
import com.logistics.common.result.ResultCode;
import com.logistics.logistics.entity.Logistics;
import com.logistics.logistics.entity.LogisticsTrack;
import com.logistics.logistics.mapper.LogisticsMapper;
import com.logistics.logistics.mapper.LogisticsTrackMapper;
import com.logistics.logistics.service.LogisticsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class LogisticsServiceImpl extends ServiceImpl<LogisticsMapper, Logistics> implements LogisticsService {

    private final LogisticsTrackMapper logisticsTrackMapper;

    @Override
    @Transactional
    public Logistics createLogistics(String logisticsNo, Long orderId) {
        Logistics logistics = new Logistics();
        logistics.setLogisticsNo(logisticsNo);
        logistics.setOrderId(orderId);
        logistics.setStatus("PENDING");
        logistics.setCurrentLocation("仓库");
        this.save(logistics);
        log.info("物流单创建成功: logisticsNo={}, orderId={}", logisticsNo, orderId);

        addTrack(logistics.getId(), "仓库", "订单已生成，等待揽收");
        return logistics;
    }

    @Override
    public Logistics getByLogisticsNo(String logisticsNo) {
        LambdaQueryWrapper<Logistics> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Logistics::getLogisticsNo, logisticsNo);
        Logistics logistics = this.getOne(wrapper);
        if (logistics == null) {
            throw new BusinessException(ResultCode.LOGISTICS_NOT_FOUND);
        }
        return logistics;
    }

    @Override
    public Logistics getByOrderId(Long orderId) {
        LambdaQueryWrapper<Logistics> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Logistics::getOrderId, orderId);
        return this.getOne(wrapper);
    }

    @Override
    public List<LogisticsTrack> getTracks(Long logisticsId) {
        LambdaQueryWrapper<LogisticsTrack> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(LogisticsTrack::getLogisticsId, logisticsId)
                .orderByAsc(LogisticsTrack::getCreatedAt);
        return logisticsTrackMapper.selectList(wrapper);
    }

    @Override
    public void addTrack(Long logisticsId, String location, String description) {
        LogisticsTrack track = new LogisticsTrack();
        track.setLogisticsId(logisticsId);
        track.setLocation(location);
        track.setDescription(description);
        logisticsTrackMapper.insert(track);
        log.info("物流轨迹更新: logisticsId={}, location={}", logisticsId, location);
    }

    @Override
    public void updateCurrentLocation(Long logisticsId, String location) {
        Logistics logistics = this.getById(logisticsId);
        if (logistics != null) {
            logistics.setCurrentLocation(location);
            if ("PENDING".equals(logistics.getStatus())) {
                logistics.setStatus("IN_TRANSIT");
            }
            this.updateById(logistics);
        }
    }
}
