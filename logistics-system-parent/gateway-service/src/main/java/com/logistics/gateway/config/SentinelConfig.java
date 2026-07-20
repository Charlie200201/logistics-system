package com.logistics.gateway.config;

import com.alibaba.csp.sentinel.adapter.gateway.common.SentinelGatewayConstants;
import com.alibaba.csp.sentinel.adapter.gateway.common.api.ApiDefinition;
import com.alibaba.csp.sentinel.adapter.gateway.common.api.ApiPathPredicateItem;
import com.alibaba.csp.sentinel.adapter.gateway.common.api.GatewayApiDefinitionManager;
import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayFlowRule;
import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayRuleManager;
import com.alibaba.csp.sentinel.adapter.gateway.sc.callback.BlockRequestHandler;
import com.alibaba.csp.sentinel.adapter.gateway.sc.callback.GatewayCallbackManager;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import javax.annotation.PostConstruct;
import java.util.HashSet;
import java.util.Set;

@Configuration
public class SentinelConfig {

    private static final String ORDER_API_GROUP = "order-api-group";

    @PostConstruct
    public void initGatewayRules() {
        // 定义API分组
        Set<ApiDefinition> apiDefinitions = new HashSet<>();
        ApiDefinition apiDefinition = new ApiDefinition(ORDER_API_GROUP);
        Set<ApiPathPredicateItem> items = new HashSet<>();
        items.add(new ApiPathPredicateItem()
                .setPattern("/api/orders/**")
                .setMatchStrategy(SentinelGatewayConstants.URL_MATCH_STRATEGY_PREFIX));
        apiDefinition.setPredicateItems(new HashSet<>(items));
        apiDefinitions.add(apiDefinition);
        GatewayApiDefinitionManager.loadApiDefinitions(apiDefinitions);

        // 配置令牌桶限流规则
        Set<GatewayFlowRule> rules = new HashSet<>();
        GatewayFlowRule rule = new GatewayFlowRule(ORDER_API_GROUP)
                .setCount(50)
                .setIntervalSec(1)
                .setBurst(100)
                .setMaxQueueingTimeoutMs(500);
        rules.add(rule);
        GatewayRuleManager.loadRules(rules);

        // 配置限流降级响应
        GatewayCallbackManager.setBlockHandler(new BlockRequestHandler() {
            @Override
            public Mono<ServerResponse> handleRequest(ServerWebExchange exchange, Throwable t) {
                return ServerResponse.status(HttpStatus.TOO_MANY_REQUESTS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue("{\"code\": 429, \"message\": \"系统繁忙，请稍后再试\"}");
            }
        });
    }
}
