SET NAMES utf8mb4;
USE campus_repair;

-- 评测用例支持「切片级」语义标注：判据不再依赖问题与文本的字面重合，
-- 这是公平评测向量/混合检索的前提（关键词级判据会天然偏向关键词检索）。
ALTER TABLE rag_eval_case
  ADD COLUMN expected_chunk_ids JSON NULL COMMENT '期望命中的切片ID数组（切片级语义标注）' AFTER expected_doc_ids;
