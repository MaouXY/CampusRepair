SET NAMES utf8mb4;
USE campus_repair;

-- RAG 自动化评测：评测用例 / 评测运行 / 逐条结果
CREATE TABLE IF NOT EXISTS rag_eval_case (
  id BIGINT PRIMARY KEY COMMENT '评测用例ID',
  dataset_name VARCHAR(64) NOT NULL COMMENT '评测集名称',
  question VARCHAR(500) NOT NULL COMMENT '评测问题',
  expected_doc_ids JSON NULL COMMENT '期望命中的知识文档ID数组',
  expected_keywords JSON NULL COMMENT '期望命中片段包含的关键词数组',
  answerable TINYINT NOT NULL DEFAULT 1 COMMENT '1=应召回，0=不可回答（测拒答）',
  task_type VARCHAR(32) NOT NULL DEFAULT 'factual' COMMENT 'factual/multi-hop/counterfactual/unanswerable',
  category_id BIGINT NULL COMMENT '限定分类，可为空',
  source VARCHAR(64) NULL COMMENT 'builtin/imported/manual 或开源数据集名',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
  KEY idx_rag_eval_case_dataset (dataset_name, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='RAG评测用例';

CREATE TABLE IF NOT EXISTS rag_eval_run (
  id BIGINT PRIMARY KEY COMMENT '评测运行ID',
  dataset_name VARCHAR(64) NOT NULL COMMENT '评测集名称',
  top_k INT NOT NULL DEFAULT 5 COMMENT '评测时的召回条数',
  case_count INT NOT NULL DEFAULT 0 COMMENT '用例数',
  answerable_case_count INT NOT NULL DEFAULT 0 COMMENT '可回答用例数',
  unanswerable_case_count INT NOT NULL DEFAULT 0 COMMENT '不可回答用例数',
  hit_rate DECIMAL(6,4) NOT NULL DEFAULT 0 COMMENT '命中率 HitRate',
  recall_at_k DECIMAL(6,4) NOT NULL DEFAULT 0 COMMENT '召回率 Recall@k',
  precision_at_k DECIMAL(6,4) NOT NULL DEFAULT 0 COMMENT '精确率 Precision@k',
  mrr DECIMAL(6,4) NOT NULL DEFAULT 0 COMMENT '平均倒数排名 MRR',
  ndcg_at_k DECIMAL(6,4) NOT NULL DEFAULT 0 COMMENT '归一化折损累计增益 nDCG@k',
  refusal_accuracy DECIMAL(6,4) NOT NULL DEFAULT 0 COMMENT '不可回答用例的拒答准确率',
  avg_latency_ms BIGINT NOT NULL DEFAULT 0 COMMENT '平均检索耗时（毫秒）',
  status VARCHAR(32) NOT NULL DEFAULT 'RUNNING' COMMENT 'RUNNING/SUCCESS/PARTIAL',
  error_message VARCHAR(500) NULL COMMENT '失败原因',
  triggered_by BIGINT NULL COMMENT '触发人ID',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '开始时间',
  finished_at DATETIME NULL COMMENT '结束时间',
  KEY idx_rag_eval_run_dataset (dataset_name, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='RAG评测运行记录';

CREATE TABLE IF NOT EXISTS rag_eval_case_result (
  id BIGINT PRIMARY KEY COMMENT '结果ID',
  run_id BIGINT NOT NULL COMMENT '评测运行ID',
  case_id BIGINT NOT NULL COMMENT '评测用例ID',
  question VARCHAR(500) NOT NULL COMMENT '评测问题',
  hit TINYINT NOT NULL DEFAULT 0 COMMENT '是否命中',
  first_relevant_rank INT NULL COMMENT '首个相关片段排名，0表示未命中',
  recall DECIMAL(6,4) NOT NULL DEFAULT 0 COMMENT '该用例召回率',
  precision_score DECIMAL(6,4) NOT NULL DEFAULT 0 COMMENT '该用例精确率',
  reciprocal_rank DECIMAL(6,4) NOT NULL DEFAULT 0 COMMENT '该用例倒数排名',
  ndcg DECIMAL(6,4) NOT NULL DEFAULT 0 COMMENT '该用例nDCG',
  latency_ms BIGINT NOT NULL DEFAULT 0 COMMENT '检索耗时（毫秒）',
  retrieved_chunks JSON NULL COMMENT '召回片段快照',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  KEY idx_rag_eval_result_run (run_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='RAG评测逐条结果';
