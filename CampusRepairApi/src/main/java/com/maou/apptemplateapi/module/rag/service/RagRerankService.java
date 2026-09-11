package com.maou.apptemplateapi.module.rag.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maou.apptemplateapi.common.config.ai.AgentToolProperties;
import com.maou.apptemplateapi.module.rag.dto.RagChunkResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 交叉编码器重排（cross-encoder rerank，当前接入 Bocha gte-rerank）。
 *
 * <p>只负责「拿到重排分」：返回以片段融合键为 key 的分数表，交给 {@link RagRankBlender}
 * 与 RRF 分数加权融合。未开启、缺 API Key、调用失败时返回 skipped，由上层退化为纯 RRF 顺序。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagRerankService {

    private final AgentToolProperties agentToolProperties;
    private final ObjectMapper objectMapper;
    private final RagFusionService ragFusionService;

    public RerankOutcome rerankScores(String query, List<RagChunkResponse> candidates, String scenario) {
        if (candidates == null || candidates.isEmpty()) {
            return RerankOutcome.skipped("empty-candidates");
        }
        AgentToolProperties.Rerank rerank = agentToolProperties.getBocha().getRerank();
        if (!rerank.isEnabled()) {
            return RerankOutcome.skipped("disabled");
        }
        if (!StringUtils.hasText(rerank.getApiKey())) {
            log.warn("rag rerank skipped, scenario={}, reason=missing-api-key, candidateCount={}", scenario, candidates.size());
            return RerankOutcome.skipped("missing-api-key");
        }
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("model", rerank.getModel());
            payload.put("query", query);
            // 窗口内所有候选都要打分，截断交给 RagRankBlender 与最终 limit
            payload.put("top_n", candidates.size());
            payload.put("return_documents", false);
            payload.put("documents", candidates.stream().map(RagChunkResponse::content).toList());

            String response = RestClient.create()
                    .post()
                    .uri(rerank.getBaseUrl())
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + rerank.getApiKey())
                    .body(payload)
                    .retrieve()
                    .body(String.class);
            Map<String, Double> scores = parseScores(response, candidates);
            if (scores.isEmpty()) {
                log.warn("rag rerank returned empty scores, scenario={}, candidateCount={}", scenario, candidates.size());
                return RerankOutcome.skipped("empty-scores");
            }
            log.info("rag rerank applied, scenario={}, model={}, candidateCount={}, scoredCount={}",
                    scenario, rerank.getModel(), candidates.size(), scores.size());
            return new RerankOutcome(true, "ok", scores);
        } catch (RuntimeException exception) {
            log.error("rag rerank failed, scenario={}, baseUrl={}, model={}, candidateCount={}, fallback=rrf-order",
                    scenario, rerank.getBaseUrl(), rerank.getModel(), candidates.size(), exception);
            return RerankOutcome.skipped("call-failed");
        }
    }

    private Map<String, Double> parseScores(String response, List<RagChunkResponse> candidates) {
        try {
            JsonNode root = objectMapper.readTree(response);
            JsonNode results = root.path("results");
            if (!results.isArray()) {
                return Map.of();
            }
            Map<String, Double> scores = new LinkedHashMap<>();
            for (JsonNode item : results) {
                int index = item.path("index").asInt(-1);
                if (index < 0 || index >= candidates.size()) {
                    continue;
                }
                double score = item.path("relevance_score").asDouble(Double.NaN);
                if (Double.isNaN(score)) {
                    continue;
                }
                scores.put(ragFusionService.fusionKey(candidates.get(index)), score);
            }
            return scores;
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException exception) {
            log.error("rag rerank response parse failed, scenario=rag-rerank-parse, response={}", response, exception);
            return Map.of();
        }
    }

    public record RerankOutcome(boolean applied, String reason, Map<String, Double> scores) {

        static RerankOutcome skipped(String reason) {
            return new RerankOutcome(false, reason, Map.of());
        }
    }
}
