-- ============================================================
-- 智慧社区居家养老服务与告警协同平台 —— 完整建表脚本
-- ============================================================
-- 用途：仅用于安装空的新库（smart_community_care / _test）
-- 执行：mysql -uroot -p --default-character-set=utf8mb4 < schema.sql
-- 注意：已有库请使用 migrations/ 下的增量脚本，不要重跑本文件
--
-- 设计约定（见 V1.0 §8 / V1.2 §4）：
--   1. 主键 BIGINT AUTO_INCREMENT，Java 用 Long，JSON 序列化为字符串
--   2. 金额 DECIMAL，不用 DOUBLE
--   3. 业务时间 DATETIME(3)，统一北京时间语义
--   4. 状态存英文枚举值，Java 用 Enum 限制合法值
--   5. 启停 / 作废 / 取消 / 终止各自表达业务语义，不用统一软删除字段
--   6. userId=账号ID，elderId=老人档案ID，deviceId=设备主键，deviceNo=设备业务编号
-- ============================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ------------------------------------------------------------
-- 账号与老人档案
-- ------------------------------------------------------------

CREATE TABLE sys_user (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    username      VARCHAR(50)  NOT NULL,
    password_hash VARCHAR(100) NOT NULL COMMENT 'BCrypt 哈希，不回传业务页面',
    real_name     VARCHAR(50)  NOT NULL,
    phone         VARCHAR(20),
    email         VARCHAR(100),
    role          VARCHAR(20)  NOT NULL COMMENT 'COMMUNITY_ADMIN / ELDER / SERVICE_STAFF',
    enabled       TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '停用后不能登录与写入，保留历史记录',
    remark        VARCHAR(255),
    created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_sys_user_username (username),
    KEY idx_sys_user_role_enabled (role, enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='账号表：一个账号一个角色';

-- 老人档案与账号一对一
CREATE TABLE elder_profile (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id           BIGINT      NOT NULL COMMENT '对应 sys_user.id，该账号角色为 ELDER',
    birth_date        DATE        COMMENT '用出生日期代替易失真的年龄字段',
    gender            VARCHAR(10) COMMENT 'male / female / other',
    address           VARCHAR(255),
    emergency_contact VARCHAR(50) COMMENT '紧急联系人姓名，不自动成为系统用户',
    emergency_phone   VARCHAR(20),
    care_note         VARCHAR(500) COMMENT '照护备注，人工记录，不作为模型输入',
    created_at        DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at        DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_elder_profile_user (user_id),
    CONSTRAINT fk_elder_profile_user FOREIGN KEY (user_id) REFERENCES sys_user (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='老人档案表';

-- ------------------------------------------------------------
-- 健康记录与异常明细
-- ------------------------------------------------------------

CREATE TABLE health_record (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    elder_id      BIGINT       NOT NULL,
    heart_rate          INT           COMMENT '心率 次/分钟',
    systolic            DECIMAL(6, 1) COMMENT '收缩压 mmHg',
    diastolic           DECIMAL(6, 1) COMMENT '舒张压 mmHg',
    blood_sugar         DECIMAL(5, 2) COMMENT '血糖 mmol/L',
    body_temperature    DECIMAL(4, 1) COMMENT '体温 ℃',
    sleep_hours         DECIMAL(4, 1) COMMENT '睡眠时长 小时',
    measure_scene       VARCHAR(20)    COMMENT 'FASTING / POSTPRANDIAL / RANDOM / UNKNOWN',
    measured_at    DATETIME(3)  NOT NULL COMMENT '测量时间：CURRENT 取保存时刻，HISTORY 由用户填写',
    mode          VARCHAR(20)  NOT NULL DEFAULT 'CURRENT' COMMENT 'CURRENT / HISTORY',
    source        VARCHAR(20)  NOT NULL DEFAULT 'MANUAL' COMMENT 'MANUAL / DEMO',
    source_device_id BIGINT              COMMENT '设备采集时关联设备，仅 source=DEMO 场景使用',
    source_collected_at DATETIME(3)     COMMENT '设备采样时间，与保存时间区分',
    voided        TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '作废后不参与概览，但保留已发生的处置',
    void_reason   VARCHAR(255),
    voided_by     BIGINT,
    voided_at     DATETIME(3),
    created_by    BIGINT           COMMENT '录入人；设备自动采集时为空',
    request_id    VARCHAR(64)   COMMENT '防重键，同一次提交重试沿用',
    request_hash  VARCHAR(64)   COMMENT '标准化请求内容摘要',
    created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_health_record_creator_request (created_by, request_id),
    KEY idx_health_record_elder_measured (elder_id, measured_at, id),
    KEY idx_health_record_device_request (source_device_id, request_id),
    CONSTRAINT fk_health_record_elder FOREIGN KEY (elder_id) REFERENCES elder_profile (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='健康体征记录表';

-- 规则命中明细：保存数值、阈值、单位与规则版本快照，规则变更不改写历史
CREATE TABLE health_record_anomaly (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    health_record_id BIGINT      NOT NULL,
    rule_code      VARCHAR(50)  NOT NULL COMMENT '规则编码',
    rule_version   VARCHAR(20)  NOT NULL COMMENT '规则版本快照',
    metric         VARCHAR(30)  NOT NULL COMMENT 'heartRate / systolic / ...',
    measured_value DECIMAL(8, 2)         COMMENT '实际测量值',
    lower_bound    DECIMAL(8, 2)         COMMENT '下界，NULL 表示不限',
    upper_bound    DECIMAL(8, 2)         COMMENT '上界，NULL 表示不限',
    unit           VARCHAR(20)  NOT NULL COMMENT '单位',
    hit_at         DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    event_id       BIGINT       COMMENT '关联的当前告警事件；历史补录与作废记录可为空',
    UNIQUE KEY uk_health_anomaly_record_rule (health_record_id, rule_code),
    KEY idx_health_anomaly_event (event_id, id),
    CONSTRAINT fk_health_anomaly_record FOREIGN KEY (health_record_id) REFERENCES health_record (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='健康记录规则命中明细表';

-- ------------------------------------------------------------
-- 告警事件（健康命中 + 主动求助共用）
-- ------------------------------------------------------------

CREATE TABLE alert_event (
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_no           VARCHAR(32)  NOT NULL COMMENT '业务编号，AE 前缀',
    source_type        VARCHAR(20)  NOT NULL COMMENT 'HEALTH_ALERT / HELP_CALL',
    rule_code          VARCHAR(50)           COMMENT '健康事件的规则编码；主动求助为空',
    elder_id           BIGINT       NOT NULL,
    status             VARCHAR(30)  NOT NULL DEFAULT 'PENDING'
                       COMMENT 'PENDING / ASSIGNED / PROCESSING / CLOSED / CANCELLED',
    priority           VARCHAR(10)  NOT NULL DEFAULT 'NORMAL' COMMENT 'HIGH / NORMAL，仅用于待办排序',
    handler_id         BIGINT                COMMENT '当前处理人账号',
    occurrence_count   INT          NOT NULL DEFAULT 1 COMMENT '健康异常归并时的发生次数',
    response_due_at    DATETIME(3)           COMMENT '首次响应时限，创建时按配置计算',
    first_accepted_at  DATETIME(3)           COMMENT '首次接收时间，只写一次，转派不清空',
    closed_at          DATETIME(3),
    close_result       VARCHAR(30)           COMMENT 'RESOLVED / FALSE_ALARM / DATA_CORRECTION',
    close_note         VARCHAR(500),
    call_type          VARCHAR(50)           COMMENT '主动求助的事项类型',
    contact_phone      VARCHAR(20)           COMMENT '求助时确认的联系电话',
    description        VARCHAR(500),
    request_id         VARCHAR(64)           COMMENT '主动求助防重键；健康自动事件为空',
    request_hash       VARCHAR(64),
    version            INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本，状态修改时校验',
    created_by         BIGINT,
    created_at         DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at         DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_alert_event_no (event_no),
    UNIQUE KEY uk_alert_event_creator_request (created_by, request_id),
    KEY idx_alert_event_elder_rule_status (elder_id, rule_code, status),
    KEY idx_alert_event_status_due (status, response_due_at, id),
    KEY idx_alert_event_handler_status (handler_id, status, id),
    CONSTRAINT fk_alert_event_elder FOREIGN KEY (elder_id) REFERENCES elder_profile (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='告警事件表';

-- 事件处理时间线（追加式，不改写）
CREATE TABLE alert_event_log (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_id    BIGINT      NOT NULL,
    action      VARCHAR(30) NOT NULL COMMENT 'CREATE/CLAIM/ASSIGN/ACCEPT/TRANSFER/CLOSE/CANCEL/TIMEOUT',
    from_status VARCHAR(30),
    to_status   VARCHAR(30),
    operator_id BIGINT           COMMENT '系统动作为空',
    operator_source VARCHAR(20)  COMMENT 'USER / SYSTEM',
    reason      VARCHAR(500),
    created_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    KEY idx_alert_event_log_event (event_id, created_at, id),
    CONSTRAINT fk_alert_event_log_event FOREIGN KEY (event_id) REFERENCES alert_event (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='告警事件处理记录表';

-- ------------------------------------------------------------
-- 服务目录与时段
-- ------------------------------------------------------------

CREATE TABLE service_item (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    name         VARCHAR(100) NOT NULL,
    category     VARCHAR(50)  NOT NULL COMMENT 'HOUSEKEEPING / MEDICAL / DELIVERY / CULTURE',
    description  VARCHAR(500),
    price        DECIMAL(10, 2)        COMMENT '参考价格，不是收入',
    provider     VARCHAR(100),
    phone        VARCHAR(20),
    enabled      TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '下架只阻止新预约，不取消已有订单',
    sort_order   INT          NOT NULL DEFAULT 0,
    created_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='社区服务项目表';

-- 预约时段：每张订单固定占用一个名额
CREATE TABLE service_slot (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    service_id     BIGINT      NOT NULL,
    start_at       DATETIME(3) NOT NULL COMMENT '预约开始时间，创建时必须在未来',
    end_at         DATETIME(3) NOT NULL COMMENT '必须大于 start_at，同一服务的时段不可重叠',
    capacity       INT         NOT NULL DEFAULT 1 COMMENT '可接受的预约数，不等于人员排班',
    occupied_count INT         NOT NULL DEFAULT 0 COMMENT '已占用名额，0 <= occupied_count <= capacity',
    enabled        TINYINT(1)  NOT NULL DEFAULT 1 COMMENT '关闭后不受理新预约',
    created_at     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    KEY idx_service_slot_service_time (service_id, start_at, end_at),
    CONSTRAINT fk_service_slot_service FOREIGN KEY (service_id) REFERENCES service_item (id),
    CONSTRAINT ck_service_slot_capacity CHECK (capacity > 0),
    CONSTRAINT ck_service_slot_occupied CHECK (occupied_count >= 0 AND occupied_count <= capacity),
    CONSTRAINT ck_service_slot_time CHECK (end_at > start_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='服务预约时段表';

-- ------------------------------------------------------------
-- 预约履约与评价
-- ------------------------------------------------------------

CREATE TABLE service_order (
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_no           VARCHAR(32)   NOT NULL COMMENT '业务编号，SO 前缀',
    elder_id           BIGINT        NOT NULL,
    slot_id            BIGINT        NOT NULL,
    service_id         BIGINT        NOT NULL,
    staff_id           BIGINT                 COMMENT '当前受派服务人员账号',
    status             VARCHAR(30)  NOT NULL DEFAULT 'PENDING_ASSIGNMENT'
                       COMMENT 'PENDING_ASSIGNMENT / ASSIGNED / ACCEPTED / IN_SERVICE /
                                PENDING_CONFIRMATION / COMPLETED / CANCELLED / TERMINATED',
    service_name_snapshot   VARCHAR(100)  NOT NULL COMMENT '服务名快照，改价不影响历史订单',
    price_snapshot          DECIMAL(10, 2)  NOT NULL COMMENT '参考价格快照，不代表实收',
    slot_start_snapshot     DATETIME(3)   NOT NULL,
    slot_end_snapshot       DATETIME(3)   NOT NULL,
    contact_name            VARCHAR(50)   NOT NULL,
    contact_phone           VARCHAR(20)   NOT NULL,
    address_snapshot        VARCHAR(255),
    service_result          VARCHAR(500) COMMENT '服务人员提交的结果说明',
    accepted_at             DATETIME(3),
    started_at              DATETIME(3),
    finished_at             DATETIME(3),
    confirmed_at            DATETIME(3),
    terminated_at           DATETIME(3),
    cancelled_at            DATETIME(3),
    cancel_reason           VARCHAR(255),
    version                 INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    request_id              VARCHAR(64),
    request_hash            VARCHAR(64),
    created_at              DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at              DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_service_order_no (order_no),
    UNIQUE KEY uk_service_order_creator_request (elder_id, request_id),
    KEY idx_service_order_elder_created (elder_id, created_at, id),
    KEY idx_service_order_staff_status_slot (staff_id, status, slot_start_snapshot),
    KEY idx_service_order_status_created (status, created_at, id),
    CONSTRAINT fk_service_order_elder FOREIGN KEY (elder_id) REFERENCES elder_profile (id),
    CONSTRAINT fk_service_order_slot  FOREIGN KEY (slot_id) REFERENCES service_slot (id),
    CONSTRAINT fk_service_order_service FOREIGN KEY (service_id) REFERENCES service_item (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='服务预约订单表';

CREATE TABLE service_order_log (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id    BIGINT      NOT NULL,
    action      VARCHAR(30) NOT NULL COMMENT 'CREATE/ASSIGN/ACCEPT/REJECT/TRANSFER/START/SUBMIT/CONFIRM/RETURN/CANCEL/TERMINATE',
    from_status VARCHAR(30),
    to_status   VARCHAR(30),
    operator_id BIGINT           COMMENT '系统动作为空',
    operator_source VARCHAR(20)  COMMENT 'USER / SYSTEM',
    reason      VARCHAR(500),
    created_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    KEY idx_service_order_log_order (order_id, created_at, id),
    CONSTRAINT fk_service_order_log_order FOREIGN KEY (order_id) REFERENCES service_order (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='服务订单处理记录表';

-- 一单一评价，只对已完成订单开放
CREATE TABLE service_review (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id    BIGINT      NOT NULL,
    elder_id    BIGINT      NOT NULL COMMENT '评价人必须是订单所属老人',
    rating      TINYINT     NOT NULL COMMENT '1~5 分',
    content     VARCHAR(500)         COMMENT '按普通文本展示，不渲染 HTML',
    created_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_service_review_order (order_id),
    CONSTRAINT fk_service_review_order FOREIGN KEY (order_id) REFERENCES service_order (id),
    CONSTRAINT ck_service_review_rating CHECK (rating >= 1 AND rating <= 5)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='服务评价表';

-- ------------------------------------------------------------
-- 设备档案与内置模拟指令
-- ------------------------------------------------------------

CREATE TABLE device (
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    device_no          VARCHAR(50)  NOT NULL COMMENT '业务编号，有历史后不修改',
    device_name        VARCHAR(100) NOT NULL,
    device_type        VARCHAR(30)  NOT NULL COMMENT 'LIGHT / AIRCON / CURTAIN',
    elder_id           BIGINT                COMMENT '当前所属老人档案',
    access_mode        VARCHAR(20)  NOT NULL DEFAULT 'SIMULATED' COMMENT '首版固定 SIMULATED',
    location           VARCHAR(100),
    enabled            TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '业务停用，禁止新控制并取消有效指令',
    simulator_running  TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '管理员是否已启动内置虚拟设备',
    simulation_mode    VARCHAR(20)  NOT NULL DEFAULT 'NORMAL' COMMENT 'NORMAL / FAIL_NEXT / NO_ACK',
    simulated_power_status VARCHAR(10) NOT NULL DEFAULT 'OFF' COMMENT '虚拟设备自身状态',
    power_status       VARCHAR(10)           COMMENT '最近模拟心跳报告的状态，首次心跳前为空',
    last_seen_at       DATETIME(3),
    version            INT          NOT NULL DEFAULT 0,
    created_at         DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at         DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_device_no (device_no),
    KEY idx_device_elder (elder_id),
    KEY idx_device_simulator (simulator_running, enabled),
    CONSTRAINT fk_device_elder FOREIGN KEY (elder_id) REFERENCES elder_profile (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='设备档案表（含内置模拟器状态）';

-- 指令：提交只是 PENDING，后台调度执行后才变终态
CREATE TABLE device_command (
    id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
    device_id             BIGINT      NOT NULL,
    elder_id_snapshot     BIGINT               COMMENT '创建指令时的老人归属，转移归属不改写',
    requested_by          BIGINT      NOT NULL COMMENT '提交人，模拟执行不改写',
    request_id            VARCHAR(64) NOT NULL,
    request_hash          VARCHAR(64) NOT NULL COMMENT '设备ID+动作+目标状态的标准化摘要',
    action                VARCHAR(30) NOT NULL COMMENT 'SET_POWER，不使用 TOGGLE',
    target_power_status   VARCHAR(10) NOT NULL COMMENT 'ON / OFF',
    status                VARCHAR(20) NOT NULL DEFAULT 'PENDING'
                          COMMENT 'PENDING / SUCCEEDED / FAILED / TIMED_OUT / CANCELLED',
    result_power_status   VARCHAR(10)         COMMENT '执行后的虚拟开关状态',
    reason                VARCHAR(255),
    ready_at              DATETIME(3) NOT NULL COMMENT '允许执行的演示延迟时刻',
    expires_at            DATETIME(3) NOT NULL COMMENT '到期未确认按 TIMED_OUT 展示',
    simulated_executed_at DATETIME(3)         COMMENT '虚拟执行时刻，NO_ACK 也写，防止每轮重复执行',
    acknowledged_at       DATETIME(3),
    created_at            DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at            DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_device_command_actor_request (requested_by, request_id),
    KEY idx_device_command_device_status (device_id, status, expires_at, id),
    KEY idx_device_command_device_created (device_id, created_at, id),
    CONSTRAINT fk_device_command_device FOREIGN KEY (device_id) REFERENCES device (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='设备模拟控制指令表';

-- ------------------------------------------------------------
-- 通用管理操作日志（业务时间线在各自模块的 log 表）
-- ------------------------------------------------------------

CREATE TABLE operation_log (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    business_type VARCHAR(40) NOT NULL COMMENT 'ACCOUNT / SERVICE / DEVICE / SLOT',
    object_id     BIGINT,
    action        VARCHAR(40) NOT NULL,
    before_state  VARCHAR(255),
    after_state   VARCHAR(255),
    operator_id   BIGINT,
    operator_source VARCHAR(20) NOT NULL DEFAULT 'USER' COMMENT 'USER / SYSTEM',
    reason        VARCHAR(500),
    created_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    KEY idx_operation_log_type_object (business_type, object_id, created_at),
    KEY idx_operation_log_operator (operator_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='通用管理操作日志表';

SET FOREIGN_KEY_CHECKS = 1;
