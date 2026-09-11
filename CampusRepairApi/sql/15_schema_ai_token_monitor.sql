SET NAMES utf8mb4;
USE campus_repair;

-- AI token 监控与降级兜底：为 AI 任务表补充 token 用量与降级审计字段。
-- 说明：
--   prompt_tokens   调用前的提示词预估 token（本地估算，用于预算判断）
--   input/output/total_tokens  模型接口返回的真实 token 用量；接口未返回时写入估算值
--   token_source    API（接口真实返回）/ ESTIMATED（本地估算）
--   degrade_level   NORMAL / DROP_IMAGES / MINIMAL_CONTEXT / RULE_ONLY
--   degrade_reason  降级原因，便于统计降级率与排障
ALTER TABLE ai_task_record
  ADD COLUMN prompt_tokens INT NULL COMMENT '提示词预估token' AFTER duration_ms,
  ADD COLUMN input_tokens INT NULL COMMENT '输入token' AFTER prompt_tokens,
  ADD COLUMN output_tokens INT NULL COMMENT '输出token' AFTER input_tokens,
  ADD COLUMN total_tokens INT NULL COMMENT '总token' AFTER output_tokens,
  ADD COLUMN token_source VARCHAR(16) NULL COMMENT 'API/ESTIMATED' AFTER total_tokens,
  ADD COLUMN degrade_level VARCHAR(32) NULL COMMENT 'NORMAL/DROP_IMAGES/MINIMAL_CONTEXT/RULE_ONLY' AFTER token_source,
  ADD COLUMN degrade_reason VARCHAR(500) NULL COMMENT '降级原因' AFTER degrade_level;

CREATE INDEX idx_ai_task_record_created_at ON ai_task_record (created_at);
