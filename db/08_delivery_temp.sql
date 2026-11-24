-- ==========================================================================
-- 冷链温度上报流水（2026-11-10）
-- --------------------------------------------------------------------------
-- 原来温度只有 delivery_order.temperature 一个字段，更新即覆盖，没有历史。
-- 动态地图要画温度曲线、要能证明"一路上都在 2~8℃"，必须有流水。
-- 每次温度上报（发车、手动更新、异常）写一条，地图上的曲线全部来自这里。
-- ==========================================================================
CREATE TABLE IF NOT EXISTS delivery_temp_log (
  id          BIGINT      NOT NULL AUTO_INCREMENT,
  delivery_id BIGINT      NOT NULL COMMENT '配送单 id',
  temperature DECIMAL(4,1) NOT NULL COMMENT '车厢温度(℃)',
  alarm       TINYINT     NOT NULL DEFAULT 0 COMMENT '1=超出 2~8℃ 范围',
  source      VARCHAR(20) NOT NULL DEFAULT 'manual' COMMENT 'depart=发车 / manual=手动上报 / sensor=设备上报',
  report_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_delivery_time (delivery_id, report_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='冷链车厢温度上报流水';

SELECT COUNT(*) AS 已有上报数 FROM delivery_temp_log;
