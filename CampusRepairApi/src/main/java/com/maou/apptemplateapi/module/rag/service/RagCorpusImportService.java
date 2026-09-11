package com.maou.apptemplateapi.module.rag.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.maou.apptemplateapi.common.enums.UserRole;
import com.maou.apptemplateapi.common.exception.BusinessException;
import com.maou.apptemplateapi.common.exception.ErrorCode;
import com.maou.apptemplateapi.common.security.CurrentUser;
import com.maou.apptemplateapi.common.security.CurrentUserProvider;
import com.maou.apptemplateapi.module.rag.dto.CorpusBatchImportRequest;
import com.maou.apptemplateapi.module.rag.dto.CorpusImportRequest;
import com.maou.apptemplateapi.module.rag.dto.CorpusImportResponse;
import com.maou.apptemplateapi.module.rag.dto.CorpusPreviewResponse;
import com.maou.apptemplateapi.module.rag.dto.CorpusSectionItem;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 语料导入：规范 / 手册 / 校内制度 → 清洗 → 章节切分 → 元数据入库 → 切片并同步向量库。
 *
 * <p>两条入口：
 * <ol>
 *   <li>{@link #preview} / {@link #importDocument}：传整篇原文，后端负责清洗与章节切分（适合直接在页面粘贴）；</li>
 *   <li>{@link #importBatch}：传已切分好的章节列表，字段与 {@code tools/corpus/convert_corpus.py} 输出一致
 *       （适合 PDF/Word 批量转换后导入）。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagCorpusImportService {

    private static final int PREVIEW_SECTION_LIMIT = 8;
    private static final int PREVIEW_SAMPLE_LENGTH = 120;
    private static final int MAX_BATCH_ITEMS = 2000;

    private final RagCorpusSplitter corpusSplitter;
    private final RagKnowledgeService ragKnowledgeService;

    public CorpusPreviewResponse preview(CorpusImportRequest request) {
        requireAdmin("admin-rag-corpus-preview");
        if (request == null || !StringUtils.hasText(request.content())) {
            throw new BusinessException(ErrorCode.RAG_EVAL_IMPORT_INVALID);
        }
        List<RagCorpusSplitter.CorpusSection> sections = corpusSplitter.split(
                request.content(), request.chunkSize(), request.chunkOverlap());
        if (sections.isEmpty()) {
            throw new BusinessException(ErrorCode.RAG_EVAL_IMPORT_INVALID);
        }
        int totalChars = sections.stream().mapToInt(section -> section.content().length()).sum();
        List<CorpusPreviewResponse.CorpusSectionPreview> previews = sections.stream()
                .limit(PREVIEW_SECTION_LIMIT)
                .map(section -> new CorpusPreviewResponse.CorpusSectionPreview(section.sectionTitle(),
                        section.sectionLevel(), section.content().length(), sample(section.content())))
                .toList();
        log.info("rag corpus preview, scenario=admin-rag-corpus-preview, title={}, sectionCount={}, chunkCount={}, totalChars={}",
                request.title(), distinctSections(sections), sections.size(), totalChars);
        return new CorpusPreviewResponse(request.title(), distinctSections(sections), sections.size(),
                totalChars, totalChars / Math.max(sections.size(), 1), previews);
    }

    @Transactional
    public CorpusImportResponse importDocument(CorpusImportRequest request) {
        CurrentUser admin = requireAdmin("admin-rag-corpus-import");
        if (request == null || !StringUtils.hasText(request.title()) || !StringUtils.hasText(request.content())) {
            throw new BusinessException(ErrorCode.RAG_EVAL_IMPORT_INVALID);
        }
        List<RagCorpusSplitter.CorpusSection> sections = corpusSplitter.split(
                request.content(), request.chunkSize(), request.chunkOverlap());
        if (sections.isEmpty()) {
            throw new BusinessException(ErrorCode.RAG_EVAL_IMPORT_INVALID);
        }
        String batchName = StringUtils.hasText(request.importBatch()) ? request.importBatch() : defaultBatch();
        Long documentId = ragKnowledgeService.createDocumentFromCorpus(request.title().trim(), request.categoryId(),
                request.content(), metadata(request.source(), request.standardNo(), request.docVersion(),
                        request.effectiveDate(), request.docType(), batchName),
                sections, request.enabled(), admin.getId(), "admin-rag-corpus-import");
        log.info("rag corpus imported, scenario=admin-rag-corpus-import, documentId={}, title={}, standardNo={}, sectionCount={}, chunkCount={}, batchName={}, adminId={}",
                documentId, request.title(), request.standardNo(), distinctSections(sections), sections.size(), batchName, admin.getId());
        return new CorpusImportResponse(documentId, request.title().trim(), request.standardNo(),
                distinctSections(sections), sections.size(), batchName, sectionTitles(sections));
    }

    @Transactional
    public List<CorpusImportResponse> importBatch(CorpusBatchImportRequest request) {
        CurrentUser admin = requireAdmin("admin-rag-corpus-import-batch");
        if (request == null || request.documents() == null || request.documents().isEmpty()) {
            throw new BusinessException(ErrorCode.RAG_EVAL_IMPORT_INVALID);
        }
        if (request.documents().size() > MAX_BATCH_ITEMS) {
            log.warn("rag corpus batch truncated, scenario=admin-rag-corpus-import-batch, size={}, limit={}",
                    request.documents().size(), MAX_BATCH_ITEMS);
        }
        String batchName = StringUtils.hasText(request.batchName()) ? request.batchName() : defaultBatch();
        Map<String, List<CorpusSectionItem>> grouped = new LinkedHashMap<>();
        for (CorpusSectionItem item : request.documents().stream().limit(MAX_BATCH_ITEMS).toList()) {
            if (item == null || !StringUtils.hasText(item.content())) {
                log.warn("rag corpus batch item skipped, scenario=admin-rag-corpus-import-batch, reason=blank-content, item={}", item);
                continue;
            }
            grouped.computeIfAbsent(groupKey(item), key -> new ArrayList<>()).add(item);
        }
        List<CorpusImportResponse> responses = new ArrayList<>();
        for (Map.Entry<String, List<CorpusSectionItem>> entry : grouped.entrySet()) {
            List<CorpusSectionItem> items = entry.getValue();
            CorpusSectionItem head = items.get(0);
            List<RagCorpusSplitter.CorpusSection> sections = new ArrayList<>();
            List<String> titles = new ArrayList<>();
            for (CorpusSectionItem item : items) {
                String sectionTitle = StringUtils.hasText(item.sectionTitle())
                        ? item.sectionTitle().trim()
                        : StringUtils.hasText(item.title()) ? item.title().trim() : "正文";
                String sectionContent = stripSectionPrefix(item.content());
                titles.add(sectionTitle);
                sections.add(new RagCorpusSplitter.CorpusSection(sectionTitle, item.sectionLevel() == null ? 2 : item.sectionLevel(),
                        "【章节】%s\n%s".formatted(sectionTitle, sectionContent)));
            }
            String documentTitle = StringUtils.hasText(head.title()) ? head.title().trim() : entry.getKey();
            Long documentId = ragKnowledgeService.createDocumentFromCorpus(documentTitle, head.categoryId(),
                    buildDocumentContent(sections),
                    metadata(head.source(), head.standardNo(), head.docVersion(), head.effectiveDate(), head.docType(), batchName),
                    sections, 1, admin.getId(), "admin-rag-corpus-import-batch");
            responses.add(new CorpusImportResponse(documentId, documentTitle, head.standardNo(),
                    distinctSectionTitles(titles).size(), sections.size(), batchName, distinctSectionTitles(titles)));
        }
        log.info("rag corpus batch imported, scenario=admin-rag-corpus-import-batch, batchName={}, itemCount={}, documentCount={}, chunkCount={}, adminId={}",
                batchName, request.documents().size(), responses.size(),
                responses.stream().mapToInt(CorpusImportResponse::chunkCount).sum(), admin.getId());
        return responses;
    }

    private RagKnowledgeService.CorpusMetadata metadata(String source,
                                                       String standardNo,
                                                       String docVersion,
                                                       LocalDate effectiveDate,
                                                       String docType,
                                                       String batchName) {
        return new RagKnowledgeService.CorpusMetadata(source, standardNo, docVersion, effectiveDate, docType, batchName);
    }

    private String groupKey(CorpusSectionItem item) {
        String standardNo = StringUtils.hasText(item.standardNo()) ? item.standardNo().trim() : "";
        String title = StringUtils.hasText(item.title()) ? item.title().trim() : "未命名语料";
        return standardNo.isEmpty() ? title : standardNo + "|" + title;
    }

    private String buildDocumentContent(List<RagCorpusSplitter.CorpusSection> sections) {
        StringBuilder builder = new StringBuilder();
        for (RagCorpusSplitter.CorpusSection section : sections) {
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(section.content());
        }
        return builder.toString();
    }

    private String stripSectionPrefix(String content) {
        String trimmed = content.trim();
        return trimmed.startsWith("【章节】") && trimmed.contains("\n")
                ? trimmed.substring(trimmed.indexOf('\n') + 1).trim()
                : trimmed;
    }

    private String sample(String content) {
        String flattened = content.replace("\n", " ").trim();
        return flattened.length() > PREVIEW_SAMPLE_LENGTH ? flattened.substring(0, PREVIEW_SAMPLE_LENGTH) + "..." : flattened;
    }

    private int distinctSections(List<RagCorpusSplitter.CorpusSection> sections) {
        return (int) sections.stream().map(RagCorpusSplitter.CorpusSection::sectionTitle).distinct().count();
    }

    private List<String> distinctSectionTitles(List<String> titles) {
        return titles.stream().distinct().toList();
    }

    private List<String> sectionTitles(List<RagCorpusSplitter.CorpusSection> sections) {
        return sections.stream().map(RagCorpusSplitter.CorpusSection::sectionTitle).distinct().toList();
    }

    private String defaultBatch() {
        return "corpus-" + LocalDate.now() + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private CurrentUser requireAdmin(String scenario) {
        CurrentUser currentUser = CurrentUserProvider.require();
        if (currentUser.getRole() != UserRole.ADMIN) {
            log.warn("rag corpus role denied, scenario={}, userId={}, roleCode={}",
                    scenario, currentUser.getId(), currentUser.getRoleCode());
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        return currentUser;
    }
}
