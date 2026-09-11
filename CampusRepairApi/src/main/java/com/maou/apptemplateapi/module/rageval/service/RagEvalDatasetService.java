package com.maou.apptemplateapi.module.rageval.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maou.apptemplateapi.common.enums.UserRole;
import com.maou.apptemplateapi.common.exception.BusinessException;
import com.maou.apptemplateapi.common.exception.ErrorCode;
import com.maou.apptemplateapi.common.result.PageResult;
import com.maou.apptemplateapi.common.security.CurrentUser;
import com.maou.apptemplateapi.common.security.CurrentUserProvider;
import com.maou.apptemplateapi.module.rageval.dto.RagEvalCaseRequest;
import com.maou.apptemplateapi.module.rageval.dto.RagEvalCaseResponse;
import com.maou.apptemplateapi.module.rageval.dto.RagEvalDatasetResponse;
import com.maou.apptemplateapi.module.rageval.dto.RagEvalImportRequest;
import com.maou.apptemplateapi.module.rageval.entity.RagEvalCase;
import com.maou.apptemplateapi.module.rageval.mapper.RagEvalCaseMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class RagEvalDatasetService {

    public static final String DEFAULT_DATASET = "campus-repair-builtin";
    public static final String DEFAULT_SOURCE = "builtin";

    private static final int MAX_IMPORT_CASES = 500;

    private final RagEvalCaseMapper caseMapper;
    private final ObjectMapper objectMapper;

    public PageResult<RagEvalCaseResponse> listCases(long page, long size, String datasetName) {
        requireAdmin("admin-rag-eval-list-cases");
        LambdaQueryWrapper<RagEvalCase> wrapper = new LambdaQueryWrapper<RagEvalCase>()
                .eq(RagEvalCase::getDeleted, 0)
                .eq(StringUtils.hasText(datasetName), RagEvalCase::getDatasetName, datasetName)
                .orderByAsc(RagEvalCase::getId);
        Page<RagEvalCase> result = caseMapper.selectPage(new Page<>(Math.max(page, 1), Math.min(Math.max(size, 1), 200)), wrapper);
        return PageResult.of(result.getRecords().stream().map(this::toResponse).toList(),
                result.getCurrent(), result.getSize(), result.getTotal());
    }

    public List<RagEvalDatasetResponse> listDatasets() {
        requireAdmin("admin-rag-eval-list-datasets");
        List<RagEvalCase> cases = loadAllCases();
        Map<String, List<RagEvalCase>> grouped = cases.stream()
                .collect(java.util.stream.Collectors.groupingBy(RagEvalCase::getDatasetName,
                        java.util.LinkedHashMap::new, java.util.stream.Collectors.toList()));
        return grouped.entrySet().stream()
                .map(entry -> {
                    List<RagEvalCase> datasetCases = entry.getValue();
                    int unanswerable = (int) datasetCases.stream().filter(evalCase -> isUnanswerable(evalCase.getAnswerable())).count();
                    return new RagEvalDatasetResponse(entry.getKey(), datasetCases.size(),
                            datasetCases.size() - unanswerable, unanswerable);
                })
                .toList();
    }

    @Transactional
    public RagEvalCaseResponse createCase(RagEvalCaseRequest request) {
        requireAdmin("admin-rag-eval-create-case");
        if (request == null || !StringUtils.hasText(request.question())) {
            log.warn("rag eval case invalid, scenario=admin-rag-eval-create-case, reason=blank-question");
            throw new BusinessException(ErrorCode.RAG_EVAL_CASE_INVALID);
        }
        RagEvalCase evalCase = new RagEvalCase();
        evalCase.setDatasetName(StringUtils.hasText(request.datasetName()) ? request.datasetName().trim() : DEFAULT_DATASET);
        evalCase.setQuestion(request.question().trim());
        evalCase.setExpectedChunkIds(writeJson(request.expectedChunkIds() == null ? List.of() : request.expectedChunkIds()));
        evalCase.setExpectedDocIds(writeJson(request.expectedDocIds() == null ? List.of() : request.expectedDocIds()));
        evalCase.setExpectedKeywords(writeJson(request.expectedKeywords() == null ? List.of() : request.expectedKeywords()));
        evalCase.setAnswerable(request.answerable() == null ? 1 : normalizeAnswerable(request.answerable()));
        evalCase.setTaskType(StringUtils.hasText(request.taskType()) ? request.taskType() : "factual");
        evalCase.setCategoryId(request.categoryId());
        evalCase.setSource(StringUtils.hasText(request.source()) ? request.source() : "manual");
        evalCase.setDeleted(0);
        caseMapper.insert(evalCase);
        log.info("rag eval case created, scenario=admin-rag-eval-create-case, caseId={}, datasetName={}, question={}",
                evalCase.getId(), evalCase.getDatasetName(), evalCase.getQuestion());
        return toResponse(evalCase);
    }

    @Transactional
    public int importCases(RagEvalImportRequest request) {
        requireAdmin("admin-rag-eval-import");
        if (request == null || !StringUtils.hasText(request.content())) {
            throw new BusinessException(ErrorCode.RAG_EVAL_IMPORT_INVALID);
        }
        List<JsonNode> nodes = parseNodes(request.content());
        if (nodes.isEmpty()) {
            throw new BusinessException(ErrorCode.RAG_EVAL_IMPORT_INVALID);
        }
        int imported = 0;
        for (JsonNode node : nodes) {
            if (imported >= MAX_IMPORT_CASES) {
                log.warn("rag eval import truncated, scenario=admin-rag-eval-import, limit={}", MAX_IMPORT_CASES);
                break;
            }
            String question = firstText(node, "question", "query", "input");
            if (!StringUtils.hasText(question)) {
                log.warn("rag eval import row skipped, scenario=admin-rag-eval-import, reason=blank-question, row={}", node);
                continue;
            }
            RagEvalCase evalCase = new RagEvalCase();
            evalCase.setDatasetName(firstTextOrDefault(node, request.datasetName(), DEFAULT_DATASET,
                    "datasetName", "dataset", "dataset_name"));
            evalCase.setQuestion(question.trim());
            evalCase.setExpectedChunkIds(writeJson(longList(node, "expectedChunkIds", "expected_chunk_ids",
                    "gold_chunk_ids", "chunk_ids", "goldChunkIds")));
            evalCase.setExpectedDocIds(writeJson(longList(node, "expectedDocIds", "gold_doc_ids",
                    "relevant_doc_ids", "doc_ids", "goldDocIds")));
            evalCase.setExpectedKeywords(writeJson(stringList(node, "expectedKeywords", "expected_keywords",
                    "gold_answers", "answer_keywords", "answer")));
            evalCase.setAnswerable(normalizeAnswerable(intValue(node, 1, "answerable", "is_answerable")));
            evalCase.setTaskType(firstTextOrDefault(node, "factual", "factual", "taskType", "task_type"));
            evalCase.setCategoryId(longValue(node, "categoryId", "category_id"));
            evalCase.setSource(firstTextOrDefault(node, request.source(), "imported", "source"));
            evalCase.setDeleted(0);
            caseMapper.insert(evalCase);
            imported++;
        }
        log.info("rag eval cases imported, scenario=admin-rag-eval-import, parsedCount={}, importedCount={}",
                nodes.size(), imported);
        return imported;
    }

    @Transactional
    public void deleteCase(Long caseId) {
        requireAdmin("admin-rag-eval-delete-case");
        RagEvalCase evalCase = caseMapper.selectById(caseId);
        if (evalCase == null || evalCase.getDeleted() != 0) {
            throw new BusinessException(ErrorCode.RAG_EVAL_CASE_INVALID);
        }
        caseMapper.deleteById(caseId);
        log.info("rag eval case deleted, scenario=admin-rag-eval-delete-case, caseId={}", caseId);
    }

    public List<RagEvalCase> loadCases(String datasetName) {
        String name = StringUtils.hasText(datasetName) ? datasetName : DEFAULT_DATASET;
        return caseMapper.selectList(new LambdaQueryWrapper<RagEvalCase>()
                .eq(RagEvalCase::getDatasetName, name)
                .eq(RagEvalCase::getDeleted, 0)
                .orderByAsc(RagEvalCase::getId));
    }

    public List<RagEvalCase> loadAllCases() {
        return caseMapper.selectList(new LambdaQueryWrapper<RagEvalCase>()
                .eq(RagEvalCase::getDeleted, 0)
                .orderByAsc(RagEvalCase::getId));
    }

    public List<Long> readLongList(String rawJson) {
        if (!StringUtils.hasText(rawJson)) {
            return List.of();
        }
        try {
            List<Long> values = new ArrayList<>();
            for (JsonNode node : objectMapper.readTree(rawJson)) {
                if (node == null || node.isNull()) {
                    continue;
                }
                Long value = node.isNumber() ? node.asLong() : parseLong(node.asText());
                if (value != null) {
                    values.add(value);
                }
            }
            return values;
        } catch (JsonProcessingException exception) {
            log.error("rag eval expected doc ids json invalid, scenario=rag-eval-parse, rawJson={}", rawJson, exception);
            return List.of();
        }
    }

    public List<String> readStringList(String rawJson) {
        if (!StringUtils.hasText(rawJson)) {
            return List.of();
        }
        try {
            List<String> values = new ArrayList<>();
            for (JsonNode node : objectMapper.readTree(rawJson)) {
                if (node != null && StringUtils.hasText(node.asText())) {
                    values.add(node.asText());
                }
            }
            return values;
        } catch (JsonProcessingException exception) {
            log.error("rag eval expected keywords json invalid, scenario=rag-eval-parse, rawJson={}", rawJson, exception);
            return List.of();
        }
    }

    private List<JsonNode> parseNodes(String content) {
        String trimmed = content.trim();
        List<JsonNode> nodes = new ArrayList<>();
        try {
            if (trimmed.startsWith("[")) {
                JsonNode array = objectMapper.readTree(trimmed);
                array.forEach(nodes::add);
                return nodes;
            }
            for (String line : trimmed.split("\\r?\\n")) {
                String row = line.trim();
                if (!StringUtils.hasText(row) || row.startsWith("#")) {
                    continue;
                }
                nodes.add(objectMapper.readTree(row));
            }
            return nodes;
        } catch (JsonProcessingException exception) {
            log.error("rag eval import content invalid, scenario=admin-rag-eval-import, reason=json-parse-failed", exception);
            throw new BusinessException(ErrorCode.RAG_EVAL_IMPORT_INVALID);
        }
    }

    private String firstText(JsonNode node, String... keys) {
        for (String key : keys) {
            JsonNode value = node.get(key);
            if (value != null && !value.isNull() && StringUtils.hasText(value.asText())) {
                return value.asText();
            }
        }
        return null;
    }

    private String firstTextOrDefault(JsonNode node, String requestValue, String fallback, String... keys) {
        String fromNode = firstText(node, keys);
        if (StringUtils.hasText(fromNode)) {
            return fromNode.trim();
        }
        if (StringUtils.hasText(requestValue)) {
            return requestValue.trim();
        }
        return fallback;
    }

    private int intValue(JsonNode node, int fallback, String... keys) {
        for (String key : keys) {
            JsonNode value = node.get(key);
            if (value == null || value.isNull()) {
                continue;
            }
            if (value.isBoolean()) {
                return value.asBoolean() ? 1 : 0;
            }
            if (value.isNumber()) {
                return value.asInt();
            }
            if (StringUtils.hasText(value.asText())) {
                String text = value.asText().trim().toLowerCase();
                if ("true".equals(text)) {
                    return 1;
                }
                if ("false".equals(text)) {
                    return 0;
                }
                try {
                    return Integer.parseInt(text);
                } catch (NumberFormatException ignored) {
                    return fallback;
                }
            }
        }
        return fallback;
    }

    private Long longValue(JsonNode node, String... keys) {
        for (String key : keys) {
            JsonNode value = node.get(key);
            if (value == null || value.isNull()) {
                continue;
            }
            Long parsed = value.isNumber() ? value.asLong() : parseLong(value.asText());
            if (parsed != null) {
                return parsed;
            }
        }
        return null;
    }

    private List<Long> longList(JsonNode node, String... keys) {
        for (String key : keys) {
            JsonNode value = node.get(key);
            if (value == null || value.isNull()) {
                continue;
            }
            if (value.isArray()) {
                List<Long> values = new ArrayList<>();
                for (JsonNode item : value) {
                    Long parsed = item.isNumber() ? item.asLong() : parseLong(item.asText());
                    if (parsed != null) {
                        values.add(parsed);
                    }
                }
                return values;
            }
            Long single = parseLong(value.asText());
            if (single != null) {
                return List.of(single);
            }
        }
        return List.of();
    }

    private List<String> stringList(JsonNode node, String... keys) {
        for (String key : keys) {
            JsonNode value = node.get(key);
            if (value == null || value.isNull()) {
                continue;
            }
            if (value.isArray()) {
                List<String> values = new ArrayList<>();
                for (JsonNode item : value) {
                    if (StringUtils.hasText(item.asText())) {
                        values.add(item.asText().trim());
                    }
                }
                return values;
            }
            if (StringUtils.hasText(value.asText())) {
                return List.of(value.asText().trim());
            }
        }
        return List.of();
    }

    private Long parseLong(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String writeJson(List<?> values) {
        try {
            return objectMapper.writeValueAsString(values == null ? List.of() : values);
        } catch (JsonProcessingException exception) {
            log.error("rag eval json write failed, scenario=rag-eval-write, values={}", values, exception);
            throw new BusinessException(ErrorCode.RAG_EVAL_CASE_INVALID);
        }
    }

    private boolean isUnanswerable(Integer answerable) {
        return answerable != null && answerable == 0;
    }

    private int normalizeAnswerable(Integer value) {
        return value != null && value == 0 ? 0 : 1;
    }

    private RagEvalCaseResponse toResponse(RagEvalCase evalCase) {
        return new RagEvalCaseResponse(evalCase.getId(), evalCase.getDatasetName(), evalCase.getQuestion(),
                readLongList(evalCase.getExpectedChunkIds()), readLongList(evalCase.getExpectedDocIds()),
                readStringList(evalCase.getExpectedKeywords()),
                evalCase.getAnswerable(), evalCase.getTaskType(), evalCase.getCategoryId(), evalCase.getSource(),
                evalCase.getCreatedAt());
    }

    private void requireAdmin(String scenario) {
        CurrentUser currentUser = CurrentUserProvider.require();
        if (currentUser.getRole() != UserRole.ADMIN) {
            log.warn("rag eval role denied, scenario={}, userId={}, roleCode={}",
                    scenario, currentUser.getId(), currentUser.getRoleCode());
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
    }
}
