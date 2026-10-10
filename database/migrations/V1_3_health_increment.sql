-- 待授权增量；尚未执行。使用已核对的当前库，分别在开发库/测试库应用。
-- 执行前必须核对SELECT DATABASE()、备份、列/表不存在及重复设备键为0。
-- 无DROP、TRUNCATE、UPDATE旧记录；steps保持NULL，不把缺测填0。
ALTER TABLE health_record
    ADD COLUMN steps INT NULL COMMENT '当日累计步数，NULL缺测' AFTER sleep_hours,
    ADD CONSTRAINT ck_health_record_steps CHECK (steps IS NULL OR steps BETWEEN 0 AND 50000),
    ADD UNIQUE KEY uk_health_record_device_request (source_device_id, request_id);

CREATE TABLE health_device_config (
    device_id BIGINT PRIMARY KEY,
    sample_interval_seconds INT NOT NULL DEFAULT 10,
    sample_scenario VARCHAR(30) NOT NULL DEFAULT 'NORMAL',
    abnormal_metric VARCHAR(30) NULL,
    sample_sequence BIGINT NOT NULL DEFAULT 0,
    last_sample_at DATETIME(3) NULL,
    daily_step_date DATE NULL,
    daily_steps INT NOT NULL DEFAULT 0,
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_health_config_device FOREIGN KEY(device_id) REFERENCES device(id),
    CONSTRAINT ck_health_config_interval CHECK(sample_interval_seconds IN(5,10,30,60)),
    CONSTRAINT ck_health_config_scenario CHECK(sample_scenario IN('NORMAL','ABNORMAL_ONCE','ABNORMAL_CONTINUOUS')),
    CONSTRAINT ck_health_config_metric CHECK(
        (sample_scenario='NORMAL' AND abnormal_metric IS NULL) OR
        (sample_scenario IN('ABNORMAL_ONCE','ABNORMAL_CONTINUOUS') AND abnormal_metric IS NOT NULL
         AND abnormal_metric IN('HEART_RATE','SYSTOLIC','BODY_TEMPERATURE'))),
    CONSTRAINT ck_health_config_sequence CHECK(sample_sequence>=0),
    CONSTRAINT ck_health_config_steps CHECK(daily_steps BETWEEN 0 AND 50000)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='健康模拟设备采样配置';

-- device_type现为VARCHAR(30)，两个库均无类型CHECK，不需改类型或重写旧设备。
-- HEALTH_SENSOR由应用的固定枚举与业务分支控制，原三种家电值保留。
