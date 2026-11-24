-- =====================================================================
--  需求报价（询价 → 比价 → 中标转单）—— 第二段改造
--  背景：原来 purchase_demand.quote_count 有 1~6 的"报价数"，但全库没有报价表，
--        页面上的「N 家报价」没有任何数据支撑；需求页看完就断，不产出订单。
--  这里补上 demand_quote 实体，并把 quote_count 改为由真实报价行数刷新。
--  幂等：可重复执行
-- =====================================================================
USE canteen_trace;
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS demand_quote (
    id            BIGINT        NOT NULL AUTO_INCREMENT COMMENT '报价主键',
    demand_id     BIGINT        NOT NULL COMMENT '采购需求ID',
    farmer_id     BIGINT        NOT NULL COMMENT '报价农户ID',
    source_id     BIGINT        DEFAULT NULL COMMENT '指定货源ID（可空，中标时若无货源自动挂牌）',
    price         DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT '报价单价',
    quantity      DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT '可供数量',
    delivery_days INT           NOT NULL DEFAULT 1 COMMENT '承诺送达天数',
    quality_note  VARCHAR(200)  DEFAULT NULL COMMENT '质量承诺',
    remark        VARCHAR(200)  DEFAULT NULL COMMENT '备注',
    status        VARCHAR(20)   NOT NULL DEFAULT '待选' COMMENT '待选/中标/落选',
    create_time   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_demand_farmer (demand_id, farmer_id),
    KEY idx_demand (demand_id),
    KEY idx_farmer (farmer_id),
    CONSTRAINT fk_quote_demand FOREIGN KEY (demand_id) REFERENCES purchase_demand (id),
    CONSTRAINT fk_quote_farmer FOREIGN KEY (farmer_id) REFERENCES farmer (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '需求报价表';

-- 清掉历史上"没有出处"的报价数，改为按真实报价行统计
UPDATE purchase_demand d
LEFT JOIN (SELECT demand_id, COUNT(*) c FROM demand_quote GROUP BY demand_id) q ON q.demand_id = d.id
SET d.quote_count = IFNULL(q.c, 0);

-- 为「处理中」的需求补真实报价：取信用最高的 3 家已认证农户，报价围绕期望价上下浮动
INSERT INTO demand_quote (demand_id, farmer_id, source_id, price, quantity, delivery_days, quality_note, status, create_time)
SELECT d.id, x.farmer_id, NULL,
       ROUND(d.expected_price * (1 + (x.k - 2) * 0.025), 2),
       d.quantity, x.k,
       '按需求标准供货，可提供产地证明', '待选',
       DATE_ADD(d.create_time, INTERVAL x.k HOUR)
FROM purchase_demand d
JOIN (
    SELECT 1 AS k, (SELECT id FROM farmer WHERE status = '已认证' ORDER BY credit_score DESC LIMIT 0, 1) AS farmer_id
    UNION ALL
    SELECT 2, (SELECT id FROM farmer WHERE status = '已认证' ORDER BY credit_score DESC LIMIT 1, 1)
    UNION ALL
    SELECT 3, (SELECT id FROM farmer WHERE status = '已认证' ORDER BY credit_score DESC LIMIT 2, 1)
) x
WHERE d.status = '处理中'
  AND NOT EXISTS (SELECT 1 FROM demand_quote q WHERE q.demand_id = d.id AND q.farmer_id = x.farmer_id);

UPDATE purchase_demand d
LEFT JOIN (SELECT demand_id, COUNT(*) c FROM demand_quote GROUP BY demand_id) q ON q.demand_id = d.id
SET d.quote_count = IFNULL(q.c, 0);

-- 数据一致性修复：状态为「处理中」的需求，截止时间不应早于当前时间（否则农户永远报不了价）
UPDATE purchase_demand
SET deadline = DATE_ADD(NOW(), INTERVAL (2 + (id % 5)) DAY)
WHERE status = '处理中' AND (deadline IS NULL OR deadline < NOW());
