package com.maou.apptemplateapi.module.rag.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.maou.apptemplateapi.module.rag.dto.RagChunkResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 重排响应解析：不同厂商的结构差异（Bocha 在 data.results、部分厂商在顶层 results）。
 *
 * <p>这个 bug 曾经真实存在：只读顶层 results 时 Bocha 的分数解析不到，
 * 检索会静默退回纯 RRF（日志显示 rerankApplied=false，reason=empty-scores）。
 */
class RagRerankServiceTest {

    private final RagRerankService service = new RagRerankService(
            new com.maou.apptemplateapi.common.config.ai.AgentToolProperties(),
            new ObjectMapper(),
            new RagFusionService());

    private final List<RagChunkResponse> candidates = List.of(
            new RagChunkResponse(1001L, 10L, "文档A", "水龙头渗水时先关闭角阀", 0.9),
            new RagChunkResponse(1002L, 10L, "文档A", "空调滤网积尘导致风量下降", 0.8));

    @Test
    void shouldParseBochaNestedResponse() {
        String response = """
                {"code":200,"log_id":"abc","data":{"model":"gte-rerank","results":[
                  {"index":0,"relevance_score":0.15882435831940483},
                  {"index":1,"relevance_score":0.006006913310615648}]}}
                """;

        Map<String, Double> scores = service.parseScores(response, candidates);

        assertThat(scores).hasSize(2);
        assertThat(scores.get("chunk:1001")).isCloseTo(0.1588, org.assertj.core.data.Offset.offset(1e-4));
        assertThat(scores.get("chunk:1002")).isCloseTo(0.0060, org.assertj.core.data.Offset.offset(1e-4));
    }

    @Test
    void shouldParseTopLevelResultsResponse() {
        String response = """
                {"results":[{"index":1,"relevance_score":0.91},{"index":0,"relevance_score":0.12}]}
                """;

        Map<String, Double> scores = service.parseScores(response, candidates);

        assertThat(scores.get("chunk:1001")).isEqualTo(0.12);
        assertThat(scores.get("chunk:1002")).isEqualTo(0.91);
    }

    @Test
    void shouldIgnoreOutOfRangeIndexAndMissingScore() {
        String response = """
                {"data":{"results":[{"index":5,"relevance_score":0.9},{"index":1},{"index":0,"relevance_score":0.5}]}}
                """;

        Map<String, Double> scores = service.parseScores(response, candidates);

        assertThat(scores).containsOnlyKeys("chunk:1001");
        assertThat(scores.get("chunk:1001")).isEqualTo(0.5);
    }

    @Test
    void shouldReturnEmptyForMalformedOrUnexpectedResponse() {
        assertThat(service.parseScores("not-a-json", candidates)).isEmpty();
        assertThat(service.parseScores("{\"code\":500,\"msg\":\"error\"}", candidates)).isEmpty();
        assertThat(service.parseScores("", candidates)).isEmpty();
    }

    @Test
    void shouldReturnEmptyWhenCandidatesEmpty() {
        assertThat(service.parseScores("{\"data\":{\"results\":[{\"index\":0,\"relevance_score\":1.0}]}}", List.of())).isEmpty();
    }
}
