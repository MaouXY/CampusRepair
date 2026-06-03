SET NAMES utf8mb4;
USE campus_repair;

-- Demo data for graduation defense.
-- Execute after 01_schema_base.sql through 11_schema_operation_audit.sql.
-- The script is idempotent and uses fixed IDs so it can be rerun before demo.

SET @img_network = 'data:image/svg+xml,%3Csvg%20xmlns=%22http://www.w3.org/2000/svg%22%20width=%22640%22%20height=%22360%22%20viewBox=%220%200%20640%20360%22%3E%3Crect%20width=%22640%22%20height=%22360%22%20fill=%22%23eaf4ff%22/%3E%3Crect%20x=%22120%22%20y=%2280%22%20width=%22400%22%20height=%22180%22%20rx=%2212%22%20fill=%22%23ffffff%22%20stroke=%22%233b82f6%22%20stroke-width=%226%22/%3E%3Ctext%20x=%22320%22%20y=%22190%22%20font-size=%2232%22%20text-anchor=%22middle%22%20fill=%22%231e3a8a%22%3ENetwork%20Panel%20Fault%3C/text%3E%3C/svg%3E';
SET @img_water = 'data:image/svg+xml,%3Csvg%20xmlns=%22http://www.w3.org/2000/svg%22%20width=%22640%22%20height=%22360%22%20viewBox=%220%200%20640%20360%22%3E%3Crect%20width=%22640%22%20height=%22360%22%20fill=%22%23ecfeff%22/%3E%3Cpath%20d=%22M150%20240C220%20180%20280%20280%20350%20225C430%20160%20495%20240%20530%20205%22%20fill=%22none%22%20stroke=%22%230e7490%22%20stroke-width=%2214%22%20stroke-linecap=%22round%22/%3E%3Ctext%20x=%22320%22%20y=%22120%22%20font-size=%2230%22%20text-anchor=%22middle%22%20fill=%22%23155e75%22%3EWater%20Leak%3C/text%3E%3C/svg%3E';
SET @img_electric = 'data:image/svg+xml,%3Csvg%20xmlns=%22http://www.w3.org/2000/svg%22%20width=%22640%22%20height=%22360%22%20viewBox=%220%200%20640%20360%22%3E%3Crect%20width=%22640%22%20height=%22360%22%20fill=%22%23fff7ed%22/%3E%3Cpolygon%20points=%22330,50%20230,195%20315,195%20285,310%20425,150%20340,150%22%20fill=%22%23f97316%22/%3E%3Ctext%20x=%22320%22%20y=%22335%22%20font-size=%2228%22%20text-anchor=%22middle%22%20fill=%22%239a3412%22%3EElectric%20Risk%3C/text%3E%3C/svg%3E';
SET @img_done = 'data:image/svg+xml,%3Csvg%20xmlns=%22http://www.w3.org/2000/svg%22%20width=%22640%22%20height=%22360%22%20viewBox=%220%200%20640%20360%22%3E%3Crect%20width=%22640%22%20height=%22360%22%20fill=%22%23f0fdf4%22/%3E%3Ccircle%20cx=%22320%22%20cy=%22170%22%20r=%2290%22%20fill=%22%2322c55e%22/%3E%3Cpath%20d=%22M275%20170l35%2035%2070-80%22%20fill=%22none%22%20stroke=%22%23ffffff%22%20stroke-width=%2218%22%20stroke-linecap=%22round%22%20stroke-linejoin=%22round%22/%3E%3Ctext%20x=%22320%22%20y=%22325%22%20font-size=%2228%22%20text-anchor=%22middle%22%20fill=%22%23166534%22%3ERepair%20Completed%3C/text%3E%3C/svg%3E';

INSERT INTO user_account (id, username, password_hash, real_name, phone, role_code, enabled, last_login_at, created_at, updated_at, deleted)
VALUES
  (10001, 'student01', '{noop}123456', '张同学', '13800000001', 'STUDENT', 1, DATE_SUB(NOW(), INTERVAL 20 MINUTE), DATE_SUB(NOW(), INTERVAL 30 DAY), NOW(), 0),
  (10002, 'worker01', '{noop}123456', '李师傅', '13800000002', 'WORKER', 1, DATE_SUB(NOW(), INTERVAL 15 MINUTE), DATE_SUB(NOW(), INTERVAL 30 DAY), NOW(), 0),
  (10003, 'admin01', '{noop}123456', '管理员', '13800000003', 'ADMIN', 1, DATE_SUB(NOW(), INTERVAL 10 MINUTE), DATE_SUB(NOW(), INTERVAL 30 DAY), NOW(), 0),
  (12001, 'student02', '{noop}123456', '林同学', '13800001201', 'STUDENT', 1, DATE_SUB(NOW(), INTERVAL 1 HOUR), DATE_SUB(NOW(), INTERVAL 20 DAY), NOW(), 0),
  (12002, 'student03', '{noop}123456', '王同学', '13800001202', 'STUDENT', 1, DATE_SUB(NOW(), INTERVAL 2 HOUR), DATE_SUB(NOW(), INTERVAL 18 DAY), NOW(), 0),
  (12003, 'student04', '{noop}123456', '陈同学', '13800001203', 'STUDENT', 1, DATE_SUB(NOW(), INTERVAL 3 HOUR), DATE_SUB(NOW(), INTERVAL 15 DAY), NOW(), 0),
  (12004, 'worker02', '{noop}123456', '赵师傅', '13800001204', 'WORKER', 1, DATE_SUB(NOW(), INTERVAL 25 MINUTE), DATE_SUB(NOW(), INTERVAL 25 DAY), NOW(), 0),
  (12005, 'worker03', '{noop}123456', '周师傅', '13800001205', 'WORKER', 1, DATE_SUB(NOW(), INTERVAL 35 MINUTE), DATE_SUB(NOW(), INTERVAL 25 DAY), NOW(), 0),
  (12006, 'admin02', '{noop}123456', '值班管理员', '13800001206', 'ADMIN', 1, DATE_SUB(NOW(), INTERVAL 45 MINUTE), DATE_SUB(NOW(), INTERVAL 25 DAY), NOW(), 0)
ON DUPLICATE KEY UPDATE
  password_hash = VALUES(password_hash),
  real_name = VALUES(real_name),
  phone = VALUES(phone),
  role_code = VALUES(role_code),
  enabled = VALUES(enabled),
  last_login_at = VALUES(last_login_at),
  updated_at = VALUES(updated_at),
  deleted = 0;

INSERT INTO repair_category (id, name, sort_order, enabled, created_at, updated_at, deleted)
VALUES
  (20001, '水电维修', 10, 1, DATE_SUB(NOW(), INTERVAL 30 DAY), NOW(), 0),
  (20002, '门窗家具', 20, 1, DATE_SUB(NOW(), INTERVAL 30 DAY), NOW(), 0),
  (20003, '网络设备', 30, 1, DATE_SUB(NOW(), INTERVAL 30 DAY), NOW(), 0),
  (20004, '空调照明', 40, 1, DATE_SUB(NOW(), INTERVAL 30 DAY), NOW(), 0),
  (20005, '公共设施', 50, 1, DATE_SUB(NOW(), INTERVAL 30 DAY), NOW(), 0)
ON DUPLICATE KEY UPDATE
  name = VALUES(name),
  sort_order = VALUES(sort_order),
  enabled = VALUES(enabled),
  updated_at = VALUES(updated_at),
  deleted = 0;

INSERT INTO repair_location (id, parent_id, name, sort_order, enabled, created_at, updated_at, deleted)
VALUES
  (30001, NULL, '一号教学楼', 10, 1, DATE_SUB(NOW(), INTERVAL 30 DAY), NOW(), 0),
  (30002, 30001, '一号教学楼-101', 11, 1, DATE_SUB(NOW(), INTERVAL 30 DAY), NOW(), 0),
  (30003, 30001, '一号教学楼-公共走廊', 12, 1, DATE_SUB(NOW(), INTERVAL 30 DAY), NOW(), 0),
  (30004, NULL, '学生宿舍A区', 20, 1, DATE_SUB(NOW(), INTERVAL 30 DAY), NOW(), 0),
  (30005, 30004, '学生宿舍A区-2栋', 21, 1, DATE_SUB(NOW(), INTERVAL 30 DAY), NOW(), 0),
  (30006, NULL, '图书馆', 30, 1, DATE_SUB(NOW(), INTERVAL 30 DAY), NOW(), 0),
  (30007, 30006, '图书馆-自习区', 31, 1, DATE_SUB(NOW(), INTERVAL 30 DAY), NOW(), 0),
  (120301, NULL, '实验实训中心', 40, 1, DATE_SUB(NOW(), INTERVAL 20 DAY), NOW(), 0),
  (120302, 120301, '实验实训中心-302机房', 41, 1, DATE_SUB(NOW(), INTERVAL 20 DAY), NOW(), 0),
  (120303, 30004, '学生宿舍A区-4栋洗衣房', 22, 1, DATE_SUB(NOW(), INTERVAL 20 DAY), NOW(), 0)
ON DUPLICATE KEY UPDATE
  parent_id = VALUES(parent_id),
  name = VALUES(name),
  sort_order = VALUES(sort_order),
  enabled = VALUES(enabled),
  updated_at = VALUES(updated_at),
  deleted = 0;

INSERT INTO repair_ticket (
  id, student_id, location_id, category_id, description, contact_phone, summary,
  priority, status, assigned_worker_id, assigned_admin_id, assigned_at,
  reject_reason, return_reason, process_result, process_remark, processed_at,
  sla_deadline_at, urged_at, urged_by, urge_remark,
  report_image_urls, result_image_urls, created_at, updated_at, deleted
)
VALUES
  (120001, 10001, 30007, 20003,
   '图书馆自习区靠窗位置网络面板无信号，重启电脑和更换网线后仍无法联网，下午有线上答辩彩排需要使用。',
   '13800000001', '图书馆自习区网络面板无信号', 'MEDIUM', 'PENDING_REVIEW',
   NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL,
   DATE_ADD(NOW(), INTERVAL 48 HOUR), NULL, NULL, NULL,
   JSON_ARRAY(@img_network), JSON_ARRAY(), DATE_SUB(NOW(), INTERVAL 10 MINUTE), DATE_SUB(NOW(), INTERVAL 10 MINUTE), 0),
  (120002, 12001, 30005, 20001,
   '宿舍A区2栋三楼公共洗手间水龙头持续漏水，地面有积水，晚高峰容易滑倒。',
   '13800001201', '宿舍公共洗手间水龙头持续漏水', 'HIGH', 'RETURNED',
   10002, 10003, DATE_SUB(NOW(), INTERVAL 2 HOUR),
   NULL, '现场需要关闭楼层水阀并携带阀芯备件，当前备件不足，建议重新派给水电专员。',
   NULL, NULL, NULL,
   DATE_ADD(DATE_SUB(NOW(), INTERVAL 2 HOUR), INTERVAL 24 HOUR), NULL, NULL, NULL,
   JSON_ARRAY(@img_water), JSON_ARRAY(), DATE_SUB(NOW(), INTERVAL 3 HOUR), DATE_SUB(NOW(), INTERVAL 1 HOUR), 0),
  (120003, 12002, 30002, 20003,
   '一号教学楼101投影仪连接教师电脑后没有画面，HDMI线已重新插拔，课程演示无法进行。',
   '13800001202', '一号教学楼101投影仪无画面', 'MEDIUM', 'ASSIGNED',
   10002, 10003, DATE_SUB(NOW(), INTERVAL 30 MINUTE),
   NULL, NULL, NULL, NULL, NULL,
   DATE_ADD(NOW(), INTERVAL 44 HOUR), NULL, NULL, NULL,
   JSON_ARRAY(@img_network), JSON_ARRAY(), DATE_SUB(NOW(), INTERVAL 2 HOUR), DATE_SUB(NOW(), INTERVAL 30 MINUTE), 0),
  (120004, 12003, 30005, 20001,
   '宿舍A区2栋402房间插座面板发黑并有轻微焦味，已经停止使用，担心存在用电安全隐患。',
   '13800001203', '宿舍插座面板发黑有焦味', 'HIGH', 'PROCESSING',
   12004, 10003, DATE_SUB(NOW(), INTERVAL 36 HOUR),
   NULL, NULL, NULL, '已到现场断电检查，等待更换插座面板和复测线路。', NULL,
   DATE_SUB(NOW(), INTERVAL 12 HOUR), DATE_SUB(NOW(), INTERVAL 1 HOUR), 10003, '安全隐患工单已超出SLA，请尽快提交处理结果。',
   JSON_ARRAY(@img_electric), JSON_ARRAY(), DATE_SUB(NOW(), INTERVAL 2 DAY), DATE_SUB(NOW(), INTERVAL 1 HOUR), 0),
  (120005, 10001, 30003, 20004,
   '一号教学楼三楼公共走廊有两盏灯不亮，夜间通行视线较差。',
   '13800000001', '教学楼三楼走廊照明不亮', 'LOW', 'WAITING_CONFIRM',
   12005, 10003, DATE_SUB(NOW(), INTERVAL 20 HOUR),
   NULL, NULL, '已更换两支LED灯管并检查开关面板，现场照明恢复正常。',
   '建议后勤每月巡检公共区域照明。', DATE_SUB(NOW(), INTERVAL 2 HOUR),
   DATE_ADD(NOW(), INTERVAL 6 HOUR), DATE_SUB(NOW(), INTERVAL 30 MINUTE), 10003, '请学生确认照明是否恢复，若仍异常可申请继续处理。',
   JSON_ARRAY(), JSON_ARRAY(@img_done), DATE_SUB(NOW(), INTERVAL 1 DAY), DATE_SUB(NOW(), INTERVAL 30 MINUTE), 0),
  (120006, 12001, 120302, 20004,
   '实验实训中心302机房空调制冷效果差，室内温度较高，影响上机课程。',
   '13800001201', '302机房空调制冷效果差', 'MEDIUM', 'COMPLETED',
   12005, 10003, DATE_SUB(NOW(), INTERVAL 3 DAY),
   NULL, NULL, '清洗滤网并补充制冷剂，复测出风温度正常。',
   '已提醒机房管理员定期清洗滤网。', DATE_SUB(NOW(), INTERVAL 2 DAY),
   DATE_ADD(DATE_SUB(NOW(), INTERVAL 3 DAY), INTERVAL 48 HOUR), NULL, NULL, NULL,
   JSON_ARRAY(), JSON_ARRAY(@img_done), DATE_SUB(NOW(), INTERVAL 3 DAY), DATE_SUB(NOW(), INTERVAL 1 DAY), 0),
  (120007, 12002, 120303, 20005,
   '洗衣房设备坏了，无法使用。',
   '13800001202', '洗衣房设备无法使用', 'LOW', 'REJECTED',
   NULL, 10003, NULL,
   '描述过于笼统，请补充具体设备编号、故障表现和现场照片后重新提交。',
   NULL, NULL, NULL, NULL,
   DATE_ADD(NOW(), INTERVAL 72 HOUR), NULL, NULL, NULL,
   JSON_ARRAY(), JSON_ARRAY(), DATE_SUB(NOW(), INTERVAL 40 MINUTE), DATE_SUB(NOW(), INTERVAL 20 MINUTE), 0)
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
  reject_reason = VALUES(reject_reason),
  return_reason = VALUES(return_reason),
  process_result = VALUES(process_result),
  process_remark = VALUES(process_remark),
  processed_at = VALUES(processed_at),
  sla_deadline_at = VALUES(sla_deadline_at),
  urged_at = VALUES(urged_at),
  urged_by = VALUES(urged_by),
  urge_remark = VALUES(urge_remark),
  report_image_urls = VALUES(report_image_urls),
  result_image_urls = VALUES(result_image_urls),
  created_at = VALUES(created_at),
  updated_at = VALUES(updated_at),
  deleted = 0;

INSERT INTO repair_assignment (id, ticket_id, admin_id, worker_id, remark, assigned_at)
VALUES
  (120201, 120002, 10003, 10002, '先核查漏水点，若需要备件请退回说明。', DATE_SUB(NOW(), INTERVAL 2 HOUR)),
  (120202, 120003, 10003, 10002, '教学设备影响课堂，请优先检查HDMI链路和输入源。', DATE_SUB(NOW(), INTERVAL 30 MINUTE)),
  (120203, 120004, 10003, 12004, '存在用电风险，请断电后处理并拍照反馈。', DATE_SUB(NOW(), INTERVAL 36 HOUR)),
  (120204, 120005, 10003, 12005, '公共走廊照明，请当天完成并提交结果。', DATE_SUB(NOW(), INTERVAL 20 HOUR)),
  (120205, 120006, 10003, 12005, '机房空调影响课程，请排查滤网和制冷系统。', DATE_SUB(NOW(), INTERVAL 3 DAY))
ON DUPLICATE KEY UPDATE
  ticket_id = VALUES(ticket_id),
  admin_id = VALUES(admin_id),
  worker_id = VALUES(worker_id),
  remark = VALUES(remark),
  assigned_at = VALUES(assigned_at);

INSERT INTO repair_ticket_flow (id, ticket_id, from_status, to_status, operator_id, operator_role, action, remark, created_at)
VALUES
  (121001, 120001, NULL, 'PENDING_REVIEW', 10001, 'STUDENT', 'CREATE', '学生提交报修，等待管理员审核。', DATE_SUB(NOW(), INTERVAL 10 MINUTE)),
  (121002, 120002, NULL, 'PENDING_REVIEW', 12001, 'STUDENT', 'CREATE', '学生提交漏水报修。', DATE_SUB(NOW(), INTERVAL 3 HOUR)),
  (121003, 120002, 'PENDING_REVIEW', 'ASSIGNED', 10003, 'ADMIN', 'ASSIGN', '派给李师傅先行核查。', DATE_SUB(NOW(), INTERVAL 2 HOUR)),
  (121004, 120002, 'ASSIGNED', 'RETURNED', 10002, 'WORKER', 'WORKER_RETURN', '缺少阀芯备件，建议重新派给水电专员。', DATE_SUB(NOW(), INTERVAL 1 HOUR)),
  (121005, 120003, NULL, 'PENDING_REVIEW', 12002, 'STUDENT', 'CREATE', '学生提交投影仪故障。', DATE_SUB(NOW(), INTERVAL 2 HOUR)),
  (121006, 120003, 'PENDING_REVIEW', 'ASSIGNED', 10003, 'ADMIN', 'ASSIGN', '派给李师傅处理教学设备故障。', DATE_SUB(NOW(), INTERVAL 30 MINUTE)),
  (121007, 120004, NULL, 'PENDING_REVIEW', 12003, 'STUDENT', 'CREATE', '学生提交用电安全隐患。', DATE_SUB(NOW(), INTERVAL 2 DAY)),
  (121008, 120004, 'PENDING_REVIEW', 'ASSIGNED', 10003, 'ADMIN', 'ASSIGN', '高优先级派给赵师傅处理。', DATE_SUB(NOW(), INTERVAL 36 HOUR)),
  (121009, 120004, 'ASSIGNED', 'PROCESSING', 12004, 'WORKER', 'ACCEPT', '维修员接单并开始处理。', DATE_SUB(NOW(), INTERVAL 35 HOUR)),
  (121010, 120004, 'PROCESSING', 'PROCESSING', 10003, 'ADMIN', 'URGE', '安全隐患工单已超出SLA，请尽快提交处理结果。', DATE_SUB(NOW(), INTERVAL 1 HOUR)),
  (121011, 120005, NULL, 'PENDING_REVIEW', 10001, 'STUDENT', 'CREATE', '学生提交走廊照明故障。', DATE_SUB(NOW(), INTERVAL 1 DAY)),
  (121012, 120005, 'PENDING_REVIEW', 'ASSIGNED', 10003, 'ADMIN', 'ASSIGN', '派给周师傅处理公共照明。', DATE_SUB(NOW(), INTERVAL 20 HOUR)),
  (121013, 120005, 'ASSIGNED', 'PROCESSING', 12005, 'WORKER', 'ACCEPT', '维修员接单并准备灯管。', DATE_SUB(NOW(), INTERVAL 19 HOUR)),
  (121014, 120005, 'PROCESSING', 'WAITING_CONFIRM', 12005, 'WORKER', 'SUBMIT_RESULT', '已更换灯管，等待学生确认评价。', DATE_SUB(NOW(), INTERVAL 2 HOUR)),
  (121015, 120005, 'WAITING_CONFIRM', 'WAITING_CONFIRM', 10003, 'ADMIN', 'URGE', '请学生确认照明是否恢复。', DATE_SUB(NOW(), INTERVAL 30 MINUTE)),
  (121016, 120006, NULL, 'PENDING_REVIEW', 12001, 'STUDENT', 'CREATE', '学生提交机房空调故障。', DATE_SUB(NOW(), INTERVAL 3 DAY)),
  (121017, 120006, 'PENDING_REVIEW', 'ASSIGNED', 10003, 'ADMIN', 'ASSIGN', '派给周师傅排查空调。', DATE_SUB(NOW(), INTERVAL 3 DAY)),
  (121018, 120006, 'ASSIGNED', 'PROCESSING', 12005, 'WORKER', 'ACCEPT', '维修员接单。', DATE_SUB(NOW(), INTERVAL 3 DAY)),
  (121019, 120006, 'PROCESSING', 'WAITING_CONFIRM', 12005, 'WORKER', 'SUBMIT_RESULT', '空调恢复正常，等待学生确认。', DATE_SUB(NOW(), INTERVAL 2 DAY)),
  (121020, 120006, 'WAITING_CONFIRM', 'COMPLETED', 12001, 'STUDENT', 'EVALUATE', '学生评价完成。', DATE_SUB(NOW(), INTERVAL 1 DAY)),
  (121021, 120007, NULL, 'PENDING_REVIEW', 12002, 'STUDENT', 'CREATE', '学生提交洗衣房设备故障。', DATE_SUB(NOW(), INTERVAL 40 MINUTE)),
  (121022, 120007, 'PENDING_REVIEW', 'REJECTED', 10003, 'ADMIN', 'REJECT', '描述过于笼统，请补充设备编号和照片。', DATE_SUB(NOW(), INTERVAL 20 MINUTE))
ON DUPLICATE KEY UPDATE
  ticket_id = VALUES(ticket_id),
  from_status = VALUES(from_status),
  to_status = VALUES(to_status),
  operator_id = VALUES(operator_id),
  operator_role = VALUES(operator_role),
  action = VALUES(action),
  remark = VALUES(remark),
  created_at = VALUES(created_at);

INSERT INTO repair_evaluation (id, ticket_id, student_id, worker_id, score, content, created_at)
VALUES
  (120301, 120006, 12001, 12005, 5, '处理及时，机房温度恢复正常，维修结果说明也很清楚。', DATE_SUB(NOW(), INTERVAL 1 DAY))
ON DUPLICATE KEY UPDATE
  ticket_id = VALUES(ticket_id),
  student_id = VALUES(student_id),
  worker_id = VALUES(worker_id),
  score = VALUES(score),
  content = VALUES(content),
  created_at = VALUES(created_at);

INSERT INTO file_metadata (
  id, original_name, object_key, bucket_name, content_type, size_bytes,
  uploader_id, uploader_role, biz_type, biz_id, public_url, created_at, updated_at, deleted
)
VALUES
  (120401, 'network-panel-demo.svg', 'demo/ticket/120001/network-panel-demo.svg', 'campus-repair-demo', 'image/svg+xml', 6400, 10001, 'STUDENT', 'TICKET_REPORT_IMAGE', 120001, @img_network, DATE_SUB(NOW(), INTERVAL 10 MINUTE), NOW(), 0),
  (120402, 'water-leak-demo.svg', 'demo/ticket/120002/water-leak-demo.svg', 'campus-repair-demo', 'image/svg+xml', 6400, 12001, 'STUDENT', 'TICKET_REPORT_IMAGE', 120002, @img_water, DATE_SUB(NOW(), INTERVAL 3 HOUR), NOW(), 0),
  (120403, 'electric-risk-demo.svg', 'demo/ticket/120004/electric-risk-demo.svg', 'campus-repair-demo', 'image/svg+xml', 6400, 12003, 'STUDENT', 'TICKET_REPORT_IMAGE', 120004, @img_electric, DATE_SUB(NOW(), INTERVAL 2 DAY), NOW(), 0),
  (120404, 'lighting-done-demo.svg', 'demo/ticket/120005/lighting-done-demo.svg', 'campus-repair-demo', 'image/svg+xml', 6400, 12005, 'WORKER', 'TICKET_RESULT_IMAGE', 120005, @img_done, DATE_SUB(NOW(), INTERVAL 2 HOUR), NOW(), 0),
  (120405, 'air-conditioner-done-demo.svg', 'demo/ticket/120006/air-conditioner-done-demo.svg', 'campus-repair-demo', 'image/svg+xml', 6400, 12005, 'WORKER', 'TICKET_RESULT_IMAGE', 120006, @img_done, DATE_SUB(NOW(), INTERVAL 2 DAY), NOW(), 0)
ON DUPLICATE KEY UPDATE
  original_name = VALUES(original_name),
  object_key = VALUES(object_key),
  bucket_name = VALUES(bucket_name),
  content_type = VALUES(content_type),
  size_bytes = VALUES(size_bytes),
  uploader_id = VALUES(uploader_id),
  uploader_role = VALUES(uploader_role),
  biz_type = VALUES(biz_type),
  biz_id = VALUES(biz_id),
  public_url = VALUES(public_url),
  updated_at = VALUES(updated_at),
  deleted = 0;

INSERT INTO ai_task_record (
  id, organization_id, task_type, biz_type, biz_id, model_name,
  request_snapshot, response_snapshot, status, error_message, duration_ms,
  created_at, updated_at, deleted
)
VALUES
  (120501, NULL, 'TICKET_PRE_ANALYSIS', 'REPAIR_TICKET', 120001, 'demo-rag-rule-model',
   'ticketId=120001; category=网络设备; location=图书馆自习区; description=网络面板无信号; ragChunks=网络设备排查指南',
   '{"suggestedCategoryId":20003,"suggestedPriority":"MEDIUM","suggestedWorkerId":10002,"faultSummary":"图书馆自习区网络面板无信号","faultReason":"可能为信息面板端口、交换机端口或认证链路异常","solution":"先检查网线和终端认证，再排查弱电间交换机端口","dispatchRemark":"携带测线仪，优先确认端口连通性","riskLevel":"MEDIUM","confidence":0.88}',
   'SUCCESS', NULL, 1260, DATE_SUB(NOW(), INTERVAL 9 MINUTE), DATE_SUB(NOW(), INTERVAL 8 MINUTE), 0),
  (120502, NULL, 'TICKET_PRE_ANALYSIS', 'REPAIR_TICKET', 120002, 'demo-rag-rule-model',
   'ticketId=120002; category=水电维修; location=宿舍A区2栋; description=水龙头持续漏水并有积水; ragChunks=水电安全处置指南',
   '{"suggestedCategoryId":20001,"suggestedPriority":"HIGH","suggestedWorkerId":12004,"faultSummary":"宿舍公共洗手间水龙头持续漏水","faultReason":"阀芯或密封圈老化，积水带来滑倒风险","solution":"关闭楼层水阀后更换阀芯并清理积水","dispatchRemark":"建议重新派给水电专员赵师傅并携带阀芯备件","riskLevel":"HIGH","confidence":0.91}',
   'SUCCESS', NULL, 1180, DATE_SUB(NOW(), INTERVAL 2 HOUR), DATE_SUB(NOW(), INTERVAL 115 MINUTE), 0),
  (120503, NULL, 'TICKET_PRE_ANALYSIS', 'REPAIR_TICKET', 120007, 'demo-rag-rule-model',
   'ticketId=120007; category=公共设施; description=洗衣房设备坏了; ragChunks=描述不完整兜底',
   NULL, 'FAILED', 'demo fallback: description is too short to infer exact device and symptom', 940,
   DATE_SUB(NOW(), INTERVAL 35 MINUTE), DATE_SUB(NOW(), INTERVAL 34 MINUTE), 0)
ON DUPLICATE KEY UPDATE
  organization_id = VALUES(organization_id),
  task_type = VALUES(task_type),
  biz_type = VALUES(biz_type),
  biz_id = VALUES(biz_id),
  model_name = VALUES(model_name),
  request_snapshot = VALUES(request_snapshot),
  response_snapshot = VALUES(response_snapshot),
  status = VALUES(status),
  error_message = VALUES(error_message),
  duration_ms = VALUES(duration_ms),
  created_at = VALUES(created_at),
  updated_at = VALUES(updated_at),
  deleted = 0;

INSERT INTO repair_ai_analysis (
  id, ticket_id, ai_task_id, status, suggested_category_id, suggested_priority,
  suggested_worker_id, fault_summary, fault_reason, solution, dispatch_remark,
  risk_level, confidence, raw_response, created_at, updated_at, deleted
)
VALUES
  (120601, 120001, 120501, 'SUCCESS', 20003, 'MEDIUM', 10002,
   '图书馆自习区网络面板无信号',
   '可能为信息面板端口、交换机端口或校园网认证链路异常。',
   '先检查网线和终端认证，再使用测线仪确认端口连通性，必要时切换交换机端口。',
   '携带测线仪和备用跳线，优先保障下午答辩彩排。',
   'MEDIUM', 0.88,
   '{"suggestedCategoryId":20003,"suggestedPriority":"MEDIUM","suggestedWorkerId":10002,"faultSummary":"图书馆自习区网络面板无信号","faultReason":"可能为信息面板端口、交换机端口或认证链路异常","solution":"先检查网线和终端认证，再排查弱电间交换机端口","dispatchRemark":"携带测线仪，优先确认端口连通性","riskLevel":"MEDIUM","confidence":0.88}',
   DATE_SUB(NOW(), INTERVAL 8 MINUTE), DATE_SUB(NOW(), INTERVAL 8 MINUTE), 0),
  (120602, 120002, 120502, 'SUCCESS', 20001, 'HIGH', 12004,
   '宿舍公共洗手间水龙头持续漏水',
   '阀芯或密封圈老化导致持续漏水，地面积水带来滑倒风险。',
   '关闭楼层水阀后更换阀芯或密封圈，处理完成后清理积水并复测。',
   '建议重新派给水电专员赵师傅，携带阀芯、密封圈和警示牌。',
   'HIGH', 0.91,
   '{"suggestedCategoryId":20001,"suggestedPriority":"HIGH","suggestedWorkerId":12004,"faultSummary":"宿舍公共洗手间水龙头持续漏水","faultReason":"阀芯或密封圈老化，积水带来滑倒风险","solution":"关闭楼层水阀后更换阀芯并清理积水","dispatchRemark":"建议重新派给水电专员赵师傅并携带阀芯备件","riskLevel":"HIGH","confidence":0.91}',
   DATE_SUB(NOW(), INTERVAL 115 MINUTE), DATE_SUB(NOW(), INTERVAL 115 MINUTE), 0),
  (120603, 120007, 120503, 'FALLBACK', 20005, 'LOW', NULL,
   '洗衣房设备无法使用',
   '报修描述缺少设备编号、具体故障表现和现场照片，无法可靠判断原因。',
   '请学生补充设备编号、屏幕提示、是否漏水或断电等信息后重新提交。',
   'AI无法可靠派单，建议管理员驳回补充信息。',
   'LOW', 0.20,
   'demo fallback: description is too short to infer exact device and symptom',
   DATE_SUB(NOW(), INTERVAL 34 MINUTE), DATE_SUB(NOW(), INTERVAL 34 MINUTE), 0)
ON DUPLICATE KEY UPDATE
  ticket_id = VALUES(ticket_id),
  ai_task_id = VALUES(ai_task_id),
  status = VALUES(status),
  suggested_category_id = VALUES(suggested_category_id),
  suggested_priority = VALUES(suggested_priority),
  suggested_worker_id = VALUES(suggested_worker_id),
  fault_summary = VALUES(fault_summary),
  fault_reason = VALUES(fault_reason),
  solution = VALUES(solution),
  dispatch_remark = VALUES(dispatch_remark),
  risk_level = VALUES(risk_level),
  confidence = VALUES(confidence),
  raw_response = VALUES(raw_response),
  created_at = VALUES(created_at),
  updated_at = VALUES(updated_at),
  deleted = 0;

INSERT INTO rag_knowledge_document (id, title, category_id, content, enabled, created_by, created_at, updated_at, deleted)
VALUES
  (120701, '答辩演示知识库：网络面板故障排查', 20003,
   '网络面板无信号时，优先确认是否为单台终端问题、单个座位问题或整片区域问题。维修员应检查网线、信息面板、弱电间交换机端口、校园网认证状态和近期网络维护公告。若影响教学、考试、答辩等场景，应至少按中优先级处理，并在处理结果中说明实际故障点和复测结果。',
   1, 10003, DATE_SUB(NOW(), INTERVAL 5 DAY), NOW(), 0),
  (120702, '答辩演示知识库：宿舍漏水与用电安全', 20001,
   '宿舍漏水应先关闭角阀或楼层水阀，判断阀芯、密封圈、软管和管路是否损坏。出现大面积积水时需要设置提示并清理现场。插座发黑、焦味、打火属于用电安全风险，应立即停止使用并断电检查，优先级应提升为高。',
   1, 10003, DATE_SUB(NOW(), INTERVAL 5 DAY), NOW(), 0)
ON DUPLICATE KEY UPDATE
  title = VALUES(title),
  category_id = VALUES(category_id),
  content = VALUES(content),
  enabled = VALUES(enabled),
  created_by = VALUES(created_by),
  updated_at = VALUES(updated_at),
  deleted = 0;

INSERT INTO rag_knowledge_chunk (
  id, document_id, chunk_index, content, token_count, embedding_provider,
  vector_store_status, enabled, created_at, updated_at, deleted
)
VALUES
  (120801, 120701, 0, '网络面板无信号时，先区分单台终端、单个座位和整片区域问题。检查网线、信息面板、弱电间交换机端口和校园网认证状态。影响教学、考试、答辩时至少按中优先级处理。', 82, 'LOCAL_HASH', 'PENDING', 1, DATE_SUB(NOW(), INTERVAL 5 DAY), NOW(), 0),
  (120802, 120702, 0, '宿舍漏水先关闭角阀或楼层水阀，再判断阀芯、密封圈、软管和管路。大面积积水需要设置提示并清理现场，避免学生滑倒。', 76, 'LOCAL_HASH', 'PENDING', 1, DATE_SUB(NOW(), INTERVAL 5 DAY), NOW(), 0),
  (120803, 120702, 1, '插座发黑、焦味、打火属于用电安全风险，应立即停止使用并断电检查，派单优先级应提升为高，处理结果需要包含复测情况。', 72, 'LOCAL_HASH', 'PENDING', 1, DATE_SUB(NOW(), INTERVAL 5 DAY), NOW(), 0)
ON DUPLICATE KEY UPDATE
  content = VALUES(content),
  token_count = VALUES(token_count),
  embedding_provider = VALUES(embedding_provider),
  vector_store_status = VALUES(vector_store_status),
  enabled = VALUES(enabled),
  updated_at = VALUES(updated_at),
  deleted = 0;

INSERT INTO repair_notice (id, title, content, target_role, published, sort_order, created_by, created_at, updated_at, deleted)
VALUES
  (120901, '答辩演示公告：校园维修平台流程演示', '本演示环境已准备学生提交、AI预分析、管理员派单、维修员接单处理、学生确认评价、SLA超时督办和操作审计数据。', 'ALL', 1, 1, 10003, DATE_SUB(NOW(), INTERVAL 1 DAY), NOW(), 0),
  (120902, '维修员提醒：高风险工单优先处理', '遇到漏水积水、插座焦味、公共照明等影响安全或教学秩序的工单，请优先接单处理并提交现场结果。', 'WORKER', 1, 2, 10003, DATE_SUB(NOW(), INTERVAL 1 DAY), NOW(), 0),
  (120903, '学生提醒：提交报修请补充照片和具体位置', '请尽量填写楼栋、房间、设备编号和故障现象，描述越完整，AI预分析和管理员派单越准确。', 'STUDENT', 1, 3, 10003, DATE_SUB(NOW(), INTERVAL 1 DAY), NOW(), 0)
ON DUPLICATE KEY UPDATE
  title = VALUES(title),
  content = VALUES(content),
  target_role = VALUES(target_role),
  published = VALUES(published),
  sort_order = VALUES(sort_order),
  created_by = VALUES(created_by),
  updated_at = VALUES(updated_at),
  deleted = 0;

INSERT INTO operation_audit_log (
  id, operator_id, operator_role, biz_type, biz_id, action,
  before_snapshot, after_snapshot, remark, created_at
)
VALUES
  (122001, 10003, 'ADMIN', 'REPAIR_TICKET', 120002, 'ASSIGN',
   'ticketId=120002,status=PENDING_REVIEW,studentId=12001,workerId=null,priority=HIGH',
   'ticketId=120002,status=ASSIGNED,studentId=12001,workerId=10002,priority=HIGH',
   '派给李师傅先行核查。', DATE_SUB(NOW(), INTERVAL 2 HOUR)),
  (122002, 10002, 'WORKER', 'REPAIR_TICKET', 120002, 'WORKER_RETURN',
   'ticketId=120002,status=ASSIGNED,studentId=12001,workerId=10002,priority=HIGH',
   'ticketId=120002,status=RETURNED,studentId=12001,workerId=10002,priority=HIGH',
   '缺少阀芯备件，建议重新派给水电专员。', DATE_SUB(NOW(), INTERVAL 1 HOUR)),
  (122003, 10003, 'ADMIN', 'REPAIR_TICKET', 120003, 'ASSIGN',
   'ticketId=120003,status=PENDING_REVIEW,studentId=12002,workerId=null,priority=MEDIUM',
   'ticketId=120003,status=ASSIGNED,studentId=12002,workerId=10002,priority=MEDIUM',
   '派给李师傅处理教学设备故障。', DATE_SUB(NOW(), INTERVAL 30 MINUTE)),
  (122004, 10003, 'ADMIN', 'REPAIR_TICKET', 120004, 'ASSIGN',
   'ticketId=120004,status=PENDING_REVIEW,studentId=12003,workerId=null,priority=HIGH',
   'ticketId=120004,status=ASSIGNED,studentId=12003,workerId=12004,priority=HIGH',
   '高优先级派给赵师傅处理。', DATE_SUB(NOW(), INTERVAL 36 HOUR)),
  (122005, 12004, 'WORKER', 'REPAIR_TICKET', 120004, 'ACCEPT',
   'ticketId=120004,status=ASSIGNED,studentId=12003,workerId=12004,priority=HIGH',
   'ticketId=120004,status=PROCESSING,studentId=12003,workerId=12004,priority=HIGH',
   '维修员接单并开始处理。', DATE_SUB(NOW(), INTERVAL 35 HOUR)),
  (122006, 10003, 'ADMIN', 'REPAIR_TICKET', 120004, 'URGE',
   'ticketId=120004,status=PROCESSING,studentId=12003,workerId=12004,priority=HIGH',
   'ticketId=120004,status=PROCESSING,studentId=12003,workerId=12004,priority=HIGH,urged=true',
   '安全隐患工单已超出SLA，请尽快提交处理结果。', DATE_SUB(NOW(), INTERVAL 1 HOUR)),
  (122007, 12005, 'WORKER', 'REPAIR_TICKET', 120005, 'SUBMIT_RESULT',
   'ticketId=120005,status=PROCESSING,studentId=10001,workerId=12005,priority=LOW',
   'ticketId=120005,status=WAITING_CONFIRM,studentId=10001,workerId=12005,priority=LOW',
   '已更换灯管，等待学生确认评价。', DATE_SUB(NOW(), INTERVAL 2 HOUR)),
  (122008, 12001, 'STUDENT', 'REPAIR_TICKET', 120006, 'EVALUATE',
   'ticketId=120006,status=WAITING_CONFIRM,studentId=12001,workerId=12005,priority=MEDIUM',
   'ticketId=120006,status=COMPLETED,studentId=12001,workerId=12005,priority=MEDIUM',
   '学生评价完成。', DATE_SUB(NOW(), INTERVAL 1 DAY)),
  (122009, 10003, 'ADMIN', 'REPAIR_TICKET', 120007, 'REJECT',
   'ticketId=120007,status=PENDING_REVIEW,studentId=12002,workerId=null,priority=LOW',
   'ticketId=120007,status=REJECTED,studentId=12002,workerId=null,priority=LOW',
   '描述过于笼统，请补充设备编号和照片。', DATE_SUB(NOW(), INTERVAL 20 MINUTE))
ON DUPLICATE KEY UPDATE
  operator_id = VALUES(operator_id),
  operator_role = VALUES(operator_role),
  biz_type = VALUES(biz_type),
  biz_id = VALUES(biz_id),
  action = VALUES(action),
  before_snapshot = VALUES(before_snapshot),
  after_snapshot = VALUES(after_snapshot),
  remark = VALUES(remark),
  created_at = VALUES(created_at);
