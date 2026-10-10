-- ============================================================
-- 演示数据（虚构，仅供本地演示）
-- ============================================================
-- 用法：mysql -uroot -p --default-character-set=utf8mb4 smart_community_care < seed-demo.sql
-- 前提：已先执行 schema.sql
--
-- 密码说明（明文仅供本地演示，未加密入库）：
--   admin      / Admin@2026    社区管理员
--   staff_wang / Staff@2026    服务人员
--   elder_zhang/ Elder@2026    老人用户（张爷爷）
--   elder_li   / Elder@2026    老人用户（李奶奶）
--
-- 哈希由项目实际使用的 BCryptPasswordEncoder 生成并回验通过
--
-- 仅在首次空库安装完成后运行一次，不用于日常启动，也不要对已有业务数据重跑。
-- 虽有 ON DUPLICATE KEY UPDATE，重复执行仍会改写演示时段、清零其占用、
-- 重置演示事项状态并追加日志，因此不能视为幂等或安全的业务数据更新。
-- ============================================================

SET NAMES utf8mb4;

-- ------------------------------------------------------------
-- 账号：三角色 + 两名老人
-- ------------------------------------------------------------
INSERT INTO sys_user (id, username, password_hash, real_name, phone, email, role, enabled, remark)
VALUES
  (1, 'admin',       '$2a$10$um1TOfko1TNpeby0/PgIQezGV4MrSi4yPvutyY8HVzjB9vvML7XpK',
   '社区管理员', '13900000001', 'admin@demo.local',     'COMMUNITY_ADMIN', 1, '演示账号'),
  (2, 'staff_wang', '$2a$10$YOwWtF/0zXrVe5tvNjiehOLQOIUs0NuIH9ftg752S51q.kuEf.7sC',
   '王服务',     '13900000002', 'staff@demo.local',     'SERVICE_STAFF',   1, '家政服务人员'),
  (3, 'elder_zhang','$2a$10$kFRjnueQTIHLmK4q5MaFE.QjEkBDVyUCx1sSXyEmz5irJUnZY.VdC',
   '张建国',     '13900000003', 'zhang@demo.local',    'ELDER',           1, '65岁，独居'),
  (4, 'elder_li',  '$2a$10$aEZhfoQfw0AC86A7YhR0BuAO6uK782wDbF/YK/gidY1XQY6KdlFCi',
   '李秀兰',     '13900000004', 'li@demo.local',       'ELDER',           1, '72岁，与子女同住')
ON DUPLICATE KEY UPDATE
  password_hash = VALUES(password_hash),
  real_name     = VALUES(real_name),
  enabled       = VALUES(enabled);

-- ------------------------------------------------------------
-- 老人档案
-- ------------------------------------------------------------
INSERT INTO elder_profile (id, user_id, birth_date, gender, address, emergency_contact, emergency_phone, care_note)
VALUES
  (1, 3, '1961-03-15', 'male',   '幸福社区1号楼101室', '张小雨', '13911110001', '有高血压史，晨起测量血压'),
  (2, 4, '1954-08-20', 'female', '幸福社区2号楼202室', '李明',   '13911110002', '行动不便，上下楼需协助')
ON DUPLICATE KEY UPDATE
  care_note = VALUES(care_note);

-- ------------------------------------------------------------
-- 服务项目
-- ------------------------------------------------------------
INSERT INTO service_item (id, name, category, description, price, provider, phone, enabled, sort_order)
VALUES
  (1, '日常保洁',   'HOUSEKEEPING', '擦拭家具、地面清洁、卫生间清洁', 80.00,  '阳光家政',   '13800000001', 1, 1),
  (2, '血压血糖协助测量', 'MEDICAL', '上门协助测量血压、血糖并记录', 50.00, '社区卫生站', '13800000002', 1, 2),
  (3, '生活物资配送', 'DELIVERY',   '代购米面粮油蔬果等生活物资',     20.00, '社区配送中心', '13800000003', 1, 3),
  (4, '康复理疗',   'MEDICAL',     '关节按摩与康复训练指导',         120.00, '社区康复中心', '13800000004', 1, 4)
ON DUPLICATE KEY UPDATE
  price   = VALUES(price),
  enabled = VALUES(enabled);

-- ------------------------------------------------------------
-- 开放时段：日期按执行当天生成，便于当天演示
-- ------------------------------------------------------------
INSERT INTO service_slot (id, service_id, start_at, end_at, capacity, occupied_count, enabled)
VALUES
  (1, 1, DATE_ADD(CURDATE(), INTERVAL 1 DAY) + INTERVAL 9  HOUR,
             DATE_ADD(CURDATE(), INTERVAL 1 DAY) + INTERVAL 12 HOUR, 2, 0, 1),
  (2, 2, DATE_ADD(CURDATE(), INTERVAL 1 DAY) + INTERVAL 14 HOUR,
             DATE_ADD(CURDATE(), INTERVAL 1 DAY) + INTERVAL 17 HOUR, 3, 0, 1),
  (3, 4, DATE_ADD(CURDATE(), INTERVAL 2 DAY) + INTERVAL 10 HOUR,
             DATE_ADD(CURDATE(), INTERVAL 2 DAY) + INTERVAL 11 HOUR, 1, 0, 1)
ON DUPLICATE KEY UPDATE
  start_at       = VALUES(start_at),
  end_at         = VALUES(end_at),
  capacity       = VALUES(capacity),
  occupied_count = 0,
  enabled        = 1;

-- ------------------------------------------------------------
-- 健康记录：近 7 天，含一条命中规则、一条作废记录
-- ------------------------------------------------------------
INSERT INTO health_record
  (id, elder_id, heart_rate, systolic, diastolic, blood_sugar, body_temperature, sleep_hours,
   measure_scene, measured_at, mode, source, voided, created_by, created_at)
VALUES
  (1, 1, 72,  125, 78, 5.6, 36.4, 7.0, 'FASTING', DATE_SUB(NOW(), INTERVAL 6 DAY), 'CURRENT', 'MANUAL', 0, 3, DATE_SUB(NOW(), INTERVAL 6 DAY)),
  (2, 1, 75,  128, 80, 5.8, 36.5, 6.5, 'FASTING', DATE_SUB(NOW(), INTERVAL 5 DAY), 'CURRENT', 'MANUAL', 0, 3, DATE_SUB(NOW(), INTERVAL 5 DAY)),
  (3, 1, 78,  132, 82, 6.0, 36.6, 6.0, 'FASTING', DATE_SUB(NOW(), INTERVAL 4 DAY), 'CURRENT', 'MANUAL', 0, 3, DATE_SUB(NOW(), INTERVAL 4 DAY)),
  (4, 1, 145, 152, 95, 7.8, 37.2, 5.0, 'RANDOM', DATE_SUB(NOW(), INTERVAL 3 DAY), 'CURRENT', 'MANUAL', 0, 3, DATE_SUB(NOW(), INTERVAL 3 DAY)),
  (5, 1, 88,  138, 86, 6.5, 36.8, 4.5, 'RANDOM', DATE_SUB(NOW(), INTERVAL 2 DAY), 'CURRENT', 'MANUAL', 0, 3, DATE_SUB(NOW(), INTERVAL 2 DAY)),
  (6, 1, 80,  130, 82, 6.2, 36.5, 6.5, 'FASTING', DATE_SUB(NOW(), INTERVAL 1 DAY), 'CURRENT', 'MANUAL', 0, 3, DATE_SUB(NOW(), INTERVAL 1 DAY)),
  (7, 1, 74,  126, 79, 5.9, 36.4, 7.5, 'FASTING', NOW(),                    'CURRENT', 'MANUAL', 0, 3, NOW()),
  -- 作废记录：录错重录用，不参与概览
  (8, 1, 200, 300, 200, 20.0, 40.0, 1.0, 'RANDOM', NOW(), 'CURRENT', 'MANUAL', 1, 3, NOW()),
  (9, 2, 68,  118, 76, 5.4, 36.3, 8.0, 'FASTING', DATE_SUB(NOW(), INTERVAL 1 DAY), 'CURRENT', 'MANUAL', 0, 4, DATE_SUB(NOW(), INTERVAL 1 DAY))
ON DUPLICATE KEY UPDATE measured_at = VALUES(measured_at);

-- 作废原因单独更新（放在 INSERT 之后，避免 ON DUPLICATE 覆盖）
UPDATE health_record
SET void_reason = '录入时单位填写错误，已作废并重新录入',
    voided_by    = 3,
    voided_at    = NOW()
WHERE id = 8 AND voided = 1;

-- ------------------------------------------------------------
-- 规则命中明细：对应上面第 4 条（心率、血压、血糖、体温四项异常）
-- 规则版本与阈值为演示配置，不代表医学标准
-- ------------------------------------------------------------
INSERT INTO health_record_anomaly
  (health_record_id, rule_code, rule_version, metric, measured_value, lower_bound, upper_bound, unit)
VALUES
  (4, 'HEART_RATE_HIGH',  'v1', 'heartRate',     145,  NULL, 100,  'bpm'),
  (4, 'SYSTOLIC_HIGH',    'v1', 'systolic',      152,  NULL, 140,  'mmHg'),
  (4, 'DIASTOLIC_HIGH',   'v1', 'diastolic',      95,  NULL,  90,  'mmHg'),
  (4, 'BLOOD_SUGAR_HIGH', 'v1', 'bloodSugar',    7.8,  NULL, 7.0,  'mmol/L'),
  (4, 'TEMPERATURE_HIGH', 'v1', 'bodyTemperature',37.2, NULL, 37.5, 'C'),
  (5, 'SLEEP_TOO_LITTLE', 'v1', 'sleepHours',    4.5,  6.0,  NULL,  'h')
ON DUPLICATE KEY UPDATE measured_value = VALUES(measured_value);

-- ------------------------------------------------------------
-- 一条待处理的健康告警事件
-- ------------------------------------------------------------
INSERT INTO alert_event
  (id, event_no, source_type, rule_code, elder_id, status, priority, occurrence_count,
   response_due_at, description, created_by, created_at)
VALUES
  (1, 'AE20260001', 'HEALTH_ALERT', 'HEART_RATE_HIGH', 1, 'PENDING', 'HIGH', 1,
   DATE_ADD(NOW(), INTERVAL 2 HOUR), '录入后心率与血压多项高于演示阈值，待社区核实', NULL, NOW())
ON DUPLICATE KEY UPDATE status = VALUES(status);

INSERT INTO alert_event_log (event_id, action, from_status, to_status, operator_source, reason, created_at)
VALUES (1, 'CREATE', NULL, 'PENDING', 'SYSTEM', '规则自动命中生成', NOW());

UPDATE health_record_anomaly SET event_id = 1 WHERE health_record_id = 4;

-- ------------------------------------------------------------
-- 一台尚未启动的模拟灯（用于演示内置模拟器）
-- ------------------------------------------------------------
INSERT INTO device
  (id, device_no, device_name, device_type, elder_id, access_mode, location,
   enabled, simulator_running, simulation_mode, simulated_power_status)
VALUES
  (1, 'SIM-LIGHT-001', '客厅灯', 'LIGHT', 1, 'SIMULATED', '客厅',
   1, 0, 'NORMAL', 'OFF')
ON DUPLICATE KEY UPDATE device_name = VALUES(device_name);
