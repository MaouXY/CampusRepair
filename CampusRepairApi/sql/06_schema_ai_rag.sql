USE campus_repair;

CREATE TABLE IF NOT EXISTS ai_task_record (
  id BIGINT PRIMARY KEY COMMENT 'AI任务ID',
  organization_id BIGINT NULL COMMENT '组织ID占位',
  task_type VARCHAR(64) NOT NULL COMMENT '任务类型',
  biz_type VARCHAR(64) NOT NULL COMMENT '业务类型',
  biz_id BIGINT NOT NULL COMMENT '业务主键',
  model_name VARCHAR(128) NULL COMMENT '模型名称',
  request_snapshot TEXT NULL COMMENT '请求快照',
  response_snapshot TEXT NULL COMMENT '响应快照',
  status VARCHAR(32) NOT NULL COMMENT '状态',
  error_message TEXT NULL COMMENT '错误信息',
  duration_ms BIGINT NULL COMMENT '耗时毫秒',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
  KEY idx_ai_task_biz (task_type, biz_type, biz_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI调用任务记录';

CREATE TABLE IF NOT EXISTS repair_ai_analysis (
  id BIGINT PRIMARY KEY COMMENT '分析ID',
  ticket_id BIGINT NOT NULL COMMENT '工单ID',
  ai_task_id BIGINT NULL COMMENT 'AI任务ID',
  status VARCHAR(32) NOT NULL COMMENT '状态：SUCCESS/FAILED/FALLBACK',
  suggested_category_id BIGINT NULL COMMENT '建议分类ID',
  suggested_priority VARCHAR(20) NULL COMMENT '建议优先级',
  fault_summary VARCHAR(200) NULL COMMENT '故障摘要',
  fault_reason VARCHAR(500) NULL COMMENT '可能原因',
  solution VARCHAR(1000) NULL COMMENT '建议方案',
  risk_level VARCHAR(32) NULL COMMENT '风险等级',
  confidence DECIMAL(5,2) NULL COMMENT '置信度',
  raw_response TEXT NULL COMMENT '原始响应',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
  KEY idx_repair_ai_analysis_ticket (ticket_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工单AI预分析';

CREATE TABLE IF NOT EXISTS rag_knowledge_document (
  id BIGINT PRIMARY KEY COMMENT '知识文档ID',
  title VARCHAR(160) NOT NULL COMMENT '标题',
  category_id BIGINT NULL COMMENT '关联分类ID',
  content MEDIUMTEXT NOT NULL COMMENT '文档内容',
  enabled TINYINT NOT NULL DEFAULT 1 COMMENT '是否启用',
  created_by BIGINT NOT NULL COMMENT '创建管理员ID',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
  KEY idx_rag_document_category (category_id, enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='RAG知识文档';

CREATE TABLE IF NOT EXISTS rag_knowledge_chunk (
  id BIGINT PRIMARY KEY COMMENT '知识片段ID',
  document_id BIGINT NOT NULL COMMENT '文档ID',
  chunk_index INT NOT NULL COMMENT '片段序号',
  content TEXT NOT NULL COMMENT '片段内容',
  token_count INT NOT NULL DEFAULT 0 COMMENT '估算Token数',
  embedding_provider VARCHAR(64) NOT NULL DEFAULT 'LOCAL_HASH' COMMENT 'Embedding提供方',
  vector_store_status VARCHAR(32) NOT NULL DEFAULT 'PENDING' COMMENT '向量库状态：PENDING/SYNCED/FAILED',
  enabled TINYINT NOT NULL DEFAULT 1 COMMENT '是否启用',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
  UNIQUE KEY uk_rag_chunk_doc_index (document_id, chunk_index),
  KEY idx_rag_chunk_document (document_id, enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='RAG知识片段';
