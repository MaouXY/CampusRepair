USE campus_repair;

CREATE TABLE IF NOT EXISTS repair_notice (
  id BIGINT PRIMARY KEY COMMENT '公告ID',
  title VARCHAR(120) NOT NULL COMMENT '公告标题',
  content TEXT NOT NULL COMMENT '公告内容',
  target_role VARCHAR(32) NOT NULL DEFAULT 'ALL' COMMENT '可见角色：ALL/STUDENT/WORKER/ADMIN',
  published TINYINT NOT NULL DEFAULT 1 COMMENT '是否发布',
  sort_order INT NOT NULL DEFAULT 0 COMMENT '排序',
  created_by BIGINT NOT NULL COMMENT '创建管理员ID',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
  KEY idx_repair_notice_target (target_role, published),
  KEY idx_repair_notice_sort (sort_order, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统公告';
