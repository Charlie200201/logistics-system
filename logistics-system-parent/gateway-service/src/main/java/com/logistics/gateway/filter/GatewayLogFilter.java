package com.logistics.gateway.filter;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.common.xcontent.XContentType;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Component
@RequiredArgsConstructor
public class GatewayLogFilter implements GlobalFilter, Ordered {

    private final RestHighLevelClient restHighLevelClient;

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        long startTime = System.currentTimeMillis();
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getPath();
        String method = request.getMethod() != null ? request.getMethod().name() : "UNKNOWN";
        String userId = request.getHeaders().getFirst("X-UserId");

        return chain.filter(exchange).doFinally(signalType -> {
            long duration = System.currentTimeMillis() - startTime;
            int statusCode = exchange.getResponse().getStatusCode() != null
                    ? exchange.getResponse().getStatusCode().value() : 0;

            CompletableFuture.runAsync(() -> {
                try {
                    Map<String, Object> logEntry = new HashMap<>();
                    logEntry.put("requestTime", Instant.now().toString());
                    logEntry.put("path", path);
                    logEntry.put("method", method);
                    logEntry.put("userId", userId != null ? userId : "anonymous");
                    logEntry.put("statusCode", statusCode);
                    logEntry.put("duration", duration);

                    String indexName = "gateway-logs-" + LocalDateTime.now().format(DATE_FORMAT);
                    IndexRequest indexRequest = new IndexRequest(indexName)
                            .source(logEntry, XContentType.JSON);

                    restHighLevelClient.indexAsync(indexRequest, RequestOptions.DEFAULT,
                            new org.elasticsearch.action.ActionListener<org.elasticsearch.action.index.IndexResponse>() {
                                @Override
                                public void onResponse(org.elasticsearch.action.index.IndexResponse response) { }
                                @Override
                                public void onFailure(Exception e) {
                                    log.error("ES日志写入失败", e);
                                }
                            });
                } catch (Exception e) {
                    log.error("ES日志记录异常: path={}", path, e);
                }
            });
        });
    }

    @Override
    public int getOrder() {
        return -50;
    }
}
