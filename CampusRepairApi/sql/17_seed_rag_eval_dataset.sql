SET NAMES utf8mb4;
USE campus_repair;

-- 内置中文评测集（8 条：7 条可回答 + 1 条不可回答/拒答），用于零成本回归对比。
-- 相关性判定：优先按 expected_doc_ids（文档级）；为空时按 expected_keywords（片段级，正文包含关键词即相关）。
-- 可扩展为从开源评测集导入，见 doc/10_RAG评测与混合检索说明.md。
INSERT INTO rag_eval_case (
  id, dataset_name, question, expected_doc_ids, expected_keywords, answerable, task_type, category_id, source
)
VALUES
  (70001, 'campus-repair-builtin', '空调不制冷应该按什么顺序排查？', '[]', '["空调", "不制冷", "滤网"]', 1, 'factual', NULL, 'builtin'),
  (70002, 'campus-repair-builtin', '水龙头漏水现场一般先做什么处理？', '[]', '["水龙头", "漏水", "角阀"]', 1, 'factual', NULL, 'builtin'),
  (70003, 'campus-repair-builtin', '教室门锁打不开有哪些常见原因？', '[]', '["门锁", "锁芯", "钥匙"]', 1, 'factual', NULL, 'builtin'),
  (70004, 'campus-repair-builtin', '投影仪没有信号时怎么排查？', '[]', '["投影仪", "输入源", "线缆"]', 1, 'factual', NULL, 'builtin'),
  (70005, 'campus-repair-builtin', '发现插座发黑、有焦味还能继续使用吗？', '[]', '["插座", "发黑", "焦味"]', 1, 'factual', NULL, 'builtin'),
  (70006, 'campus-repair-builtin', '哪些报修情况需要按高优先级处理？', '[]', '["漏电", "HIGH", "优先级"]', 1, 'factual', NULL, 'builtin'),
  (70007, 'campus-repair-builtin', '公共设施维修完成后如何完成闭环？', '[]', '["闭环", "确认", "评价"]', 1, 'factual', NULL, 'builtin'),
  (70008, 'campus-repair-builtin', '学校食堂的营业时间是几点到几点？', '[]', '[]', 0, 'unanswerable', NULL, 'builtin')
ON DUPLICATE KEY UPDATE
  question = VALUES(question),
  expected_doc_ids = VALUES(expected_doc_ids),
  expected_keywords = VALUES(expected_keywords),
  answerable = VALUES(answerable),
  task_type = VALUES(task_type),
  source = VALUES(source),
  deleted = 0;
