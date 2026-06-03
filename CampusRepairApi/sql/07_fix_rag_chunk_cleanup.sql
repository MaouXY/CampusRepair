USE campus_repair;

-- RAG chunk 是由知识文档内容重建出来的派生数据。
-- 旧版本使用逻辑删除会继续占用 (document_id, chunk_index) 唯一键，
-- 导致同一知识文档再次保存或重建时插入第 0 个分块报 DuplicateKeyException。
DELETE FROM rag_knowledge_chunk
WHERE deleted = 1;
