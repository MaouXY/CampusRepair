SET NAMES utf8mb4;
USE campus_repair;

-- 语料导入元数据：让知识库能标注来源、标准号、版本、生效日期与章节，便于引用与追溯
ALTER TABLE rag_knowledge_document
  ADD COLUMN source VARCHAR(128) NULL COMMENT '语料来源（标准/手册/校内制度名）' AFTER category_id,
  ADD COLUMN standard_no VARCHAR(64) NULL COMMENT '标准号或文号' AFTER source,
  ADD COLUMN doc_version VARCHAR(32) NULL COMMENT '版本/年版' AFTER standard_no,
  ADD COLUMN effective_date DATE NULL COMMENT '生效日期' AFTER doc_version,
  ADD COLUMN doc_type VARCHAR(32) NULL COMMENT 'standard/manual/policy/case' AFTER effective_date,
  ADD COLUMN import_batch VARCHAR(64) NULL COMMENT '导入批次' AFTER doc_type;

ALTER TABLE rag_knowledge_chunk
  ADD COLUMN section_title VARCHAR(200) NULL COMMENT '所属章节标题（用于检索引用）' AFTER content;

CREATE INDEX idx_rag_document_standard_no ON rag_knowledge_document (standard_no);
CREATE INDEX idx_rag_document_import_batch ON rag_knowledge_document (import_batch);
