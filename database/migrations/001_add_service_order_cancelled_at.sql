-- 001_add_service_order_cancelled_at.sql
--
-- 目的：service_order 表补 cancelled_at 列。
-- 原因：ServiceOrder 实体已有 cancelledAt 字段，但建表脚本中缺失该列，
--       取消订单时无法记录取消时刻。
--
-- 适用：已建过 service_order 表的数据库（空库直接用最新 schema.sql 即可）。
--
-- 执行前先确认列不存在，避免重复执行报错：
--   SELECT COLUMN_NAME FROM information_schema.COLUMNS
--   WHERE TABLE_SCHEMA = 'smart_community_care'
--     AND TABLE_NAME = 'service_order'
--     AND COLUMN_NAME = 'cancelled_at';
--
-- 刻意不用 AFTER terminated_at：部分环境的 service_order 表可能尚未有该列
-- （列的增补顺序不一定一致），写死 AFTER 会让迁移直接报
-- "Unknown column" 而失败。列顺序对业务无影响，不指定位置最稳。

ALTER TABLE service_order
    ADD COLUMN cancelled_at DATETIME(3) COMMENT '取消时刻';
