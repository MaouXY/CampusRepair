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

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class RagRerankService {

    private final AgentToolProperties agentToolProperties;
    private final ObjectMapper objectMapper;

    public List<RagChunkResponse> rerank(String query, List<RagChunkResponse> candidates, String scenario) {
        AgentToolProperties.Rerank rerank = agentToolProperties.getBocha().getRerank();
        int topN = rerank.getTopN() == null ? 6 : rerank.getTopN();
        if (!rerank.isEnabled()) {
            return candidates.stream().limit(topN).toList();
        }
        if (!StringUtils.hasText(rerank.getApiKey()) || candidates.isEmpty()) {
            log.warn("rag rerank skipped, scenario={}, reason=missing-api-key-or-candidates, enabled={}, candidateCount={}",
                    scenario, rerank.isEnabled(), candidates.size());
            return candidates.stream().limit(topN).toList();
        }
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("model", rerank.getModel());
            payload.put("query", query);
            payload.put("top_n", topN);
            payload.put("return_documents", rerank.getReturnDocuments());
            payload.put("documents", candidates.stream().map(RagChunkResponse::content).toList());

            String response = RestClient.create()
                    .post()
                    .uri(rerank.getBaseUrl())
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + rerank.getApiKey())
                    .body(payload)
                    .retrieve()
                    .body(String.class);
            return applyRerankResponse(response, candidates, topN);
        } catch (RuntimeException exception) {
            log.error("rag rerank failed, scenario={}, baseUrl={}, model={}, candidateCount={}",
                    scenario, rerank.getBaseUrl(), rerank.getModel(), candidates.size(), exception);
            return candidates.stream().limit(topN).toList();
        }
    }

    private List<RagChunkResponse> applyRerankResponse(String response, List<RagChunkResponse> candidates, int topN) {
        try {
            JsonNode root = objectMapper.readTree(response);
            JsonNode results = root.path("results");
            if (!results.isArray()) {
                return candidates.stream().limit(topN).toList();
            }
            java.util.ArrayList<RagChunkResponse> reranked = new java.util.ArrayList<>();
            for (JsonNode item : results) {
                int index = item.path("index").asInt(-1);
                if (index >= 0 && index < candidates.size()) {
                    RagChunkResponse old = candidates.get(index);
                    double score = item.path("relevance_score").asDouble(old.score());
                    reranked.add(new RagChunkResponse(old.id(), old.documentId(), old.title(), old.content(), score));
                }
            }
            if (reranked.isEmpty()) {
                return candidates.stream().limit(topN).toList();
            }
            return reranked.stream()
                    .sorted(Comparator.comparingDouble(RagChunkResponse::score).reversed())
                    .limit(topN)
                    .toList();
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException exception) {
            log.error("rag rerank response parse failed, scenario=rag-rerank-parse, response={}", response, exception);
            return candidates.stream().limit(topN).toList();
        }
    }
}
