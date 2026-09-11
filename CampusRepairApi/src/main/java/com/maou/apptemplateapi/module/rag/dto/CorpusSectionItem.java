package com.maou.apptemplateapi.module.rag.dto;

import java.time.LocalDate;

/**
 * 批量导入的单条记录，字段与 `tools/corpus/convert_corpus.py` 输出的 JSONL 完全对齐：
 * 一条记录 = 一个章节。
 */
public record CorpusSectionItem(
        String title,
        String source,
        String standardNo,
        String docVersion,
        LocalDate effectiveDate,
        String docType,
        Long categoryId,
        String sectionTitle,
        Integer sectionLevel,
        String content
) {
}
