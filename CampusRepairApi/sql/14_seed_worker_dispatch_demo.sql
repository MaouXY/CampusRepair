SET NAMES utf8mb4;
USE campus_repair;

-- Execute after 13_schema_worker_dispatch.sql.

INSERT INTO worker_profile (worker_id, department_name, skill_tags, dispatch_enabled, max_active_orders)
VALUES
  (10002, '综合维修组', JSON_ARRAY('门窗', '公共设施'), 1, 4),
  (12004, '水电组', JSON_ARRAY('水电', '公共设施'), 1, 3),
  (12005, '空调照明组', JSON_ARRAY('空调', '照明', '公共设施'), 1, 5)
ON DUPLICATE KEY UPDATE
  department_name = VALUES(department_name),
  skill_tags = VALUES(skill_tags),
  dispatch_enabled = VALUES(dispatch_enabled),
  max_active_orders = VALUES(max_active_orders),
  deleted = 0;

UPDATE repair_ticket
SET process_result = NULL,
    process_remark = NULL,
    processed_at = NULL
WHERE id = 120004
  AND status = 'PROCESSING';
