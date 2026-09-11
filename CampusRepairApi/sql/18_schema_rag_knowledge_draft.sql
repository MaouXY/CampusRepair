SET NAMES utf8mb4;
USE campus_repair;

-- 第三阶段 V3：典型工单自动沉淀为知识库草稿，管理员审核后入库
CREATE TABLE IF NOT EXISTS rag_knowledge_draft (
  id BIGINT PRIMARY KEY COMMENT '知识草稿ID',
  source_ticket_id BIGINT NULL COMMENT '来源工单ID',
  title VARCHAR(160) NOT NULL COMMENT '草稿标题',
  content TEXT NOT NULL COMMENT '草稿正文',
  category_id BIGINT NULL COMMENT '关联分类',
  status VARCHAR(32) NOT NULL DEFAULT 'PENDING_REVIEW' COMMENT 'PENDING_REVIEW/APPROVED/REJECTED',
  created_by_ai TINYINT NOT NULL DEFAULT 1 COMMENT '1=AI生成，0=规则模板兜底',
  generate_source VARCHAR(32) NOT NULL DEFAULT 'AI' COMMENT 'AI/RULE/AI_DEGRADED',
  review_remark VARCHAR(500) NULL COMMENT '审核意见',
  reviewed_by BIGINT NULL COMMENT '审核人ID',
  reviewed_at DATETIME NULL COMMENT '审核时间',
  knowledge_document_id BIGINT NULL COMMENT '入库后的知识文档ID',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
  KEY idx_rag_draft_status (status, deleted, created_at),
  KEY idx_rag_draft_ticket (source_ticket_id, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='典型工单知识沉淀草稿';
