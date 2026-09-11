package com.maou.apptemplateapi.module.rageval.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 检索指标计算：HitRate / Recall@k / Precision@k / MRR / nDCG@k。
 *
 * <p>相关性判定规则（按优先级，粒度由细到粗）：
 * <ol>
 *   <li>{@code expectedChunkIds} —— <b>切片级</b>语义标注：命中指定切片才算相关。
 *       这是唯一与检索器解耦的判据（不依赖问题与文本的字面重合），用于公平评测语义检索；</li>
 *   <li>{@code expectedDocumentIds} —— 文档级：命中指定文档的任意切片都算相关；</li>
 *   <li>{@code expectedKeywords} —— 关键词级：切片正文包含任一关键词即算相关。
 *       注意该判据与关键词检索同源，会天然偏向关键词检索，只适合快速回归。</li>
 * </ol>
 */
@Slf4j
@Service
public class RagMetricCalculator {

    public RetrievedItem item(Long chunkId, Long documentId, String content) {
        return new RetrievedItem(chunkId, documentId, content);
    }

    public RetrievedItem item(Long documentId, String content) {
        return new RetrievedItem(null, documentId, content);
    }

    public CaseMetric evaluate(List<RetrievedItem> retrieved,
                               Set<Long> expectedChunkIds,
                               Set<Long> expectedDocumentIds,
                               List<String> expectedKeywords,
                               int topK) {
        List<RetrievedItem> candidates = retrieved == null ? List.of() : retrieved.stream()
                .filter(Objects::nonNull)
                .limit(Math.max(topK, 1))
                .toList();
        int firstRelevantRank = 0;
        int relevantRetrieved = 0;
        double dcg = 0;
        for (int index = 0; index < candidates.size(); index++) {
            if (!isRelevant(candidates.get(index), expectedChunkIds, expectedDocumentIds, expectedKeywords)) {
                continue;
            }
            relevantRetrieved++;
            if (firstRelevantRank == 0) {
                firstRelevantRank = index + 1;
            }
            dcg += 1.0 / (Math.log(index + 2) / Math.log(2));
        }
        int relevantTotal = relevantTotal(expectedChunkIds, expectedDocumentIds, expectedKeywords);
        double recall = relevantTotal == 0 ? 0 : Math.min((double) relevantRetrieved / relevantTotal, 1.0);
        double precision = candidates.isEmpty() ? 0 : (double) relevantRetrieved / candidates.size();
        double reciprocalRank = firstRelevantRank == 0 ? 0 : 1.0 / firstRelevantRank;
        double idcg = idealDcg(relevantTotal, Math.max(topK, 1));
        double ndcg = idcg == 0 ? 0 : Math.min(dcg / idcg, 1.0);
        return new CaseMetric(firstRelevantRank > 0, firstRelevantRank, recall, precision, reciprocalRank, ndcg);
    }

    private boolean isRelevant(RetrievedItem item,
                               Set<Long> expectedChunkIds,
                               Set<Long> expectedDocumentIds,
                               List<String> expectedKeywords) {
        if (expectedChunkIds != null && !expectedChunkIds.isEmpty()) {
            return item.chunkId() != null && expectedChunkIds.contains(item.chunkId());
        }
        if (expectedDocumentIds != null && !expectedDocumentIds.isEmpty()) {
            return item.documentId() != null && expectedDocumentIds.contains(item.documentId());
        }
        if (expectedKeywords == null || expectedKeywords.isEmpty()) {
            return false;
        }
        String content = item.content() == null ? "" : item.content();
        return expectedKeywords.stream().filter(StringUtils::hasText).anyMatch(content::contains);
    }

    private int relevantTotal(Set<Long> expectedChunkIds, Set<Long> expectedDocumentIds, List<String> expectedKeywords) {
        if (expectedChunkIds != null && !expectedChunkIds.isEmpty()) {
            return expectedChunkIds.size();
        }
        if (expectedDocumentIds != null && !expectedDocumentIds.isEmpty()) {
            return expectedDocumentIds.size();
        }
        return expectedKeywords == null || expectedKeywords.isEmpty() ? 0 : 1;
    }

    private double idealDcg(int relevantTotal, int topK) {
        int limit = Math.min(relevantTotal, topK);
        double idcg = 0;
        for (int index = 0; index < limit; index++) {
            idcg += 1.0 / (Math.log(index + 2) / Math.log(2));
        }
        return idcg;
    }

    public record RetrievedItem(Long chunkId, Long documentId, String content) {
    }

    public record CaseMetric(
            boolean hit,
            int firstRelevantRank,
            double recall,
            double precision,
            double reciprocalRank,
            double ndcg
    ) {
    }
}
