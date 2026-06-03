USE campus_repair;

INSERT INTO user_account (id, username, password_hash, real_name, phone, role_code, enabled)
VALUES
  (10001, 'student01', '{noop}123456', '张同学', '13800000001', 'STUDENT', 1),
  (10002, 'worker01', '{noop}123456', '李师傅', '13800000002', 'WORKER', 1),
  (10003, 'admin01', '{noop}123456', '管理员', '13800000003', 'ADMIN', 1)
ON DUPLICATE KEY UPDATE
  password_hash = VALUES(password_hash),
  real_name = VALUES(real_name),
  phone = VALUES(phone),
  role_code = VALUES(role_code),
  enabled = VALUES(enabled),
  deleted = 0;

INSERT INTO repair_category (id, name, sort_order, enabled)
VALUES
  (20001, '水电维修', 10, 1),
  (20002, '门窗家具', 20, 1),
  (20003, '网络设备', 30, 1),
  (20004, '空调照明', 40, 1),
  (20005, '公共设施', 50, 1)
ON DUPLICATE KEY UPDATE
  name = VALUES(name),
  sort_order = VALUES(sort_order),
  enabled = VALUES(enabled),
  deleted = 0;

INSERT INTO repair_location (id, parent_id, name, sort_order, enabled)
VALUES
  (30001, NULL, '一号教学楼', 10, 1),
  (30002, 30001, '一号教学楼-101', 11, 1),
  (30003, 30001, '一号教学楼-公共走廊', 12, 1),
  (30004, NULL, '学生宿舍A区', 20, 1),
  (30005, 30004, '学生宿舍A区-2栋', 21, 1),
  (30006, NULL, '图书馆', 30, 1),
  (30007, 30006, '图书馆-自习区', 31, 1)
ON DUPLICATE KEY UPDATE
  parent_id = VALUES(parent_id),
  name = VALUES(name),
  sort_order = VALUES(sort_order),
  enabled = VALUES(enabled),
  deleted = 0;

INSERT INTO repair_ticket (
  id,
  student_id,
  location_id,
  category_id,
  description,
  contact_phone,
  summary,
  priority,
  status,
  assigned_worker_id,
  assigned_admin_id,
  assigned_at,
  report_image_urls,
  result_image_urls
)
VALUES
  (
    40001,
    10001,
    30002,
    20003,
    '一号教学楼101投影仪无法连接电脑，影响上课使用。',
    '13800000001',
    '一号教学楼101投影仪无法连接电脑',
    'MEDIUM',
    'ASSIGNED',
    10002,
    10003,
    CURRENT_TIMESTAMP,
    JSON_ARRAY(),
    JSON_ARRAY()
  )
ON DUPLICATE KEY UPDATE
  student_id = VALUES(student_id),
  location_id = VALUES(location_id),
  category_id = VALUES(category_id),
  description = VALUES(description),
  contact_phone = VALUES(contact_phone),
  summary = VALUES(summary),
  priority = VALUES(priority),
  status = VALUES(status),
  assigned_worker_id = VALUES(assigned_worker_id),
  assigned_admin_id = VALUES(assigned_admin_id),
  assigned_at = VALUES(assigned_at),
  deleted = 0;

INSERT INTO repair_assignment (id, ticket_id, admin_id, worker_id, remark, assigned_at)
VALUES
  (41001, 40001, 10003, 10002, '演示派单：请优先处理教学设备故障。', CURRENT_TIMESTAMP)
ON DUPLICATE KEY UPDATE
  admin_id = VALUES(admin_id),
  worker_id = VALUES(worker_id),
  remark = VALUES(remark),
  assigned_at = VALUES(assigned_at);

INSERT INTO repair_ticket_flow (id, ticket_id, from_status, to_status, operator_id, operator_role, action, remark)
VALUES
  (42001, 40001, NULL, 'PENDING_REVIEW', 10001, 'STUDENT', 'CREATE', '学生提交报修'),
  (42002, 40001, 'PENDING_REVIEW', 'ASSIGNED', 10003, 'ADMIN', 'ASSIGN', '管理员审核通过并派单')
ON DUPLICATE KEY UPDATE
  ticket_id = VALUES(ticket_id),
  from_status = VALUES(from_status),
  to_status = VALUES(to_status),
  operator_id = VALUES(operator_id),
  operator_role = VALUES(operator_role),
  action = VALUES(action),
  remark = VALUES(remark);

INSERT INTO repair_notice (id, title, content, target_role, published, sort_order, created_by)
VALUES
  (50001, '报修服务上线', '校园报修平台已开放学生提交、管理员派单、维修员处理和学生评价流程。', 'ALL', 1, 10, 10003),
  (50002, '维修员处理提醒', '请维修员接单后及时更新处理结果，便于学生确认评价。', 'WORKER', 1, 20, 10003)
ON DUPLICATE KEY UPDATE
  title = VALUES(title),
  content = VALUES(content),
  target_role = VALUES(target_role),
  published = VALUES(published),
  sort_order = VALUES(sort_order),
  created_by = VALUES(created_by),
  deleted = 0;

INSERT INTO rag_knowledge_document (id, title, category_id, content, enabled, created_by)
VALUES
  (60001, '投影仪无法连接处理指南', 20003, '常见原因包括 HDMI 线缆松动、输入源选择错误、电脑显示模式未切换、投影仪灯泡或接口故障。处理时先检查线缆和输入源，再指导用户切换复制/扩展模式，最后更换线缆或登记设备维修。', 1, 10003),
  (60002, '宿舍水龙头漏水处理指南', 20001, '先关闭角阀或楼层水阀，确认漏水位置。若为阀芯松动可紧固或更换垫圈；若管路破裂，需要临时止水并安排备件维修。涉及大面积积水时应提升优先级。', 1, 10003)
ON DUPLICATE KEY UPDATE
  title = VALUES(title),
  category_id = VALUES(category_id),
  content = VALUES(content),
  enabled = VALUES(enabled),
  created_by = VALUES(created_by),
  deleted = 0;

INSERT INTO rag_knowledge_chunk (
  id,
  document_id,
  chunk_index,
  content,
  token_count,
  embedding_provider,
  vector_store_status,
  enabled
)
VALUES
  (
    61001,
    60001,
    0,
    '常见原因包括 HDMI 线缆松动、输入源选择错误、电脑显示模式未切换、投影仪灯泡或接口故障。处理时先检查线缆和输入源，再指导用户切换复制/扩展模式，最后更换线缆或登记设备维修。',
    80,
    'LOCAL_HASH',
    'PENDING',
    1
  ),
  (
    61002,
    60002,
    0,
    '先关闭角阀或楼层水阀，确认漏水位置。若为阀芯松动可紧固或更换垫圈；若管路破裂，需要临时止水并安排备件维修。涉及大面积积水时应提升优先级。',
    72,
    'LOCAL_HASH',
    'PENDING',
    1
  )
ON DUPLICATE KEY UPDATE
  document_id = VALUES(document_id),
  chunk_index = VALUES(chunk_index),
  content = VALUES(content),
  token_count = VALUES(token_count),
  embedding_provider = VALUES(embedding_provider),
  vector_store_status = VALUES(vector_store_status),
  enabled = VALUES(enabled),
  deleted = 0;
