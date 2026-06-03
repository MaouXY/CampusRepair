USE campus_repair;

CREATE TABLE IF NOT EXISTS file_metadata (
  id BIGINT PRIMARY KEY COMMENT '文件ID',
  original_name VARCHAR(255) NOT NULL COMMENT '原始文件名',
  object_key VARCHAR(512) NOT NULL COMMENT '对象存储Key',
  bucket_name VARCHAR(128) NOT NULL COMMENT '存储桶',
  content_type VARCHAR(128) NOT NULL COMMENT '文件类型',
  size_bytes BIGINT NOT NULL COMMENT '文件大小',
  uploader_id BIGINT NOT NULL COMMENT '上传用户ID',
  uploader_role VARCHAR(32) NOT NULL COMMENT '上传用户角色',
  biz_type VARCHAR(64) NOT NULL DEFAULT 'TEMP' COMMENT '业务类型',
  biz_id BIGINT NULL COMMENT '业务主键',
  public_url VARCHAR(1024) NULL COMMENT '对象访问URL占位',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
  UNIQUE KEY uk_file_metadata_object_key (object_key),
  KEY idx_file_metadata_biz (biz_type, biz_id),
  KEY idx_file_metadata_uploader (uploader_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文件元数据';
