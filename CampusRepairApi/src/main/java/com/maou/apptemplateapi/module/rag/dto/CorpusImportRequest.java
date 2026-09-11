package com.maou.apptemplateapi.module.rag.dto;

import java.time.LocalDate;

/**
 * 单篇语料导入：content 传整篇原文，后端负责清洗 + 章节切分。
 */
public record CorpusImportRequest(
        String title,
        Long categoryId,
        String source,
        String standardNo,
        String docVersion,
        LocalDate effectiveDate,
        String docType,
        String content,
        Integer chunkSize,
        Integer chunkOverlap,
        Integer enabled,
        String importBatch
) {
}
