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
 * <p>相关性判定规则（按优先级）：
 * <ol>
 *   <li>用例给出了期望文档ID → 命中片段所属文档在期望集合内即相关；</li>
 *   <li>只给了期望关键词 → 命中片段正文包含任一关键词即相关（适合从开源数据集导入、只有答案文本的样本）。</li>
 * </ol>
 */
@Slf4j
@Service
public class RagMetricCalculator {

    public RetrievedItem item(Long documentId, String content) {
        return new RetrievedItem(documentId, content);
    }

    public CaseMetric evaluate(List<RetrievedItem> retrieved,
                               Set<Long> expectedDocumentIds,
                               List<String> expectedKeywords,
                               int topK) {
        List<RetrievedItem> candidates = retrieved == null ? List.of() : retrieved.stream()
                .filter(Objects::nonNull)
                .limit(Math.max(topK, 1))
                .toList();
        boolean relevantFlag;
        int firstRelevantRank = 0;
        int relevantRetrieved = 0;
        double dcg = 0;
        for (int index = 0; index < candidates.size(); index++) {
            relevantFlag = isRelevant(candidates.get(index), expectedDocumentIds, expectedKeywords);
            if (!relevantFlag) {
                continue;
            }
            relevantRetrieved++;
            if (firstRelevantRank == 0) {
                firstRelevantRank = index + 1;
            }
            dcg += 1.0 / (Math.log(index + 2) / Math.log(2));
        }
        int relevantTotal = expectedDocumentIds == null || expectedDocumentIds.isEmpty()
                ? (expectedKeywords == null || expectedKeywords.isEmpty() ? 0 : 1)
                : expectedDocumentIds.size();
        double recall = relevantTotal == 0 ? 0 : Math.min((double) relevantRetrieved / relevantTotal, 1.0);
        double precision = candidates.isEmpty() ? 0 : (double) relevantRetrieved / candidates.size();
        double reciprocalRank = firstRelevantRank == 0 ? 0 : 1.0 / firstRelevantRank;
        double idcg = idealDcg(relevantTotal, Math.max(topK, 1));
        double ndcg = idcg == 0 ? 0 : Math.min(dcg / idcg, 1.0);
        return new CaseMetric(firstRelevantRank > 0, firstRelevantRank, recall, precision, reciprocalRank, ndcg);
    }

    private boolean isRelevant(RetrievedItem item, Set<Long> expectedDocumentIds, List<String> expectedKeywords) {
        if (expectedDocumentIds != null && !expectedDocumentIds.isEmpty()) {
            return item.documentId() != null && expectedDocumentIds.contains(item.documentId());
        }
        if (expectedKeywords == null || expectedKeywords.isEmpty()) {
            return false;
        }
        String content = item.content() == null ? "" : item.content();
        return expectedKeywords.stream().filter(StringUtils::hasText).anyMatch(content::contains);
    }

    private double idealDcg(int relevantTotal, int topK) {
        int limit = Math.min(relevantTotal, topK);
        double idcg = 0;
        for (int index = 0; index < limit; index++) {
            idcg += 1.0 / (Math.log(index + 2) / Math.log(2));
        }
        return idcg;
    }

    public record RetrievedItem(Long documentId, String content) {
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
