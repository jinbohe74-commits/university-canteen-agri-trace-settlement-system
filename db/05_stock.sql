-- =====================================================================
--  库存、领用与采购建议 —— 第三段改造
--  背景：全库没有任何 stock/inventory 字段，系统不知道食堂还剩多少、昨天领用了多少，
--        所以"采购"只是买卖记录，做不出「按消耗生成采购建议」——而这才是采购系统的第一屏。
--  口径说明（避免造数）：
--    · 期初库存 = 近 30 天入库实收量，每一公斤都能对到 inbound_record 的入库单；
--    · 期初之后，库存只由两条真实流水驱动：入库确认 + 领用/损耗出库；
--    · 不生成任何"假定消耗"记录，没有出库流水时采购建议会明确提示「暂无消耗数据」。
--  幂等：可重复执行
-- =====================================================================
USE canteen_trace;
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS stock (
    id          BIGINT        NOT NULL AUTO_INCREMENT COMMENT '库存主键',
    canteen_id  BIGINT        NOT NULL COMMENT '食堂ID',
    product_id  BIGINT        NOT NULL COMMENT '农产品ID',
    quantity    DECIMAL(12,2) NOT NULL DEFAULT 0 COMMENT '在库数量',
    unit        VARCHAR(10)   DEFAULT 'kg' COMMENT '单位',
    safety_qty  DECIMAL(12,2) NOT NULL DEFAULT 0 COMMENT '安全库存',
    update_time DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_canteen_product (canteen_id, product_id),
    KEY idx_canteen (canteen_id),
    CONSTRAINT fk_stock_canteen FOREIGN KEY (canteen_id) REFERENCES canteen (id),
    CONSTRAINT fk_stock_product FOREIGN KEY (product_id) REFERENCES product (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '食堂库存表';

CREATE TABLE IF NOT EXISTS stock_out (
    id          BIGINT        NOT NULL AUTO_INCREMENT COMMENT '出库主键',
    canteen_id  BIGINT        NOT NULL COMMENT '食堂ID',
    product_id  BIGINT        NOT NULL COMMENT '农产品ID',
    quantity    DECIMAL(12,2) NOT NULL DEFAULT 0 COMMENT '出库数量',
    reason      VARCHAR(20)   NOT NULL DEFAULT '领用' COMMENT '领用/损耗/退货/盘点调整',
    operator    VARCHAR(50)   DEFAULT NULL COMMENT '经办人',
    remark      VARCHAR(200)  DEFAULT NULL COMMENT '备注',
    create_time DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_canteen_time (canteen_id, create_time),
    KEY idx_product (product_id),
    CONSTRAINT fk_out_canteen FOREIGN KEY (canteen_id) REFERENCES canteen (id),
    CONSTRAINT fk_out_product FOREIGN KEY (product_id) REFERENCES product (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '出库/领用流水表';

-- 期初库存：近 30 天入库实收量按（食堂, 品项）汇总
-- 历史没有出库流水，在库量会偏高；上线后用「领用出库/盘点调整」校正，不做任何假定消耗
INSERT INTO stock (canteen_id, product_id, quantity, unit, safety_qty)
SELECT o.canteen_id, o.product_id, SUM(r.receive_quantity), MAX(p.unit), 0
FROM inbound_record r
JOIN orders o ON o.id = r.order_id
LEFT JOIN product p ON p.id = o.product_id
WHERE r.inbound_time >= DATE_SUB(NOW(), INTERVAL 30 DAY)
GROUP BY o.canteen_id, o.product_id
ON DUPLICATE KEY UPDATE quantity = VALUES(quantity);
