package com.logistics.logistics.controller;

import com.logistics.common.result.Result;
import com.logistics.logistics.entity.Logistics;
import com.logistics.logistics.entity.LogisticsTrack;
import com.logistics.logistics.service.LogisticsService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Api(tags = "物流服务")
@RestController
@RequestMapping("/api/logistics")
@RequiredArgsConstructor
public class LogisticsController {

    private final LogisticsService logisticsService;

    @ApiOperation("根据物流单号查询物流信息")
    @GetMapping("/{logisticsNo}")
    public Result<Map<String, Object>> getByNo(@PathVariable String logisticsNo) {
        Logistics logistics = logisticsService.getByLogisticsNo(logisticsNo);
        List<LogisticsTrack> tracks = logisticsService.getTracks(logistics.getId());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("logistics", logistics);
        result.put("tracks", tracks);
        return Result.ok(result);
    }

    @ApiOperation("根据订单ID查询物流信息")
    @GetMapping("/order/{orderId}")
    public Result<Logistics> getByOrderId(@PathVariable Long orderId) {
        return Result.ok(logisticsService.getByOrderId(orderId));
    }
}
