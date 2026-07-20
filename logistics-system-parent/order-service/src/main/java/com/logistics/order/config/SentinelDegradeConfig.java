package com.logistics.order.config;

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

        // user-service 熔断规则
        DegradeRule userRule = new DegradeRule("GET:http://user-service/api/users/{id}")
                .setGrade(RuleConstant.DEGRADE_GRADE_EXCEPTION_RATIO)   // 异常比例模式
                .setCount(0.5)             // 50% 请求失败 → 触发熔断
                .setMinRequestAmount(5)     // 最少 5 个请求后才开始统计
                .setStatIntervalMs(10000)   // 统计窗口 10 秒
                .setTimeWindow(60);         // 熔断持续 60 秒，之后进入半开状态
        rules.add(userRule);

        // product-service 熔断规则
        DegradeRule productRule = new DegradeRule("GET:http://product-service/api/products/{id}")
                .setGrade(RuleConstant.DEGRADE_GRADE_EXCEPTION_RATIO)
                .setCount(0.5)
                .setMinRequestAmount(5)
                .setStatIntervalMs(10000)
                .setTimeWindow(60);
        rules.add(productRule);

        // product-service 扣减库存 — 单独规则（这个方法最关键，更敏感）
        DegradeRule stockRule = new DegradeRule("POST:http://product-service/api/products/{id}/deduct-stock")
                .setGrade(RuleConstant.DEGRADE_GRADE_EXCEPTION_RATIO)
                .setCount(0.3)             // 30% 失败就熔断（写操作更敏感）
                .setMinRequestAmount(3)     // 3 个请求开始统计
                .setStatIntervalMs(10000)
                .setTimeWindow(120);        // 持续 120 秒
        rules.add(stockRule);

        DegradeRuleManager.loadRules(rules);
    }
}
