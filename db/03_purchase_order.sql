-- =====================================================================
--  采购单（一次下单多品项）—— 第一段改造
--  背景：原系统一次只能对单个货源下单，332 张订单 / 332 条明细 = 平均 1.0000 品项/单，
--        真实食堂一次要买几十个品项，等于要点几十次。这里引入「采购单 → 履约子单」两层：
--        采购单（purchase_order）是采购员的一次提交；订单（orders）仍是履约单元，
--        质检/配送/入库/结算/损耗全部挂在订单上，下游逻辑不受影响。
--  幂等：可重复执行
-- =====================================================================
USE canteen_trace;
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS purchase_order (
    id                BIGINT        NOT NULL AUTO_INCREMENT COMMENT '采购单主键',
    po_no             VARCHAR(40)   NOT NULL COMMENT '采购单号 CG+日期+序号',
    canteen_id        BIGINT        NOT NULL COMMENT '采购食堂ID',
    purchaser_id      BIGINT        DEFAULT NULL COMMENT '采购员用户ID',
    demand_id         BIGINT        DEFAULT NULL COMMENT '关联采购需求ID',
    item_count        INT           NOT NULL DEFAULT 0 COMMENT '品项数',
    total_amount      DECIMAL(12,2) NOT NULL DEFAULT 0 COMMENT '采购单总金额',
    expected_delivery DATETIME      DEFAULT NULL COMMENT '期望交付时间',
    status            VARCHAR(20)   NOT NULL DEFAULT '待接单' COMMENT '待接单/进行中/已完成/已取消',
    remark            VARCHAR(255)  DEFAULT NULL COMMENT '备注',
    create_time       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_po_no (po_no),
    KEY idx_canteen (canteen_id),
    KEY idx_create_time (create_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '采购单表（一次下单多品项）';

-- orders 增加所属采购单，可空以兼容历史单
SET @col := (SELECT COUNT(*) FROM information_schema.columns
             WHERE table_schema = 'canteen_trace' AND table_name = 'orders' AND column_name = 'po_id');
SET @sql := IF(@col = 0,
    'ALTER TABLE orders ADD COLUMN po_id BIGINT DEFAULT NULL COMMENT ''所属采购单ID'' AFTER order_no, ADD KEY idx_po (po_id)',
    'SELECT ''orders.po_id already exists'' AS msg');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 历史订单补采购单号：把每张历史订单视为一个单品的采购单，便于「我的订单」按采购单统一展示
INSERT INTO purchase_order (po_no, canteen_id, purchaser_id, demand_id, item_count, total_amount,
                            expected_delivery, status, remark, create_time)
SELECT CONCAT('CG', DATE_FORMAT(o.create_time, '%Y%m%d'), LPAD(o.id, 4, '0')),
       o.canteen_id, NULL, o.demand_id, 1, o.total_amount,
       o.expected_delivery,
       CASE WHEN o.status >= 100 THEN '已完成'
            WHEN o.status > 10 THEN '进行中'
            ELSE '待接单' END,
       '历史数据迁移',
       o.create_time
FROM orders o
WHERE o.po_id IS NULL
  AND NOT EXISTS (SELECT 1 FROM purchase_order p WHERE p.po_no = CONCAT('CG', DATE_FORMAT(o.create_time, '%Y%m%d'), LPAD(o.id, 4, '0')));

UPDATE orders o
JOIN purchase_order p ON p.po_no = CONCAT('CG', DATE_FORMAT(o.create_time, '%Y%m%d'), LPAD(o.id, 4, '0'))
SET o.po_id = p.id
WHERE o.po_id IS NULL;
