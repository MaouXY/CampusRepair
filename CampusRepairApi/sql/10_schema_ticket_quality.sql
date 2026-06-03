USE campus_repair;

SET @schema_name = DATABASE();

SET @column_exists = (
  SELECT COUNT(*)
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = @schema_name
    AND TABLE_NAME = 'repair_ticket'
    AND COLUMN_NAME = 'return_reason'
);
SET @sql = IF(
  @column_exists = 0,
  'ALTER TABLE repair_ticket ADD COLUMN return_reason VARCHAR(500) NULL COMMENT ''维修员退回原因'' AFTER reject_reason',
  'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @column_exists = (
  SELECT COUNT(*)
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = @schema_name
    AND TABLE_NAME = 'repair_ticket'
    AND COLUMN_NAME = 'sla_deadline_at'
);
SET @sql = IF(
  @column_exists = 0,
  'ALTER TABLE repair_ticket ADD COLUMN sla_deadline_at DATETIME NULL COMMENT ''SLA截止时间'' AFTER processed_at',
  'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @column_exists = (
  SELECT COUNT(*)
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = @schema_name
    AND TABLE_NAME = 'repair_ticket'
    AND COLUMN_NAME = 'urged_at'
);
SET @sql = IF(
  @column_exists = 0,
  'ALTER TABLE repair_ticket ADD COLUMN urged_at DATETIME NULL COMMENT ''最近督办时间'' AFTER sla_deadline_at',
  'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @column_exists = (
  SELECT COUNT(*)
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = @schema_name
    AND TABLE_NAME = 'repair_ticket'
    AND COLUMN_NAME = 'urged_by'
);
SET @sql = IF(
  @column_exists = 0,
  'ALTER TABLE repair_ticket ADD COLUMN urged_by BIGINT NULL COMMENT ''最近督办管理员ID'' AFTER urged_at',
  'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @column_exists = (
  SELECT COUNT(*)
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = @schema_name
    AND TABLE_NAME = 'repair_ticket'
    AND COLUMN_NAME = 'urge_remark'
);
SET @sql = IF(
  @column_exists = 0,
  'ALTER TABLE repair_ticket ADD COLUMN urge_remark VARCHAR(500) NULL COMMENT ''最近督办说明'' AFTER urged_by',
  'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @index_exists = (
  SELECT COUNT(*)
  FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = @schema_name
    AND TABLE_NAME = 'repair_ticket'
    AND INDEX_NAME = 'idx_repair_ticket_sla_deadline'
);
SET @sql = IF(
  @index_exists = 0,
  'ALTER TABLE repair_ticket ADD INDEX idx_repair_ticket_sla_deadline (sla_deadline_at)',
  'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

UPDATE repair_ticket
SET sla_deadline_at = CASE priority
  WHEN 'HIGH' THEN DATE_ADD(COALESCE(assigned_at, created_at), INTERVAL 24 HOUR)
  WHEN 'MEDIUM' THEN DATE_ADD(COALESCE(assigned_at, created_at), INTERVAL 48 HOUR)
  ELSE DATE_ADD(COALESCE(assigned_at, created_at), INTERVAL 72 HOUR)
END
WHERE sla_deadline_at IS NULL
  AND deleted = 0;
