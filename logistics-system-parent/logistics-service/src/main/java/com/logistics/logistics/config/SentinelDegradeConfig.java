package com.logistics.logistics.config;

import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRule;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRuleManager;
import org.springframework.context.annotation.Configuration;

import javax.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;

@Configuration
public class SentinelDegradeConfig {

    @PostConstruct
    public void initDegradeRules() {
        List<DegradeRule> rules = new ArrayList<>();

        // order-service 熔断规则
        DegradeRule orderRule = new DegradeRule("GET:http://order-service/api/orders/expired")
                .setGrade(RuleConstant.DEGRADE_GRADE_EXCEPTION_RATIO)
                .setCount(0.5)
                .setMinRequestAmount(3)     // 定时任务每 5 分钟调一次，门槛设低
                .setStatIntervalMs(300000)  // 统计窗口 5 分钟
                .setTimeWindow(120);        // 熔断 120 秒
        rules.add(orderRule);

        // product-service 恢复库存的熔断规则
        DegradeRule stockRule = new DegradeRule("POST:http://product-service/api/products/{id}/restore-stock")
                .setGrade(RuleConstant.DEGRADE_GRADE_EXCEPTION_RATIO)
                .setCount(0.3)
                .setMinRequestAmount(3)
                .setStatIntervalMs(10000)
                .setTimeWindow(120);
        rules.add(stockRule);

        DegradeRuleManager.loadRules(rules);
    }
}
