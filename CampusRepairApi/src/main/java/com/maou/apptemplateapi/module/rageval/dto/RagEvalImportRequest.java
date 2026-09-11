package com.maou.apptemplateapi.module.rageval.dto;

/**
 * 评测集导入。content 支持两种格式：
 * <ul>
 *   <li>JSONL：每行一个 JSON 对象；</li>
 *   <li>JSON 数组：一个对象数组。</li>
 * </ul>
 * 字段别名兼容常见开源评测集（BEIR / RGB / CRUD-RAG 等）：
 * question|query|input、expectedDocIds|gold_doc_ids|relevant_doc_ids、
 * expectedKeywords|expected_keywords|gold_answers、categoryId|category_id、
 * answerable、taskType|task_type、source、datasetName|dataset。
 */
public record RagEvalImportRequest(
        String datasetName,
        String source,
        String content
) {
}
