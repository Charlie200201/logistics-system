package com.logistics.logistics.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.logistics.logistics.entity.Logistics;
import com.logistics.logistics.service.LogisticsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Random;

@Slf4j
@Component
@RequiredArgsConstructor
public class LogisticsTrackTask {

    private final LogisticsService logisticsService;

    private static final String[] LOCATIONS = {
            "北京分拣中心", "上海转运中心", "广州物流园",
            "深圳配送站", "杭州集散中心", "成都中转站",
            "武汉物流基地", "南京分拨中心", "重庆配送中心",
            "西安物流枢纽"
    };

    private static final String[] DESCRIPTIONS = {
            "快件已到达", "快件已发出", "正在中转",
            "已扫描入库", "正在派送中", "快件已签收"
    };

    private final Random random = new Random();

    @Scheduled(fixedDelay = 30000)
    public void updateLogisticsLocation() {
        LambdaQueryWrapper<Logistics> wrapper = new LambdaQueryWrapper<>();
        wrapper.in(Logistics::getStatus, "PENDING", "IN_TRANSIT");
        List<Logistics> list = logisticsService.list(wrapper);

        if (list.isEmpty()) {
            return;
        }

        for (Logistics logistics : list) {
            String location = LOCATIONS[random.nextInt(LOCATIONS.length)];
            String description = DESCRIPTIONS[random.nextInt(DESCRIPTIONS.length)];

            logisticsService.updateCurrentLocation(logistics.getId(), location);
            logisticsService.addTrack(logistics.getId(), location, description);
            log.info("物流轨迹更新: logisticsNo={}, location={}, description={}",
                    logistics.getLogisticsNo(), location, description);
        }
    }
}
