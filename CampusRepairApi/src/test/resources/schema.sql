DROP TABLE IF EXISTS rag_eval_case_result;
DROP TABLE IF EXISTS rag_eval_run;
DROP TABLE IF EXISTS rag_eval_case;
DROP TABLE IF EXISTS rag_knowledge_draft;
DROP TABLE IF EXISTS worker_dispatch_score_snapshot;
DROP TABLE IF EXISTS worker_profile;
DROP TABLE IF EXISTS rag_knowledge_chunk;
DROP TABLE IF EXISTS rag_knowledge_document;
DROP TABLE IF EXISTS repair_ai_analysis;
DROP TABLE IF EXISTS ai_task_record;
DROP TABLE IF EXISTS file_metadata;
DROP TABLE IF EXISTS repair_notice;
DROP TABLE IF EXISTS operation_audit_log;
DROP TABLE IF EXISTS repair_evaluation;
DROP TABLE IF EXISTS repair_assignment;
DROP TABLE IF EXISTS repair_ticket_flow;
DROP TABLE IF EXISTS repair_ticket;
DROP TABLE IF EXISTS repair_location;
DROP TABLE IF EXISTS repair_category;
DROP TABLE IF EXISTS user_account;

CREATE TABLE user_account (
  id BIGINT PRIMARY KEY,
  username VARCHAR(64) NOT NULL,
  password_hash VARCHAR(255) NOT NULL,
  real_name VARCHAR(64) NOT NULL,
  phone VARCHAR(32),
  role_code VARCHAR(32) NOT NULL,
  enabled TINYINT NOT NULL DEFAULT 1,
  last_login_at TIMESTAMP,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted TINYINT NOT NULL DEFAULT 0
);

CREATE TABLE repair_category (
  id BIGINT PRIMARY KEY,
  name VARCHAR(64) NOT NULL,
  sort_order INT NOT NULL DEFAULT 0,
  enabled TINYINT NOT NULL DEFAULT 1,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted TINYINT NOT NULL DEFAULT 0
);

CREATE TABLE repair_location (
  id BIGINT PRIMARY KEY,
  parent_id BIGINT,
  name VARCHAR(64) NOT NULL,
  sort_order INT NOT NULL DEFAULT 0,
  enabled TINYINT NOT NULL DEFAULT 1,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted TINYINT NOT NULL DEFAULT 0
);

CREATE TABLE repair_ticket (
  id BIGINT PRIMARY KEY,
  student_id BIGINT NOT NULL,
  location_id BIGINT NOT NULL,
  category_id BIGINT NOT NULL,
  description CLOB NOT NULL,
  contact_phone VARCHAR(32) NOT NULL,
  summary VARCHAR(200) NOT NULL,
  priority VARCHAR(20) NOT NULL DEFAULT 'LOW',
  status VARCHAR(32) NOT NULL DEFAULT 'PENDING_REVIEW',
  assigned_worker_id BIGINT,
  assigned_admin_id BIGINT,
  assigned_at TIMESTAMP,
  reject_reason VARCHAR(500),
  return_reason VARCHAR(500),
  process_result VARCHAR(500),
  process_remark VARCHAR(1000),
  processed_at TIMESTAMP,
  sla_deadline_at TIMESTAMP,
  urged_at TIMESTAMP,
  urged_by BIGINT,
  urge_remark VARCHAR(500),
  report_image_urls CLOB,
  result_image_urls CLOB,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted TINYINT NOT NULL DEFAULT 0
);

CREATE TABLE repair_ticket_flow (
  id BIGINT PRIMARY KEY,
  ticket_id BIGINT NOT NULL,
  from_status VARCHAR(32),
  to_status VARCHAR(32) NOT NULL,
  operator_id BIGINT NOT NULL,
  operator_role VARCHAR(32) NOT NULL,
  action VARCHAR(64) NOT NULL,
  remark VARCHAR(500),
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE repair_assignment (
  id BIGINT PRIMARY KEY,
  ticket_id BIGINT NOT NULL,
  admin_id BIGINT NOT NULL,
  worker_id BIGINT NOT NULL,
  remark VARCHAR(500),
  assigned_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE repair_evaluation (
  id BIGINT PRIMARY KEY,
  ticket_id BIGINT NOT NULL,
  student_id BIGINT NOT NULL,
  worker_id BIGINT NOT NULL,
  score TINYINT NOT NULL,
  content VARCHAR(500),
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE operation_audit_log (
  id BIGINT PRIMARY KEY,
  operator_id BIGINT NOT NULL,
  operator_role VARCHAR(32) NOT NULL,
  biz_type VARCHAR(64) NOT NULL,
  biz_id BIGINT NOT NULL,
  action VARCHAR(64) NOT NULL,
  before_snapshot CLOB,
  after_snapshot CLOB,
  remark VARCHAR(1000),
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE repair_notice (
  id BIGINT PRIMARY KEY,
  title VARCHAR(120) NOT NULL,
  content CLOB NOT NULL,
  target_role VARCHAR(32) NOT NULL DEFAULT 'ALL',
  published TINYINT NOT NULL DEFAULT 1,
  sort_order INT NOT NULL DEFAULT 0,
  created_by BIGINT NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted TINYINT NOT NULL DEFAULT 0
);

CREATE TABLE file_metadata (
  id BIGINT PRIMARY KEY,
  original_name VARCHAR(255) NOT NULL,
  object_key VARCHAR(512) NOT NULL,
  bucket_name VARCHAR(128) NOT NULL,
  content_type VARCHAR(128) NOT NULL,
  size_bytes BIGINT NOT NULL,
  uploader_id BIGINT NOT NULL,
  uploader_role VARCHAR(32) NOT NULL,
  biz_type VARCHAR(64) NOT NULL DEFAULT 'TEMP',
  biz_id BIGINT,
  public_url VARCHAR(1024),
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted TINYINT NOT NULL DEFAULT 0
);

CREATE TABLE ai_task_record (
  id BIGINT PRIMARY KEY,
  organization_id BIGINT,
  task_type VARCHAR(64) NOT NULL,
  biz_type VARCHAR(64) NOT NULL,
  biz_id BIGINT NOT NULL,
  model_name VARCHAR(128),
  request_snapshot CLOB,
  response_snapshot CLOB,
  status VARCHAR(32) NOT NULL,
  error_message CLOB,
  duration_ms BIGINT,
  prompt_tokens INT,
  input_tokens INT,
  output_tokens INT,
  total_tokens INT,
  token_source VARCHAR(16),
  degrade_level VARCHAR(32),
  degrade_reason VARCHAR(500),
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted TINYINT NOT NULL DEFAULT 0
);

CREATE TABLE repair_ai_analysis (
  id BIGINT PRIMARY KEY,
  ticket_id BIGINT NOT NULL,
  ai_task_id BIGINT,
  status VARCHAR(32) NOT NULL,
  suggested_category_id BIGINT,
  suggested_priority VARCHAR(20),
  suggested_worker_id BIGINT,
  fault_summary VARCHAR(200),
  fault_reason VARCHAR(500),
  solution VARCHAR(1000),
  dispatch_remark VARCHAR(500),
  risk_level VARCHAR(32),
  confidence DECIMAL(5,2),
  raw_response CLOB,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted TINYINT NOT NULL DEFAULT 0
);

CREATE TABLE worker_profile (
  worker_id BIGINT PRIMARY KEY,
  department_name VARCHAR(64) NOT NULL,
  skill_tags CLOB NOT NULL,
  dispatch_enabled TINYINT NOT NULL DEFAULT 1,
  max_active_orders INT NOT NULL DEFAULT 5,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted TINYINT NOT NULL DEFAULT 0
);

CREATE TABLE worker_dispatch_score_snapshot (
  id BIGINT PRIMARY KEY,
  ticket_id BIGINT NOT NULL,
  ai_analysis_id BIGINT,
  worker_id BIGINT NOT NULL,
  worker_name VARCHAR(64) NOT NULL,
  department_name VARCHAR(64),
  skill_tags CLOB,
  active_order_count INT NOT NULL DEFAULT 0,
  max_active_orders INT NOT NULL DEFAULT 5,
  skill_score DECIMAL(5,2) NOT NULL DEFAULT 0,
  department_score DECIMAL(5,2) NOT NULL DEFAULT 0,
  workload_score DECIMAL(5,2) NOT NULL DEFAULT 0,
  quality_score DECIMAL(5,2) NOT NULL DEFAULT 0,
  penalty_score DECIMAL(5,2) NOT NULL DEFAULT 0,
  total_score DECIMAL(5,2) NOT NULL DEFAULT 0,
  rule_reason VARCHAR(1000),
  ai_recommended TINYINT NOT NULL DEFAULT 0,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE rag_knowledge_document (
  id BIGINT PRIMARY KEY,
  title VARCHAR(160) NOT NULL,
  category_id BIGINT,
  content CLOB NOT NULL,
  enabled TINYINT NOT NULL DEFAULT 1,
  created_by BIGINT NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted TINYINT NOT NULL DEFAULT 0
);

CREATE TABLE rag_knowledge_chunk (
  id BIGINT PRIMARY KEY,
  document_id BIGINT NOT NULL,
  chunk_index INT NOT NULL,
  content CLOB NOT NULL,
  token_count INT NOT NULL DEFAULT 0,
  embedding_provider VARCHAR(64) NOT NULL DEFAULT 'LOCAL_HASH',
  vector_store_status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
  enabled TINYINT NOT NULL DEFAULT 1,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted TINYINT NOT NULL DEFAULT 0
);

CREATE TABLE rag_knowledge_draft (
  id BIGINT PRIMARY KEY,
  source_ticket_id BIGINT,
  title VARCHAR(160) NOT NULL,
  content CLOB NOT NULL,
  category_id BIGINT,
  status VARCHAR(32) NOT NULL DEFAULT 'PENDING_REVIEW',
  created_by_ai TINYINT NOT NULL DEFAULT 1,
  generate_source VARCHAR(32) NOT NULL DEFAULT 'AI',
  review_remark VARCHAR(500),
  reviewed_by BIGINT,
  reviewed_at TIMESTAMP,
  knowledge_document_id BIGINT,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted TINYINT NOT NULL DEFAULT 0
);

CREATE TABLE rag_eval_case (
  id BIGINT PRIMARY KEY,
  dataset_name VARCHAR(64) NOT NULL,
  question VARCHAR(500) NOT NULL,
  expected_doc_ids CLOB,
  expected_keywords CLOB,
  answerable TINYINT NOT NULL DEFAULT 1,
  task_type VARCHAR(32) NOT NULL DEFAULT 'factual',
  category_id BIGINT,
  source VARCHAR(64),
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted TINYINT NOT NULL DEFAULT 0
);

CREATE TABLE rag_eval_run (
  id BIGINT PRIMARY KEY,
  dataset_name VARCHAR(64) NOT NULL,
  top_k INT NOT NULL DEFAULT 5,
  case_count INT NOT NULL DEFAULT 0,
  answerable_case_count INT NOT NULL DEFAULT 0,
  unanswerable_case_count INT NOT NULL DEFAULT 0,
  hit_rate DECIMAL(6,4) NOT NULL DEFAULT 0,
  recall_at_k DECIMAL(6,4) NOT NULL DEFAULT 0,
  precision_at_k DECIMAL(6,4) NOT NULL DEFAULT 0,
  mrr DECIMAL(6,4) NOT NULL DEFAULT 0,
  ndcg_at_k DECIMAL(6,4) NOT NULL DEFAULT 0,
  refusal_accuracy DECIMAL(6,4) NOT NULL DEFAULT 0,
  avg_latency_ms BIGINT NOT NULL DEFAULT 0,
  status VARCHAR(32) NOT NULL DEFAULT 'RUNNING',
  error_message VARCHAR(500),
  triggered_by BIGINT,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  finished_at TIMESTAMP
);

CREATE TABLE rag_eval_case_result (
  id BIGINT PRIMARY KEY,
  run_id BIGINT NOT NULL,
  case_id BIGINT NOT NULL,
  question VARCHAR(500) NOT NULL,
  hit TINYINT NOT NULL DEFAULT 0,
  first_relevant_rank INT,
  recall DECIMAL(6,4) NOT NULL DEFAULT 0,
  precision_score DECIMAL(6,4) NOT NULL DEFAULT 0,
  reciprocal_rank DECIMAL(6,4) NOT NULL DEFAULT 0,
  ndcg DECIMAL(6,4) NOT NULL DEFAULT 0,
  latency_ms BIGINT NOT NULL DEFAULT 0,
  retrieved_chunks CLOB,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
