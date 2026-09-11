SET NAMES utf8mb4;
USE campus_repair;

-- 公告有效期：生效时间 / 过期时间。两者均可为空（为空表示不限制）：
--   effective_at 为空 → 立即生效；expire_at 为空 → 永不过期
-- 学生端列表只返回「已发布 + 已生效 + 未过期」的公告；管理端始终可见并返回状态。
ALTER TABLE repair_notice
  ADD COLUMN effective_at DATETIME NULL COMMENT '生效时间，空表示立即生效' AFTER sort_order,
  ADD COLUMN expire_at DATETIME NULL COMMENT '过期时间，空表示永不过期' AFTER effective_at;

CREATE INDEX idx_repair_notice_validity ON repair_notice (published, effective_at, expire_at);
