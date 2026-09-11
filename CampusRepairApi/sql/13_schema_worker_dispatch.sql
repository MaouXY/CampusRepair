SET NAMES utf8mb4;
USE campus_repair;

CREATE TABLE IF NOT EXISTS worker_profile (
  worker_id BIGINT PRIMARY KEY COMMENT '维修员用户ID',
  department_name VARCHAR(64) NOT NULL COMMENT '所属部门',
  skill_tags JSON NOT NULL COMMENT '技能标签',
  dispatch_enabled TINYINT NOT NULL DEFAULT 1 COMMENT '是否参与智能派单',
  max_active_orders INT NOT NULL DEFAULT 5 COMMENT '最大活跃工单数',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
  KEY idx_worker_profile_dispatch (dispatch_enabled, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='维修员派单画像';

CREATE TABLE IF NOT EXISTS worker_dispatch_score_snapshot (
  id BIGINT PRIMARY KEY COMMENT '评分快照ID',
  ticket_id BIGINT NOT NULL COMMENT '工单ID',
  ai_analysis_id BIGINT NULL COMMENT 'AI分析ID',
  worker_id BIGINT NOT NULL COMMENT '维修员ID',
  worker_name VARCHAR(64) NOT NULL COMMENT '维修员姓名',
  department_name VARCHAR(64) NULL COMMENT '所属部门',
  skill_tags JSON NULL COMMENT '技能标签',
  active_order_count INT NOT NULL DEFAULT 0 COMMENT '当前活跃工单数',
  max_active_orders INT NOT NULL DEFAULT 5 COMMENT '最大活跃工单数',
  skill_score DECIMAL(5,2) NOT NULL DEFAULT 0 COMMENT '技能匹配分',
  department_score DECIMAL(5,2) NOT NULL DEFAULT 0 COMMENT '部门匹配分',
  workload_score DECIMAL(5,2) NOT NULL DEFAULT 0 COMMENT '负载分',
  quality_score DECIMAL(5,2) NOT NULL DEFAULT 0 COMMENT '历史质量分',
  penalty_score DECIMAL(5,2) NOT NULL DEFAULT 0 COMMENT '返工退回扣分',
  total_score DECIMAL(5,2) NOT NULL DEFAULT 0 COMMENT '总分',
  rule_reason VARCHAR(1000) NULL COMMENT '规则评分原因',
  ai_recommended TINYINT NOT NULL DEFAULT 0 COMMENT '是否AI最终推荐',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  KEY idx_worker_dispatch_score_ticket (ticket_id, created_at),
  KEY idx_worker_dispatch_score_analysis (ai_analysis_id),
  KEY idx_worker_dispatch_score_worker (worker_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI派单候选评分快照';
