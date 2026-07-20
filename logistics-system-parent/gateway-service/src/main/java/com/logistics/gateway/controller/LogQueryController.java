package com.logistics.gateway.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Slf4j
@RestController
@RequestMapping("/api/logs")
@RequiredArgsConstructor
public class LogQueryController {

    private final RestHighLevelClient restHighLevelClient;

    @GetMapping("/search")
    public Map<String, Object> search(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String startTime,
            @RequestParam(required = false) String endTime,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize) {

        try {
            // 确定要搜索的索引（按天分索引）
            List<String> indices = buildIndices(startTime, endTime);
            if (indices.isEmpty()) {
                Map<String, Object> empty = new LinkedHashMap<>();
                empty.put("code", 200);
                empty.put("message", "success");
                empty.put("data", Collections.emptyList());
                empty.put("total", 0);
                return empty;
            }

            SearchRequest searchRequest = new SearchRequest(indices.toArray(new String[0]));
            SearchSourceBuilder sourceBuilder = new SearchSourceBuilder();
            BoolQueryBuilder boolQuery = QueryBuilders.boolQuery();

            // 关键词搜索（匹配path）
            if (keyword != null && !keyword.isEmpty()) {
                boolQuery.must(QueryBuilders.multiMatchQuery(keyword, "path", "userId"));
            }

            // 时间范围
            if (startTime != null && endTime != null) {
                boolQuery.must(QueryBuilders.rangeQuery("requestTime")
                        .gte(startTime).lte(endTime));
            }

            sourceBuilder.query(boolQuery);
            sourceBuilder.from((pageNum - 1) * pageSize);
            sourceBuilder.size(pageSize);
            sourceBuilder.sort("requestTime", org.elasticsearch.search.sort.SortOrder.DESC);
            searchRequest.source(sourceBuilder);

            SearchResponse response = restHighLevelClient.search(searchRequest, RequestOptions.DEFAULT);
            List<Map<String, Object>> results = new ArrayList<>();
            for (SearchHit hit : response.getHits().getHits()) {
                results.add(hit.getSourceAsMap());
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("code", 200);
            result.put("message", "success");
            result.put("data", results);
            result.put("total", response.getHits().getTotalHits().value);
            return result;
        } catch (Exception e) {
            log.error("ES日志查询失败", e);
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("code", 500);
            error.put("message", "日志查询失败: " + e.getMessage());
            return error;
        }
    }

    private List<String> buildIndices(String startTime, String endTime) {
        List<String> indices = new ArrayList<>();
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        LocalDate start, end;

        if (startTime != null && endTime != null) {
            start = LocalDate.parse(startTime.substring(0, 10), fmt);
            end = LocalDate.parse(endTime.substring(0, 10), fmt);
        } else {
            // 默认查询最近7天
            end = LocalDate.now();
            start = end.minusDays(7);
        }

        LocalDate current = start;
        while (!current.isAfter(end)) {
            indices.add("gateway-logs-" + current.format(fmt));
            current = current.plusDays(1);
        }
        return indices;
    }
}
