USE campus_repair;

SET @has_suggested_worker_id := (
  SELECT COUNT(*)
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'repair_ai_analysis'
    AND COLUMN_NAME = 'suggested_worker_id'
);

SET @sql := IF(
  @has_suggested_worker_id = 0,
  'ALTER TABLE repair_ai_analysis ADD COLUMN suggested_worker_id BIGINT NULL COMMENT ''AI建议维修员ID'' AFTER suggested_priority',
  'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @has_dispatch_remark := (
  SELECT COUNT(*)
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'repair_ai_analysis'
    AND COLUMN_NAME = 'dispatch_remark'
);

SET @sql := IF(
  @has_dispatch_remark = 0,
  'ALTER TABLE repair_ai_analysis ADD COLUMN dispatch_remark VARCHAR(500) NULL COMMENT ''AI建议派单备注'' AFTER solution',
  'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
