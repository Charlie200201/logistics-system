# 11 — Elasticsearch 日志收集

## 是什么

Elasticsearch（简称 ES）是一个**分布式搜索引擎**，专门用于全文搜索和数据分析。在本项目中，ES 用来存储和检索网关请求日志。

## 为什么需要

### 为什么不用 MySQL 存日志

```
MySQL:
  - 写入频繁 → 锁竞争 → 拖慢业务数据库
  - LIKE 查询 → 全表扫描 → 极慢
  - 日志数据不需要事务 → 浪费 MySQL 的事务能力

Elasticsearch:
  - 写入快 → 近实时，秒级可见
  - 搜索快 → 倒排索引，毫秒级
  - 按时间分索引 → 自动归档，老索引可直接删除
  - 聚合分析 → 天生适合统计日志
```

### MySQL LIKE vs ES 全文搜索

```
MySQL 搜索日志：
  SELECT * FROM logs WHERE content LIKE '%orders%';
  → 全表扫描 100 万条 → 5 秒

ES 搜索日志：
  GET /gateway-logs-*/_search?q=orders
  → 倒排索引 → 10 毫秒
```

## 核心概念

### MySQL 概念 → ES 概念

| MySQL | Elasticsearch | 说明 |
|-------|---------------|------|
| Database | Index（索引） | 本项目按天建索引：`gateway-logs-2026-07-14` |
| Table | Type（ES 7 已废弃） | 一个索引只有一种文档类型 |
| Row | Document（文档） | 一条日志 = 一个 JSON 文档 |
| Column | Field（字段） | 文档中的属性 |
| SELECT | Search API | 搜索文档 |

### 倒排索引原理

```
传统（正排）索引:
  文档1 → "订单创建成功，用户1购买商品2"
  文档2 → "用户3查询订单"
  搜索"订单" → 遍历每个文档 → 看是否包含"订单"

倒排索引:
  "订单" → [文档1, 文档2]
  "用户" → [文档1, 文档2]
  "商品" → [文档1]
  搜索"订单" → 直接查倒排表 → 立即得到结果
```

## 项目中的代码

### 1. ES 客户端配置

**文件位置**: `gateway-service/src/main/java/com/logistics/gateway/config/ElasticsearchConfig.java`

```java
@Configuration
public class ElasticsearchConfig {

    @Value("${elasticsearch.host:localhost}")
    private String host;

    @Value("${elasticsearch.port:9200}")
    private int port;

    @Bean
    public RestHighLevelClient restHighLevelClient() {
        return new RestHighLevelClient(
                RestClient.builder(new HttpHost(host, port, "http"))
        );
    }
}
```

### 2. 网关日志过滤器 — 写入 ES

**文件位置**: `gateway-service/src/main/java/com/logistics/gateway/filter/GatewayLogFilter.java`

```java
@Component
@RequiredArgsConstructor
public class GatewayLogFilter implements GlobalFilter, Ordered {

    private final RestHighLevelClient esClient;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        long startTime = System.currentTimeMillis();
        String path = exchange.getRequest().getURI().getPath();
        String method = exchange.getRequest().getMethod().name();
        String userId = exchange.getRequest().getHeaders().getFirst("X-UserId");

        // 等请求处理完后再记录日志
        return chain.filter(exchange).doFinally(signalType -> {
            long duration = System.currentTimeMillis() - startTime;
            int statusCode = exchange.getResponse().getStatusCode().value();

            // 异步写入 ES，不阻塞请求返回
            CompletableFuture.runAsync(() -> {
                try {
                    // 构建日志文档
                    Map<String, Object> logEntry = new HashMap<>();
                    logEntry.put("requestTime", LocalDateTime.now().toString());
                    logEntry.put("path", path);
                    logEntry.put("method", method);
                    logEntry.put("userId", userId != null ? userId : "anonymous");
                    logEntry.put("statusCode", statusCode);
                    logEntry.put("duration", duration);

                    // 按天分索引: gateway-logs-2026-07-14
                    String indexName = "gateway-logs-" + LocalDate.now().format(fmt);
                    IndexRequest request = new IndexRequest(indexName)
                            .source(logEntry, XContentType.JSON);

                    // 异步索引文档
                    esClient.indexAsync(request, RequestOptions.DEFAULT, listener);
                } catch (Exception e) {
                    log.error("ES日志记录异常: path={}", path, e);
                }
            });
        });
    }

    @Override
    public int getOrder() {
        return -50;  // 在 JWT 过滤器之后执行，此时已有 userId
    }
}
```

### 3. 日志查询接口

**文件位置**: `gateway-service/src/main/java/com/logistics/gateway/controller/LogQueryController.java`

```java
@RestController
@RequestMapping("/api/logs")
@RequiredArgsConstructor
public class LogQueryController {

    private final RestHighLevelClient esClient;

    @GetMapping("/search")
    public Map<String, Object> search(
            @RequestParam(required = false) String keyword,    // 搜索关键词
            @RequestParam(required = false) String startTime,  // 开始时间
            @RequestParam(required = false) String endTime,    // 结束时间
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize) {

        // ① 确定要搜索哪些索引（按天分索引，需要根据时间范围计算）
        List<String> indices = buildIndices(startTime, endTime);

        // ② 构建查询条件
        BoolQueryBuilder boolQuery = QueryBuilders.boolQuery();
        if (keyword != null && !keyword.isEmpty()) {
            boolQuery.must(QueryBuilders.multiMatchQuery(keyword, "path", "userId"));
        }
        if (startTime != null && endTime != null) {
            boolQuery.must(QueryBuilders.rangeQuery("requestTime")
                    .gte(startTime).lte(endTime));
        }

        // ③ 执行搜索
        SearchRequest searchRequest = new SearchRequest(indices.toArray(new String[0]));
        searchRequest.source(new SearchSourceBuilder()
                .query(boolQuery)
                .from((pageNum - 1) * pageSize)
                .size(pageSize)
                .sort("requestTime", SortOrder.DESC));

        SearchResponse response = esClient.search(searchRequest, RequestOptions.DEFAULT);

        // ④ 解析结果
        List<Map<String, Object>> results = new ArrayList<>();
        for (SearchHit hit : response.getHits().getHits()) {
            results.add(hit.getSourceAsMap());
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("code", 200);
        result.put("total", response.getHits().getTotalHits().value);
        result.put("data", results);
        return result;
    }
}
```

### 4. application.yml 配置

```yaml
elasticsearch:
  host: localhost
  port: 9200
  scheme: http
```

### 5. Docker 中的 ES

```bash
docker ps | grep elasticsearch
# elasticsearch   elasticsearch:7.12.1   9200, 9300
```

## 验证方法

### 1. 确认 ES 运行

```bash
curl http://localhost:9200
# 应返回 ES 集群信息
```

### 2. 查询索引

```bash
# 发一些请求后，查看索引是否自动创建
curl http://localhost:9200/_cat/indices/gateway-logs-*

# 查询某个索引的文档
curl http://localhost:9200/gateway-logs-2026-07-14/_search?pretty
```

### 3. 使用 API 查询

```bash
# 搜索包含 "orders" 的日志
curl "http://localhost:8085/api/logs/search?keyword=orders"

# 搜索指定时间段
curl "http://localhost:8085/api/logs/search?keyword=500&startTime=2026-07-14T00:00:00&endTime=2026-07-14T23:59:59"
```

## 常见问题

**Q: ES 没有日志？**
A: 确认 ES 容器在运行 `curl localhost:9200`。确认 Gateway 的 `GatewayLogFilter` 被正确加载。

**Q: 查询返回空？**
A: 要先发一些 API 请求才会产生日志。检查索引名日期格式是否正确。

**Q: 按天分索引有什么好处？**
A: 方便删除过期数据（直接删索引，不用逐条删文档），每个索引的文档数量可控。
