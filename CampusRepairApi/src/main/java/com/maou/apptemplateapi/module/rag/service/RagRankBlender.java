package com.maou.apptemplateapi.module.rag.service;

import com.maou.apptemplateapi.module.rag.dto.RagChunkResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RRF 融合结果与重排结果的最终排序融合。
 *
 * <p>解决「RRF 与 rerank 冲突」的三个问题：
 * <ol>
 *   <li><b>只重排前 N 条</b>：只把 RRF 结果的前 {@code rerankWindow} 条送去 cross-encoder，窗口之外保持 RRF 顺序；</li>
 *   <li><b>加权融合</b>：final = α·norm(rerank) + (1-α)·norm(rrf)，α=1 退化为纯重排，α=0 退化为纯 RRF，
 *       避免重排把「双路都命中」的共识片段压下去；</li>
 *   <li><b>量纲对齐</b>：RRF 分数（约 0.016 量级）与重排分数（0~1）各自做 min-max 归一化后再相加。</li>
 * </ol>
 * 重排未生效（未开启 / 缺 API Key / 调用失败）时，直接返回 RRF 顺序，链路不中断。
 */
@Slf4j
@Service
public class RagRankBlender {

    public List<RagChunkResponse> blend(List<RagFusionService.RagFusionResult> fused,
                                        Map<String, Double> rerankScores,
                                        double blendWeight,
                                        int rerankWindow,
                                        int limit,
                                        String scenario) {
        if (fused == null || fused.isEmpty() || limit <= 0) {
            return List.of();
        }
        boolean rerankApplied = rerankScores != null && !rerankScores.isEmpty();
        double alpha = rerankApplied ? clampWeight(blendWeight) : 0.0;
        int window = Math.max(Math.min(rerankWindow <= 0 ? fused.size() : rerankWindow, fused.size()), 1);

        List<RagFusionService.RagFusionResult> windowResults = fused.subList(0, window);
        List<RagFusionService.RagFusionResult> tailResults = window > fused.size() ? List.of() : fused.subList(window, fused.size());

        Map<String, Double> normalizedRrf = normalize(windowResults.stream()
                .map(RagFusionService.RagFusionResult::fusedScore)
                .toList());
        Map<String, Double> normalizedRerank = rerankApplied
                ? normalize(windowResults.stream()
                .map(result -> rerankScores.get(result.key()))
                .toList())
                : Map.of();

        List<ScoredResult> windowScored = new ArrayList<>();
        for (int index = 0; index < windowResults.size(); index++) {
            RagFusionService.RagFusionResult result = windowResults.get(index);
            String scoreKey = String.valueOf(index);
            double rrfScore = normalizedRrf.getOrDefault(scoreKey, 0.0);
            double rerankScore = rerankApplied ? normalizedRerank.getOrDefault(scoreKey, 0.0) : 0.0;
            double finalScore = alpha * rerankScore + (1 - alpha) * rrfScore;
            windowScored.add(new ScoredResult(result, finalScore, rrfScore, rerankScore, index));
        }
        windowScored.sort(Comparator.comparingDouble(ScoredResult::finalScore).reversed()
                .thenComparingInt(ScoredResult::fusedIndex));

        List<RagChunkResponse> ordered = new ArrayList<>(limit);
        for (ScoredResult scored : windowScored) {
            if (ordered.size() >= limit) {
                break;
            }
            ordered.add(scored.result().chunk());
        }
        for (RagFusionService.RagFusionResult tail : tailResults) {
            if (ordered.size() >= limit) {
                break;
            }
            ordered.add(tail.chunk());
        }
        log.info("rag rank blended, scenario={}, rerankApplied={}, blendWeight={}, rerankWindow={}, windowSize={}, tailSize={}, returnedCount={}, topFinalScore={}, topRrfScore={}, topRerankScore={}",
                scenario, rerankApplied, alpha, window, windowResults.size(), tailResults.size(), ordered.size(),
                windowScored.isEmpty() ? null : round(windowScored.get(0).finalScore()),
                windowScored.isEmpty() ? null : round(windowScored.get(0).rrfScore()),
                windowScored.isEmpty() ? null : round(windowScored.get(0).rerankScore()));
        return ordered;
    }

    /**
     * min-max 归一化：所有值相等时统一给 1.0（表示并列最优，而不是全部归零）。
     */
    private Map<String, Double> normalize(List<Double> values) {
        Map<String, Double> normalized = new LinkedHashMap<>();
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        for (Double value : values) {
            if (value == null) {
                continue;
            }
            min = Math.min(min, value);
            max = Math.max(max, value);
        }
        if (min == Double.MAX_VALUE) {
            return normalized;
        }
        double range = max - min;
        for (int index = 0; index < values.size(); index++) {
            Double value = values.get(index);
            double normalizedValue = value == null
                    ? 0.0
                    : (range <= 0 ? 1.0 : (value - min) / range);
            normalized.put(String.valueOf(index), round(normalizedValue));
        }
        return normalized;
    }

    private double clampWeight(double weight) {
        if (weight < 0) {
            return 0.0;
        }
        return Math.min(weight, 1.0);
    }

    private double round(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }

    private record ScoredResult(
            RagFusionService.RagFusionResult result,
            double finalScore,
            double rrfScore,
            double rerankScore,
            int fusedIndex
    ) {
    }
}
