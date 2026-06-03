USE campus_repair;

CREATE TABLE IF NOT EXISTS operation_audit_log (
  id BIGINT PRIMARY KEY COMMENT '审计ID',
  operator_id BIGINT NOT NULL COMMENT '操作人ID',
  operator_role VARCHAR(32) NOT NULL COMMENT '操作人角色',
  biz_type VARCHAR(64) NOT NULL COMMENT '业务类型',
  biz_id BIGINT NOT NULL COMMENT '业务主键',
  action VARCHAR(64) NOT NULL COMMENT '操作动作',
  before_snapshot TEXT NULL COMMENT '操作前快照',
  after_snapshot TEXT NULL COMMENT '操作后快照',
  remark VARCHAR(1000) NULL COMMENT '操作备注',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  KEY idx_operation_audit_biz (biz_type, biz_id, created_at),
  KEY idx_operation_audit_operator (operator_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='操作审计日志';
