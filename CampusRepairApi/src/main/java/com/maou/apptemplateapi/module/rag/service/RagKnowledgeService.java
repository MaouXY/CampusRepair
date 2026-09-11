package com.maou.apptemplateapi.module.rag.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.maou.apptemplateapi.common.config.ai.AgentToolProperties;
import com.maou.apptemplateapi.common.enums.UserRole;
import com.maou.apptemplateapi.common.exception.BusinessException;
import com.maou.apptemplateapi.common.exception.ErrorCode;
import com.maou.apptemplateapi.common.result.PageResult;
import com.maou.apptemplateapi.common.security.CurrentUser;
import com.maou.apptemplateapi.common.security.CurrentUserProvider;
import com.maou.apptemplateapi.module.rag.dto.KnowledgeDocumentRequest;
import com.maou.apptemplateapi.module.rag.dto.KnowledgeDocumentResponse;
import com.maou.apptemplateapi.module.rag.dto.RagChunkResponse;
import com.maou.apptemplateapi.module.rag.dto.RagSearchResult;
import com.maou.apptemplateapi.module.rag.dto.RagVectorStatusResponse;
import com.maou.apptemplateapi.module.rag.entity.RagKnowledgeChunk;
import com.maou.apptemplateapi.module.rag.entity.RagKnowledgeDocument;
import com.maou.apptemplateapi.module.rag.mapper.RagKnowledgeChunkMapper;
import com.maou.apptemplateapi.module.rag.mapper.RagKnowledgeDocumentMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class RagKnowledgeService {

    private final RagKnowledgeDocumentMapper documentMapper;
    private final RagKnowledgeChunkMapper chunkMapper;
    private final AgentToolProperties agentToolProperties;
    private final MilvusRagVectorService milvusRagVectorService;
    private final RagRerankService ragRerankService;
    private final RagEmbeddingService ragEmbeddingService;
    private final RagFusionService ragFusionService;
    private final RagRankBlender ragRankBlender;
    private final RagCorpusSplitter ragCorpusSplitter;

    public PageResult<KnowledgeDocumentResponse> listDocuments(long page, long size) {
        requireAdmin("admin-list-rag-documents");
        Page<RagKnowledgeDocument> result = documentMapper.selectPage(new Page<>(Math.max(page, 1), Math.min(Math.max(size, 1), 100)),
                new LambdaQueryWrapper<RagKnowledgeDocument>()
                        .eq(RagKnowledgeDocument::getDeleted, 0)
                        .orderByDesc(RagKnowledgeDocument::getId));
        Map<Long, Long> chunkCounts = countChunks(result.getRecords().stream().map(RagKnowledgeDocument::getId).toList());
        return PageResult.of(result.getRecords().stream().map(doc -> toResponse(doc, chunkCounts.getOrDefault(doc.getId(), 0L).intValue())).toList(),
                result.getCurrent(), result.getSize(), result.getTotal());
    }

    @Transactional
    public KnowledgeDocumentResponse createDocument(KnowledgeDocumentRequest request) {
        CurrentUser admin = requireAdmin("admin-create-rag-document");
        RagKnowledgeDocument document = new RagKnowledgeDocument();
        apply(document, request);
        document.setCreatedBy(admin.getId());
        documentMapper.insert(document);
        rebuildChunks(document, "admin-create-rag-document");
        return toResponse(documentMapper.selectById(document.getId()), countChunks(List.of(document.getId())).getOrDefault(document.getId(), 0L).intValue());
    }

    @Transactional
    public KnowledgeDocumentResponse updateDocument(Long id, KnowledgeDocumentRequest request) {
        requireAdmin("admin-update-rag-document");
        RagKnowledgeDocument document = requireDocument(id, "admin-update-rag-document");
        apply(document, request);
        documentMapper.updateById(document);
        rebuildChunks(document, "admin-update-rag-document");
        return toResponse(documentMapper.selectById(id), countChunks(List.of(id)).getOrDefault(id, 0L).intValue());
    }

    @Transactional
    public void deleteDocument(Long id) {
        requireAdmin("admin-delete-rag-document");
        requireDocument(id, "admin-delete-rag-document");
        documentMapper.deleteById(id);
        int deletedChunks = chunkMapper.physicalDeleteByDocumentId(id);
        log.info("rag document deleted, scenario=admin-delete-rag-document, documentId={}, deletedChunkCount={}", id, deletedChunks);
    }

    @Transactional
    public int rebuildDocumentChunks(Long documentId) {
        requireAdmin("admin-rebuild-rag-document");
        RagKnowledgeDocument document = requireDocument(documentId, "admin-rebuild-rag-document");
        return rebuildChunks(document, "admin-rebuild-rag-document");
    }

    /**
     * 语料导入落库：写入带元数据的知识文档，并按「章节」切片（每片带 section_title，便于引用出处）。
     */
    @Transactional
    public Long createDocumentFromCorpus(String title,
                                         Long categoryId,
                                         String rawContent,
                                         CorpusMetadata metadata,
                                         List<RagCorpusSplitter.CorpusSection> sections,
                                         Integer enabled,
                                         Long operatorId,
                                         String scenario) {
        RagKnowledgeDocument document = new RagKnowledgeDocument();
        document.setTitle(title);
        document.setCategoryId(categoryId);
        document.setContent(rawContent);
        document.setEnabled(enabled == null || enabled != 0 ? 1 : 0);
        document.setCreatedBy(operatorId);
        document.setSource(metadata.source());
        document.setStandardNo(metadata.standardNo());
        document.setDocVersion(metadata.docVersion());
        document.setEffectiveDate(metadata.effectiveDate());
        document.setDocType(metadata.docType());
        document.setImportBatch(metadata.batchName());
        documentMapper.insert(document);
        int chunkCount = rebuildChunksWithSections(document, sections, scenario);
        log.info("rag corpus document saved, scenario={}, documentId={}, title={}, standardNo={}, source={}, batchName={}, sectionCount={}, chunkCount={}, operatorId={}",
                scenario, document.getId(), title, metadata.standardNo(), metadata.source(), metadata.batchName(),
                sections.stream().map(RagCorpusSplitter.CorpusSection::sectionTitle).distinct().count(), chunkCount, operatorId);
        return document.getId();
    }

    private int rebuildChunksWithSections(RagKnowledgeDocument document,
                                          List<RagCorpusSplitter.CorpusSection> sections,
                                          String scenario) {
        Long documentId = document.getId();
        int deletedChunks = chunkMapper.physicalDeleteByDocumentId(documentId);
        List<RagKnowledgeChunk> savedChunks = new java.util.ArrayList<>();
        int index = 0;
        for (RagCorpusSplitter.CorpusSection section : sections) {
            savedChunks.add(insertChunk(document, index++, section.content(), section.sectionTitle()));
        }
        boolean vectorSynced = milvusRagVectorService.rebuildDocument(documentId, savedChunks, scenario);
        if (milvusRagVectorService.enabled()) {
            for (RagKnowledgeChunk chunk : savedChunks) {
                chunk.setVectorStoreStatus(vectorSynced ? "SYNCED" : "FAILED");
                chunkMapper.updateById(chunk);
            }
        }
        log.info("rag corpus chunks replaced, scenario={}, documentId={}, deletedChunkCount={}, insertedChunkCount={}, milvusEnabled={}, vectorSynced={}",
                scenario, documentId, deletedChunks, savedChunks.size(), milvusRagVectorService.enabled(), vectorSynced);
        return savedChunks.size();
    }

    /**
     * 语料元数据：来源、标准号、版本、生效日期、文档类型、导入批次。
     */
    public record CorpusMetadata(
            String source,
            String standardNo,
            String docVersion,
            LocalDate effectiveDate,
            String docType,
            String batchName
    ) {
    }

    /**
     * 知识草稿审核入库：由草稿服务在管理员审核通过后调用（内部方法，鉴权在调用方完成）。
     */
    @Transactional
    public KnowledgeDocumentResponse createDocumentFromDraft(String title,
                                                             Long categoryId,
                                                             String content,
                                                             Long operatorId,
                                                             String scenario) {
        RagKnowledgeDocument document = new RagKnowledgeDocument();
        document.setTitle(title);
        document.setCategoryId(categoryId);
        document.setContent(content);
        document.setEnabled(1);
        document.setCreatedBy(operatorId);
        documentMapper.insert(document);
        int chunkCount = rebuildChunks(document, scenario);
        log.info("rag document created from draft, scenario={}, documentId={}, categoryId={}, chunkCount={}, operatorId={}",
                scenario, document.getId(), categoryId, chunkCount, operatorId);
        return toResponse(documentMapper.selectById(document.getId()), chunkCount);
    }

    /**
     * 向量检索真实状态核对：Embedding 提供方/模型/维度 + Milvus 开关 + 切片同步情况 + 混合检索与重排配置。
     */
    public RagVectorStatusResponse vectorStatus() {
        requireAdmin("admin-rag-vector-status");
        AgentToolProperties.Milvus milvus = agentToolProperties.getRag().getMilvus();
        AgentToolProperties.Hybrid hybrid = agentToolProperties.getRag().getHybrid();
        AgentToolProperties.Rerank rerank = agentToolProperties.getBocha().getRerank();
        long documentCount = documentMapper.selectCount(new LambdaQueryWrapper<RagKnowledgeDocument>()
                .eq(RagKnowledgeDocument::getDeleted, 0));
        List<Long> documentIds = documentMapper.selectList(new LambdaQueryWrapper<RagKnowledgeDocument>()
                        .select(RagKnowledgeDocument::getId)
                        .eq(RagKnowledgeDocument::getDeleted, 0))
                .stream()
                .map(RagKnowledgeDocument::getId)
                .toList();
        // 只统计仍存在文档的切片：历史遗留的孤儿切片（文档已删）不参与同步率统计
        long chunkCount = documentIds.isEmpty() ? 0 : chunkMapper.selectCount(new LambdaQueryWrapper<RagKnowledgeChunk>()
                .eq(RagKnowledgeChunk::getDeleted, 0)
                .in(RagKnowledgeChunk::getDocumentId, documentIds));
        long synced = countChunkByVectorStatus(documentIds, "SYNCED");
        long pending = countChunkByVectorStatus(documentIds, "PENDING");
        long failed = countChunkByVectorStatus(documentIds, "FAILED");
        int rrfK = hybrid.getRrfK() == null ? RagFusionService.DEFAULT_RRF_K : Math.max(hybrid.getRrfK(), 1);
        String message;
        if (!milvusRagVectorService.enabled()) {
            message = "向量检索未启用：当前为关键词检索（可选混合检索），历史切片状态不参与打分";
        } else if (failed > 0) {
            message = "存在 %d 个切片同步失败，建议在知识管理中执行「重建」后复查".formatted(failed);
        } else if (pending > 0) {
            message = "存在 %d 个切片待同步，稍后或重建后即可检索".formatted(pending);
        } else {
            message = "向量检索已启用且切片已全部同步";
        }
        RagVectorStatusResponse response = new RagVectorStatusResponse(
                ragEmbeddingService.provider(), ragEmbeddingService.modelName(), ragEmbeddingService.dimension(),
                milvusRagVectorService.enabled(), milvus.getCollectionName(), documentCount, chunkCount,
                synced, pending, failed, hybrid.isEnabled(), rrfK, rerank.isEnabled(), rerank.getModel(),
                rankingOf(agentToolProperties.getRag().getRanking().getRerankBlendWeight(), 0.7),
                windowOf(agentToolProperties.getRag().getRanking().getRerankWindow(), 20),
                rankingOf(agentToolProperties.getRag().getRanking().getMinVectorScore(), 0.0),
                windowOf(agentToolProperties.getRag().getRanking().getMinKeywordHits(), 1),
                message);
        log.info("rag vector status queried, scenario=admin-rag-vector-status, provider={}, model={}, dimension={}, milvusEnabled={}, collectionName={}, documentCount={}, chunkCount={}, synced={}, pending={}, failed={}, hybridEnabled={}, rrfK={}, rerankEnabled={}",
                response.embeddingProvider(), response.embeddingModel(), response.dimension(), response.milvusEnabled(),
                response.collectionName(), documentCount, chunkCount, synced, pending, failed,
                response.hybridEnabled(), response.rrfK(), response.rerankEnabled());
        return response;
    }

    private long countChunkByVectorStatus(List<Long> documentIds, String status) {
        if (documentIds.isEmpty()) {
            return 0;
        }
        Long count = chunkMapper.selectCount(new LambdaQueryWrapper<RagKnowledgeChunk>()
                .eq(RagKnowledgeChunk::getDeleted, 0)
                .in(RagKnowledgeChunk::getDocumentId, documentIds)
                .eq(RagKnowledgeChunk::getVectorStoreStatus, status));
        return count == null ? 0 : count;
    }

    private double rankingOf(Double value, double fallback) {
        return value == null ? fallback : value;
    }

    private int windowOf(Integer value, int fallback) {
        return value == null || value <= 0 ? fallback : value;
    }

    /**
     * 重建切片。
     *
     * <p>文档带章节结构（如导入的规范/手册）时，按章节切分并保留 {@code section_title}，
     * 与语料导入的切片方式保持一致——否则「重建」会把章节切片重新压成定长切片，
     * 丢掉章节信息并让检索质量回退；没有识别到章节标题的普通文档仍走原有定长切分。
     */
    private int rebuildChunks(RagKnowledgeDocument document, String scenario) {
        Long documentId = document.getId();
        int deletedChunks = chunkMapper.physicalDeleteByDocumentId(documentId);
        Integer configuredChunkSize = agentToolProperties.getRag().getMilvus().getChunkSize();
        Integer configuredOverlap = agentToolProperties.getRag().getMilvus().getChunkOverlap();
        List<RagCorpusSplitter.CorpusSection> sections = ragCorpusSplitter.split(document.getContent(),
                configuredChunkSize, configuredOverlap);
        boolean structured = sections.stream()
                .anyMatch(section -> !"正文".equals(section.sectionTitle()));
        List<RagKnowledgeChunk> savedChunks = new java.util.ArrayList<>();
        int index = 0;
        if (structured) {
            for (RagCorpusSplitter.CorpusSection section : sections) {
                savedChunks.add(insertChunk(document, index++, section.content(), section.sectionTitle()));
            }
        } else {
            for (String content : splitText(document.getContent())) {
                savedChunks.add(insertChunk(document, index++, content, null));
            }
        }
        boolean vectorSynced = milvusRagVectorService.rebuildDocument(documentId, savedChunks, scenario);
        if (milvusRagVectorService.enabled()) {
            for (RagKnowledgeChunk chunk : savedChunks) {
                chunk.setVectorStoreStatus(vectorSynced ? "SYNCED" : "FAILED");
                chunkMapper.updateById(chunk);
            }
        }
        log.info("rag document rebuilt, scenario={}, documentId={}, structuredSections={}, chunkCount={}, milvusEnabled={}, collectionName={}, rerankEnabled={}",
                scenario, documentId, structured, savedChunks.size(), agentToolProperties.getRag().getMilvus().isEnabled(),
                agentToolProperties.getRag().getMilvus().getCollectionName(), agentToolProperties.getBocha().getRerank().isEnabled());
        log.info("rag document chunks replaced, scenario={}, documentId={}, deletedChunkCount={}, insertedChunkCount={}",
                scenario, documentId, deletedChunks, savedChunks.size());
        return savedChunks.size();
    }

    private RagKnowledgeChunk insertChunk(RagKnowledgeDocument document, int index, String content, String sectionTitle) {
        RagKnowledgeChunk chunk = new RagKnowledgeChunk();
        chunk.setDocumentId(document.getId());
        chunk.setChunkIndex(index);
        chunk.setContent(content);
        chunk.setSectionTitle(sectionTitle);
        chunk.setTokenCount(Math.max(1, content.length() / 2));
        chunk.setEmbeddingProvider("%s:%s".formatted(ragEmbeddingService.provider(), ragEmbeddingService.modelName()));
        chunk.setVectorStoreStatus(milvusRagVectorService.enabled() ? "PENDING" : "DISABLED");
        chunk.setEnabled(document.getEnabled());
        chunkMapper.insert(chunk);
        return chunk;
    }

    public List<RagChunkResponse> search(Long categoryId, String query, int limit) {
        return searchDetailed(categoryId, query, limit).chunks();
    }

    /**
     * 混合检索入口：关键词召回 + 向量召回，RRF 融合后再做重排。
     */
    public RagSearchResult searchDetailed(Long categoryId, String query, int limit) {
        List<RagKnowledgeDocument> documents = documentMapper.selectList(new LambdaQueryWrapper<RagKnowledgeDocument>()
                .eq(RagKnowledgeDocument::getEnabled, 1)
                .eq(RagKnowledgeDocument::getDeleted, 0)
                .and(categoryId != null, wrapper -> wrapper.eq(RagKnowledgeDocument::getCategoryId, categoryId).or().isNull(RagKnowledgeDocument::getCategoryId)));
        if (documents.isEmpty() || !StringUtils.hasText(query)) {
            return RagSearchResult.empty();
        }
        int requestedLimit = Math.max(limit, 1);
        AgentToolProperties.Hybrid hybrid = agentToolProperties.getRag().getHybrid();
        int rerankTopN = agentToolProperties.getBocha().getRerank().getTopN() == null
                ? requestedLimit
                : agentToolProperties.getBocha().getRerank().getTopN();
        int fuseLimit = Math.max(requestedLimit, rerankTopN);
        Map<Long, RagKnowledgeDocument> docMap = documents.stream()
                .collect(Collectors.toMap(RagKnowledgeDocument::getId, doc -> doc));
        Set<Long> docIds = docMap.keySet();

        int keywordTopK = hybrid.isEnabled() ? resolveTopK(hybrid.getKeywordTopK(), requestedLimit) : fuseLimit;
        int vectorTopK = hybrid.isEnabled() ? resolveTopK(hybrid.getVectorTopK(), requestedLimit) : fuseLimit;
        List<RagChunkResponse> keywordMatches = keywordSearch(docMap, query, keywordTopK);
        List<RagChunkResponse> vectorMatches = hydrateMilvusMatches(
                milvusRagVectorService.search(query, vectorTopK, "rag-search"), docIds);

        List<RagChunkResponse> merged;
        List<RagFusionService.RagFusionResult> fusedResults = List.of();
        int overlapCount = 0;
        String topSource;
        if (!hybrid.isEnabled()) {
            merged = vectorMatches.isEmpty() ? keywordMatches : vectorMatches;
            topSource = vectorMatches.isEmpty()
                    ? (keywordMatches.isEmpty() ? "NONE" : RagFusionService.SOURCE_KEYWORD)
                    : RagFusionService.SOURCE_VECTOR;
        } else {
            int rrfK = hybrid.getRrfK() == null ? RagFusionService.DEFAULT_RRF_K : Math.max(hybrid.getRrfK(), 1);
            Map<String, List<RagChunkResponse>> rankedLists = new LinkedHashMap<>();
            rankedLists.put(RagFusionService.SOURCE_KEYWORD, keywordMatches);
            rankedLists.put(RagFusionService.SOURCE_VECTOR, vectorMatches);
            Map<String, Double> weights = new LinkedHashMap<>();
            weights.put(RagFusionService.SOURCE_KEYWORD, positiveOrDefault(hybrid.getKeywordWeight()));
            weights.put(RagFusionService.SOURCE_VECTOR, positiveOrDefault(hybrid.getVectorWeight()));
            fusedResults = ragFusionService.fuse(rankedLists, weights, rrfK, fuseLimit);
            merged = fusedResults.stream().map(RagFusionService.RagFusionResult::chunk).toList();
            overlapCount = (int) fusedResults.stream().filter(RagFusionService.RagFusionResult::fromBothSources).count();
            topSource = fusedResults.isEmpty() ? "NONE" : fusedResults.get(0).sourceLabel();
        }

        AgentToolProperties.Ranking ranking = agentToolProperties.getRag().getRanking();
        double blendWeight = ranking.getRerankBlendWeight() == null ? 0.7 : ranking.getRerankBlendWeight();
        int rerankWindow = ranking.getRerankWindow() == null || ranking.getRerankWindow() <= 0
                ? fuseLimit
                : Math.max(ranking.getRerankWindow(), 1);
        boolean rerankApplied = false;
        String rerankSkipReason = "hybrid-disabled";
        List<RagChunkResponse> ordered;
        if (fusedResults.isEmpty()) {
            ordered = merged.stream().limit(requestedLimit).toList();
            rerankSkipReason = "no-fused-candidates";
        } else {
            int window = Math.min(rerankWindow, fusedResults.size());
            List<RagChunkResponse> windowCandidates = fusedResults.subList(0, window).stream()
                    .map(RagFusionService.RagFusionResult::chunk)
                    .toList();
            RagRerankService.RerankOutcome outcome = ragRerankService.rerankScores(query, windowCandidates, "rag-search-rerank");
            rerankApplied = outcome.applied();
            rerankSkipReason = outcome.reason();
            ordered = ragRankBlender.blend(fusedResults, outcome.scores(), blendWeight, window, requestedLimit, "rag-search-blend");
        }
        log.info("rag hybrid search done, scenario=rag-search, hybridEnabled={}, vectorEnabled={}, keywordTopK={}, vectorTopK={}, minKeywordHits={}, minVectorScore={}, keywordCandidateCount={}, vectorCandidateCount={}, overlapCount={}, fusedCount={}, returnedCount={}, topSource={}, rerankApplied={}, rerankSkipReason={}, rerankBlendWeight={}, rerankWindow={}",
                hybrid.isEnabled(), milvusRagVectorService.enabled(), keywordTopK, vectorTopK,
                ranking.getMinKeywordHits(), ranking.getMinVectorScore(),
                keywordMatches.size(), vectorMatches.size(), overlapCount, merged.size(), ordered.size(),
                topSource, rerankApplied, rerankSkipReason, blendWeight, rerankWindow);
        return new RagSearchResult(ordered, keywordMatches.size(), vectorMatches.size(), overlapCount,
                merged.size(), topSource, milvusRagVectorService.enabled(), rerankApplied, rerankSkipReason,
                blendWeight, rerankWindow);
    }

    private int resolveTopK(Integer configured, int requestedLimit) {
        int fallback = Math.max(requestedLimit * 2, requestedLimit);
        if (configured == null || configured <= 0) {
            return fallback;
        }
        return Math.max(configured, requestedLimit);
    }

    private double positiveOrDefault(Double value) {
        return value == null || value <= 0 ? 1.0 : value;
    }

    private List<RagChunkResponse> keywordSearch(Map<Long, RagKnowledgeDocument> docMap, String query, int limit) {
        Set<String> keywords = tokenize(query);
        int minHits = minKeywordHits();
        return chunkMapper.selectList(new LambdaQueryWrapper<RagKnowledgeChunk>()
                        .in(RagKnowledgeChunk::getDocumentId, docMap.keySet())
                        .eq(RagKnowledgeChunk::getEnabled, 1)
                        .eq(RagKnowledgeChunk::getDeleted, 0))
                .stream()
                .map(chunk -> {
                    double score = score(chunk.getContent(), keywords);
                    RagKnowledgeDocument doc = docMap.get(chunk.getDocumentId());
                    return new RagChunkResponse(chunk.getId(), chunk.getDocumentId(), doc == null ? null : doc.getTitle(), chunk.getContent(), score);
                })
                .filter(chunk -> chunk.score() >= minHits)
                .sorted(Comparator.comparingDouble(RagChunkResponse::score).reversed()
                        .thenComparing(RagChunkResponse::id, Comparator.nullsLast(Comparator.naturalOrder())))
                .limit(Math.max(limit, 1))
                .toList();
    }

    /**
     * 关键词召回最低命中词数：命中 1 个 bigram 的片段噪声较大，可通过配置提高到 2 及以上。
     */
    private int minKeywordHits() {
        Integer configured = agentToolProperties.getRag().getRanking().getMinKeywordHits();
        return configured == null || configured <= 0 ? 1 : configured;
    }

    private List<RagChunkResponse> hydrateMilvusMatches(List<RagChunkResponse> matches, Set<Long> allowedDocumentIds) {
        List<Long> chunkIds = matches.stream().map(RagChunkResponse::id).filter(Objects::nonNull).distinct().toList();
        if (chunkIds.isEmpty()) {
            return matches;
        }
        Map<Long, RagKnowledgeChunk> chunks = chunkMapper.selectBatchIds(chunkIds).stream()
                .filter(chunk -> chunk.getDeleted() == 0 && chunk.getEnabled() == 1)
                .filter(chunk -> allowedDocumentIds.contains(chunk.getDocumentId()))
                .collect(Collectors.toMap(RagKnowledgeChunk::getId, chunk -> chunk));
        Map<Long, RagKnowledgeDocument> documents = documentMapper.selectBatchIds(chunks.values().stream()
                        .map(RagKnowledgeChunk::getDocumentId)
                        .distinct()
                        .toList())
                .stream()
                .collect(Collectors.toMap(RagKnowledgeDocument::getId, document -> document));
        return matches.stream()
                .map(match -> {
                    RagKnowledgeChunk chunk = chunks.get(match.id());
                    if (chunk == null) {
                        return null;
                    }
                    RagKnowledgeDocument document = documents.get(chunk.getDocumentId());
                    return new RagChunkResponse(chunk.getId(), chunk.getDocumentId(),
                            document == null ? match.title() : document.getTitle(), chunk.getContent(), match.score());
                })
                .filter(Objects::nonNull)
                .toList();
    }

    private CurrentUser requireAdmin(String scenario) {
        CurrentUser currentUser = CurrentUserProvider.require();
        if (currentUser.getRole() != UserRole.ADMIN) {
            log.warn("rag role denied, scenario={}, userId={}, roleCode={}", scenario, currentUser.getId(), currentUser.getRoleCode());
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        return currentUser;
    }

    private RagKnowledgeDocument requireDocument(Long id, String scenario) {
        RagKnowledgeDocument document = documentMapper.selectById(id);
        if (document == null || document.getDeleted() != 0) {
            log.warn("rag document not found, scenario={}, documentId={}", scenario, id);
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        return document;
    }

    private void apply(RagKnowledgeDocument document, KnowledgeDocumentRequest request) {
        document.setTitle(request.title());
        document.setCategoryId(request.categoryId());
        document.setContent(request.content());
        document.setEnabled(request.enabled() != null && request.enabled() == 1 ? 1 : 0);
    }

    private List<String> splitText(String text) {
        Integer configuredChunkSize = agentToolProperties.getRag().getMilvus().getChunkSize();
        Integer configuredOverlap = agentToolProperties.getRag().getMilvus().getChunkOverlap();
        int chunkSize = Math.max(configuredChunkSize == null ? 800 : configuredChunkSize, 200);
        int overlap = Math.min(Math.max(configuredOverlap == null ? 120 : configuredOverlap, 0), chunkSize / 2);
        if (text.length() <= chunkSize) {
            return List.of(text);
        }
        java.util.ArrayList<String> chunks = new java.util.ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + chunkSize, text.length());
            chunks.add(text.substring(start, end));
            if (end == text.length()) {
                break;
            }
            start = Math.max(end - overlap, start + 1);
        }
        return chunks;
    }

    private Set<String> tokenize(String query) {
        Set<String> tokens = new HashSet<>();
        Arrays.stream(query.toLowerCase().split("[\\s,.;，。；、]+"))
                .filter(StringUtils::hasText)
                .forEach(tokens::add);
        for (int i = 0; i < query.length() - 1; i++) {
            tokens.add(query.substring(i, i + 2).toLowerCase());
        }
        return tokens;
    }

    private double score(String content, Set<String> keywords) {
        String normalized = content.toLowerCase();
        return keywords.stream().filter(Objects::nonNull).filter(normalized::contains).count();
    }

    private Map<Long, Long> countChunks(List<Long> documentIds) {
        if (documentIds.isEmpty()) {
            return Map.of();
        }
        return chunkMapper.selectList(new LambdaQueryWrapper<RagKnowledgeChunk>()
                        .in(RagKnowledgeChunk::getDocumentId, documentIds)
                        .eq(RagKnowledgeChunk::getDeleted, 0))
                .stream()
                .collect(Collectors.groupingBy(RagKnowledgeChunk::getDocumentId, Collectors.counting()));
    }

    private KnowledgeDocumentResponse toResponse(RagKnowledgeDocument document, int chunkCount) {
        return new KnowledgeDocumentResponse(document.getId(), document.getTitle(), document.getCategoryId(),
                document.getContent(), document.getEnabled(), chunkCount, document.getSource(), document.getStandardNo(),
                document.getDocVersion(), document.getEffectiveDate(), document.getDocType(), document.getImportBatch(),
                document.getCreatedAt(), document.getUpdatedAt());
    }
}
