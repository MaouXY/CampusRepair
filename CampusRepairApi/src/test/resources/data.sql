INSERT INTO user_account (id, username, password_hash, real_name, phone, role_code, enabled, deleted)
VALUES
  (10001, 'student01', '{noop}123456', '张同学', '13800000001', 'STUDENT', 1, 0),
  (10002, 'worker01', '{noop}123456', '李师傅', '13800000002', 'WORKER', 1, 0),
  (10003, 'admin01', '{noop}123456', '管理员', '13800000003', 'ADMIN', 1, 0),
  (10004, 'student02', '{noop}123456', '王同学', '13800000004', 'STUDENT', 1, 0),
  (10005, 'worker02', '{noop}123456', '赵师傅', '13800000005', 'WORKER', 1, 0),
  (10006, 'worker03', '{noop}123456', '孙师傅', '13800000006', 'WORKER', 1, 0),
  (10007, 'worker04', '{noop}123456', '周师傅', '13800000007', 'WORKER', 1, 0);

INSERT INTO repair_category (id, name, sort_order, enabled, deleted)
VALUES
  (20001, '水电维修', 10, 1, 0),
  (20002, '门窗家具', 20, 1, 0),
  (20003, '网络设备', 30, 1, 0);

INSERT INTO repair_location (id, parent_id, name, sort_order, enabled, deleted)
VALUES
  (30001, NULL, '一号教学楼', 10, 1, 0),
  (30002, 30001, '一号教学楼-101', 11, 1, 0),
  (30003, NULL, '学生宿舍A区', 20, 1, 0);

INSERT INTO worker_profile (worker_id, department_name, skill_tags, dispatch_enabled, max_active_orders, deleted)
VALUES
  (10002, '网络组', '["\u7f51\u7edc"]', 1, 1, 0),
  (10005, '水电组', '["\u6c34\u7535","\u516c\u5171\u8bbe\u65bd"]', 1, 3, 0),
  (10006, '水电组', '["\u6c34\u7535"]', 0, 3, 0),
  (10007, '水电组', '["\u6c34\u7535"]', 1, 3, 0);

INSERT INTO repair_ticket (
  id, student_id, location_id, category_id, description, contact_phone, summary,
  priority, status, assigned_worker_id, deleted
)
VALUES
  (40001, 10001, 30002, 20003, '网络设备维修负载样例', '13800000001', '网络设备维修负载样例', 'LOW', 'ASSIGNED', 10002, 0);
