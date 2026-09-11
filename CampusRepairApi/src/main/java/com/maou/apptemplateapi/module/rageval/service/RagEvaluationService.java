package com.maou.apptemplateapi.module.rageval.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maou.apptemplateapi.common.enums.UserRole;
import com.maou.apptemplateapi.common.exception.BusinessException;
import com.maou.apptemplateapi.common.exception.ErrorCode;
import com.maou.apptemplateapi.common.result.PageResult;
import com.maou.apptemplateapi.common.security.CurrentUser;
import com.maou.apptemplateapi.common.security.CurrentUserProvider;
import com.maou.apptemplateapi.module.rag.dto.RagChunkResponse;
import com.maou.apptemplateapi.module.rag.dto.RagSearchResult;
import com.maou.apptemplateapi.module.rag.service.RagKnowledgeService;
import com.maou.apptemplateapi.module.rageval.dto.RagEvalCaseResultResponse;
import com.maou.apptemplateapi.module.rageval.dto.RagEvalMetricsResponse;
import com.maou.apptemplateapi.module.rageval.dto.RagEvalRunDetailResponse;
import com.maou.apptemplateapi.module.rageval.dto.RagEvalRunRequest;
import com.maou.apptemplateapi.module.rageval.dto.RagEvalRunResponse;
import com.maou.apptemplateapi.module.rageval.entity.RagEvalCase;
import com.maou.apptemplateapi.module.rageval.entity.RagEvalCaseResult;
import com.maou.apptemplateapi.module.rageval.entity.RagEvalRun;
import com.maou.apptemplateapi.module.rageval.mapper.RagEvalCaseResultMapper;
import com.maou.apptemplateapi.module.rageval.mapper.RagEvalRunMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * RAG 自动化评测：对评测集逐条执行真实检索链路，计算检索指标并落库，用于衡量 RAG 好坏与回归对比。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagEvaluationService {

    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_PARTIAL = "PARTIAL";

    private static final int DEFAULT_TOP_K = 5;
    private static final int MAX_TOP_K = 20;

    private final RagEvalRunMapper runMapper;
    private final RagEvalCaseResultMapper caseResultMapper;
    private final RagEvalDatasetService datasetService;
    private final RagKnowledgeService ragKnowledgeService;
    private final RagMetricCalculator metricCalculator;
    private final ObjectMapper objectMapper;

    @Transactional
    public RagEvalRunDetailResponse run(RagEvalRunRequest request) {
        CurrentUser admin = requireAdmin("admin-rag-eval-run");
        String datasetName = request == null || !StringUtils.hasText(request.datasetName())
                ? RagEvalDatasetService.DEFAULT_DATASET
                : request.datasetName().trim();
        int topK = clampTopK(request == null ? null : request.topK());

        List<RagEvalCase> cases = datasetService.loadCases(datasetName);
        if (cases.isEmpty()) {
            log.warn("rag eval dataset empty, scenario=admin-rag-eval-run, datasetName={}, adminId={}",
                    datasetName, admin.getId());
            throw new BusinessException(ErrorCode.RAG_EVAL_DATASET_EMPTY);
        }

        RagEvalRun run = new RagEvalRun();
        run.setDatasetName(datasetName);
        run.setTopK(topK);
        run.setCaseCount(cases.size());
        run.setStatus(STATUS_RUNNING);
        run.setTriggeredBy(admin.getId());
        run.setCreatedAt(LocalDateTime.now());
        runMapper.insert(run);

        long totalLatencyMs = 0;
        int hits = 0;
        int answerableCount = 0;
        int unanswerableCount = 0;
        int unanswerableHits = 0;
        int failedCount = 0;
        double recallSum = 0;
        double precisionSum = 0;
        double reciprocalRankSum = 0;
        double ndcgSum = 0;
        List<RagEvalCaseResult> results = new ArrayList<>();

        for (RagEvalCase evalCase : cases) {
            boolean answerable = evalCase.getAnswerable() == null || evalCase.getAnswerable() != 0;
            if (answerable) {
                answerableCount++;
            } else {
                unanswerableCount++;
            }
            long startedAt = System.nanoTime();
            RagSearchResult searchResult;
            try {
                searchResult = ragKnowledgeService.searchDetailed(evalCase.getCategoryId(), evalCase.getQuestion(), topK);
            } catch (RuntimeException exception) {
                failedCount++;
                log.error("rag eval case failed, scenario=admin-rag-eval-run, runId={}, caseId={}, question={}",
                        run.getId(), evalCase.getId(), evalCase.getQuestion(), exception);
                searchResult = RagSearchResult.empty();
            }
            long latencyMs = Math.max((System.nanoTime() - startedAt) / 1_000_000, 0);
            totalLatencyMs += latencyMs;

            Set<Long> expectedDocIds = new LinkedHashSet<>(datasetService.readLongList(evalCase.getExpectedDocIds()));
            List<String> expectedKeywords = datasetService.readStringList(evalCase.getExpectedKeywords());
            List<RagMetricCalculator.RetrievedItem> retrievedItems = searchResult.chunks().stream()
                    .map(chunk -> metricCalculator.item(chunk.documentId(), chunk.content()))
                    .toList();
            RagMetricCalculator.CaseMetric metric = metricCalculator.evaluate(retrievedItems, expectedDocIds, expectedKeywords, topK);

            boolean hit = answerable ? metric.hit() : !metric.hit();
            if (hit) {
                hits++;
                if (!answerable) {
                    unanswerableHits++;
                }
            }
            if (answerable) {
                recallSum += metric.recall();
                precisionSum += metric.precision();
                reciprocalRankSum += metric.reciprocalRank();
                ndcgSum += metric.ndcg();
            }

            RagEvalCaseResult caseResult = new RagEvalCaseResult();
            caseResult.setRunId(run.getId());
            caseResult.setCaseId(evalCase.getId());
            caseResult.setQuestion(evalCase.getQuestion());
            caseResult.setHit(hit ? 1 : 0);
            caseResult.setFirstRelevantRank(metric.firstRelevantRank());
            caseResult.setRecall(scale(metric.recall()));
            caseResult.setPrecisionScore(scale(metric.precision()));
            caseResult.setReciprocalRank(scale(metric.reciprocalRank()));
            caseResult.setNdcg(scale(metric.ndcg()));
            caseResult.setLatencyMs(latencyMs);
            caseResult.setRetrievedChunks(writeRetrieved(searchResult));
            caseResult.setCreatedAt(LocalDateTime.now());
            caseResultMapper.insert(caseResult);
            results.add(caseResult);
        }

        run.setAnswerableCaseCount(answerableCount);
        run.setUnanswerableCaseCount(unanswerableCount);
        run.setHitRate(scale((double) hits / cases.size()));
        run.setRecallAtK(scale(answerableCount == 0 ? 0 : recallSum / answerableCount));
        run.setPrecisionAtK(scale(answerableCount == 0 ? 0 : precisionSum / answerableCount));
        run.setMrr(scale(answerableCount == 0 ? 0 : reciprocalRankSum / answerableCount));
        run.setNdcgAtK(scale(answerableCount == 0 ? 0 : ndcgSum / answerableCount));
        run.setRefusalAccuracy(scale(unanswerableCount == 0 ? 0 : (double) unanswerableHits / unanswerableCount));
        run.setAvgLatencyMs(totalLatencyMs / cases.size());
        run.setStatus(failedCount == 0 ? STATUS_SUCCESS : STATUS_PARTIAL);
        run.setFinishedAt(LocalDateTime.now());
        runMapper.updateById(run);

        log.info("rag eval run finished, scenario=admin-rag-eval-run, runId={}, datasetName={}, topK={}, caseCount={}, hitRate={}, recall={}, precision={}, mrr={}, ndcg={}, refusalAccuracy={}, avgLatencyMs={}, failedCount={}, status={}",
                run.getId(), datasetName, topK, cases.size(), run.getHitRate(), run.getRecallAtK(), run.getPrecisionAtK(),
                run.getMrr(), run.getNdcgAtK(), run.getRefusalAccuracy(), run.getAvgLatencyMs(), failedCount, run.getStatus());
        return toDetail(run, results);
    }

    public PageResult<RagEvalRunResponse> listRuns(long page, long size) {
        requireAdmin("admin-rag-eval-list-runs");
        Page<RagEvalRun> result = runMapper.selectPage(new Page<>(Math.max(page, 1), Math.min(Math.max(size, 1), 100)),
                new LambdaQueryWrapper<RagEvalRun>().orderByDesc(RagEvalRun::getId));
        return PageResult.of(result.getRecords().stream().map(this::toRunResponse).toList(),
                result.getCurrent(), result.getSize(), result.getTotal());
    }

    public RagEvalRunDetailResponse runDetail(Long runId) {
        requireAdmin("admin-rag-eval-run-detail");
        RagEvalRun run = runMapper.selectById(runId);
        if (run == null) {
            log.warn("rag eval run not found, scenario=admin-rag-eval-run-detail, runId={}", runId);
            throw new BusinessException(ErrorCode.RAG_EVAL_RUN_NOT_FOUND);
        }
        List<RagEvalCaseResult> results = caseResultMapper.selectList(new LambdaQueryWrapper<RagEvalCaseResult>()
                .eq(RagEvalCaseResult::getRunId, runId)
                .orderByAsc(RagEvalCaseResult::getId));
        return toDetail(run, results);
    }

    private RagEvalRunDetailResponse toDetail(RagEvalRun run, List<RagEvalCaseResult> results) {
        return new RagEvalRunDetailResponse(toRunResponse(run), results.stream().map(this::toCaseResultResponse).toList());
    }

    private RagEvalRunResponse toRunResponse(RagEvalRun run) {
        RagEvalMetricsResponse metrics = new RagEvalMetricsResponse(
                run.getCaseCount() == null ? 0 : run.getCaseCount(),
                run.getAnswerableCaseCount() == null ? 0 : run.getAnswerableCaseCount(),
                run.getUnanswerableCaseCount() == null ? 0 : run.getUnanswerableCaseCount(),
                run.getHitRate(), run.getRecallAtK(), run.getPrecisionAtK(), run.getMrr(), run.getNdcgAtK(),
                run.getRefusalAccuracy(), run.getAvgLatencyMs() == null ? 0 : run.getAvgLatencyMs());
        return new RagEvalRunResponse(run.getId(), run.getDatasetName(), run.getTopK(), run.getStatus(),
                run.getErrorMessage(), metrics, run.getTriggeredBy(), run.getCreatedAt(), run.getFinishedAt());
    }

    private RagEvalCaseResultResponse toCaseResultResponse(RagEvalCaseResult result) {
        return new RagEvalCaseResultResponse(result.getCaseId(), result.getQuestion(),
                result.getHit() != null && result.getHit() == 1, result.getFirstRelevantRank(),
                result.getRecall(), result.getPrecisionScore(), result.getReciprocalRank(), result.getNdcg(),
                result.getLatencyMs(), readRetrieved(result.getRetrievedChunks()));
    }

    private String writeRetrieved(RagSearchResult searchResult) {
        List<RagEvalCaseResultResponse.RetrievedChunkResponse> chunks = searchResult.chunks().stream()
                .map(chunk -> new RagEvalCaseResultResponse.RetrievedChunkResponse(chunk.id(), chunk.documentId(),
                        chunk.title(), chunk.score(), searchResult.topSource()))
                .toList();
        try {
            return objectMapper.writeValueAsString(chunks);
        } catch (JsonProcessingException exception) {
            log.error("rag eval retrieved chunks write failed, scenario=rag-eval-write-retrieved, topSource={}",
                    searchResult.topSource(), exception);
            return "[]";
        }
    }

    private List<RagEvalCaseResultResponse.RetrievedChunkResponse> readRetrieved(String rawJson) {
        if (!StringUtils.hasText(rawJson)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(rawJson, new TypeReference<List<RagEvalCaseResultResponse.RetrievedChunkResponse>>() {
            });
        } catch (JsonProcessingException exception) {
            log.error("rag eval retrieved chunks read failed, scenario=rag-eval-read-retrieved", exception);
            return List.of();
        }
    }

    private int clampTopK(Integer topK) {
        if (topK == null || topK <= 0) {
            return DEFAULT_TOP_K;
        }
        return Math.min(topK, MAX_TOP_K);
    }

    private BigDecimal scale(double value) {
        return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP);
    }

    private CurrentUser requireAdmin(String scenario) {
        CurrentUser currentUser = CurrentUserProvider.require();
        if (currentUser.getRole() != UserRole.ADMIN) {
            log.warn("rag eval role denied, scenario={}, userId={}, roleCode={}",
                    scenario, currentUser.getId(), currentUser.getRoleCode());
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        return currentUser;
    }
}
