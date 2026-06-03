USE campus_repair;

CREATE TABLE IF NOT EXISTS repair_ticket (
  id BIGINT PRIMARY KEY COMMENT '工单ID',
  student_id BIGINT NOT NULL COMMENT '报修学生ID',
  location_id BIGINT NOT NULL COMMENT '报修地点ID',
  category_id BIGINT NOT NULL COMMENT '报修分类ID',
  description TEXT NOT NULL COMMENT '故障描述',
  contact_phone VARCHAR(32) NOT NULL COMMENT '联系电话',
  summary VARCHAR(200) NOT NULL COMMENT '工单摘要',
  priority VARCHAR(20) NOT NULL DEFAULT 'LOW' COMMENT '优先级：HIGH/MEDIUM/LOW',
  status VARCHAR(32) NOT NULL DEFAULT 'PENDING_REVIEW' COMMENT '状态',
  assigned_worker_id BIGINT NULL COMMENT '派单维修员ID',
  assigned_admin_id BIGINT NULL COMMENT '派单管理员ID',
  assigned_at DATETIME NULL COMMENT '派单时间',
  reject_reason VARCHAR(500) NULL COMMENT '驳回原因',
  process_result VARCHAR(500) NULL COMMENT '处理结果',
  process_remark VARCHAR(1000) NULL COMMENT '处理备注',
  processed_at DATETIME NULL COMMENT '处理提交时间',
  report_image_urls JSON NULL COMMENT '报修图片占位',
  result_image_urls JSON NULL COMMENT '维修结果图片占位',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
  KEY idx_repair_ticket_student (student_id),
  KEY idx_repair_ticket_worker (assigned_worker_id),
  KEY idx_repair_ticket_status (status),
  KEY idx_repair_ticket_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='报修工单';

CREATE TABLE IF NOT EXISTS repair_ticket_flow (
  id BIGINT PRIMARY KEY COMMENT '流转记录ID',
  ticket_id BIGINT NOT NULL COMMENT '工单ID',
  from_status VARCHAR(32) NULL COMMENT '流转前状态',
  to_status VARCHAR(32) NOT NULL COMMENT '流转后状态',
  operator_id BIGINT NOT NULL COMMENT '操作人ID',
  operator_role VARCHAR(32) NOT NULL COMMENT '操作人角色',
  action VARCHAR(64) NOT NULL COMMENT '操作动作',
  remark VARCHAR(500) NULL COMMENT '操作说明',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  KEY idx_repair_ticket_flow_ticket (ticket_id),
  KEY idx_repair_ticket_flow_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工单流转记录';

CREATE TABLE IF NOT EXISTS repair_assignment (
  id BIGINT PRIMARY KEY COMMENT '派单记录ID',
  ticket_id BIGINT NOT NULL COMMENT '工单ID',
  admin_id BIGINT NOT NULL COMMENT '管理员ID',
  worker_id BIGINT NOT NULL COMMENT '维修员ID',
  remark VARCHAR(500) NULL COMMENT '派单备注',
  assigned_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '派单时间',
  KEY idx_repair_assignment_ticket (ticket_id),
  KEY idx_repair_assignment_worker (worker_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工单派单记录';

CREATE TABLE IF NOT EXISTS repair_evaluation (
  id BIGINT PRIMARY KEY COMMENT '评价ID',
  ticket_id BIGINT NOT NULL COMMENT '工单ID',
  student_id BIGINT NOT NULL COMMENT '学生ID',
  worker_id BIGINT NOT NULL COMMENT '维修员ID',
  score TINYINT NOT NULL COMMENT '评分：1-5',
  content VARCHAR(500) NULL COMMENT '评价内容',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  UNIQUE KEY uk_repair_evaluation_ticket (ticket_id),
  KEY idx_repair_evaluation_worker (worker_id),
  KEY idx_repair_evaluation_student (student_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='维修评价';
