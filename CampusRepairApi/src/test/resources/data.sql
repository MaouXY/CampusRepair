INSERT INTO user_account (id, username, password_hash, real_name, phone, role_code, enabled, deleted)
VALUES
  (10001, 'student01', '{noop}123456', '张同学', '13800000001', 'STUDENT', 1, 0),
  (10002, 'worker01', '{noop}123456', '李师傅', '13800000002', 'WORKER', 1, 0),
  (10003, 'admin01', '{noop}123456', '管理员', '13800000003', 'ADMIN', 1, 0),
  (10004, 'student02', '{noop}123456', '王同学', '13800000004', 'STUDENT', 1, 0),
  (10005, 'worker02', '{noop}123456', '赵师傅', '13800000005', 'WORKER', 1, 0);

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
