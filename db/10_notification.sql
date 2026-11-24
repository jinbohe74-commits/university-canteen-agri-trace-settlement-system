-- ==========================================================================
-- 定向业务通知（替代 sys_event 的全员广播动态）
-- --------------------------------------------------------------------------
-- 背景：原「实时动态」是 AOP 把每次写操作广播给「除本人外的其余全部角色」，
-- 129 条里 125 条没有任何具体接收人，内容还是「姓名 + 固定动作 + 接口路径」。
-- 这里改成一条通知只发给业务上真正相关的人（订单所属农户 / 收货食堂 /
-- 对应角色的经办人），带业务单号、跳转路由、逐条已读状态。
-- 幂等：应用启动时也会以同样的 CREATE TABLE IF NOT EXISTS 补齐。
-- ==========================================================================
CREATE TABLE IF NOT EXISTS sys_notification (
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    biz_type        VARCHAR(40)  NOT NULL COMMENT '业务类型：SHIP/QUALITY/DEPART/ARRIVE/INBOUND/SETTLE/ORDER_NEW',
    biz_no          VARCHAR(60)  DEFAULT NULL COMMENT '关联业务单号（订单号/结算单号）',
    order_id        BIGINT       DEFAULT NULL COMMENT '关联订单，用于按单聚合',
    sender_id       BIGINT       DEFAULT NULL COMMENT '触发人',
    sender_name     VARCHAR(60)  DEFAULT NULL,
    sender_role     VARCHAR(30)  DEFAULT NULL,
    recipient_id    BIGINT       NOT NULL COMMENT '接收人 sys_user.id（定向核心）',
    recipient_name  VARCHAR(60)  DEFAULT NULL,
    recipient_role  VARCHAR(30)  DEFAULT NULL,
    title           VARCHAR(120) NOT NULL COMMENT '通知标题',
    content         VARCHAR(400) DEFAULT NULL COMMENT '通知正文，含真实业务数字',
    route           VARCHAR(160) DEFAULT NULL COMMENT '点击后跳转的前端路由',
    is_read         TINYINT      NOT NULL DEFAULT 0 COMMENT '0 未读 1 已读',
    read_time       DATETIME     DEFAULT NULL,
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_recipient (recipient_id, is_read, id),
    KEY idx_biz (biz_type, biz_no),
    KEY idx_order (order_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '定向业务通知表';

-- --------------------------------------------------------------------------
-- 幂等清理：早期版本把「食堂绑定账号」和「采购员角色」解析成同一人，
-- 一次业务事件会落两条完全一样的通知。只保留 id 最小的一条。
-- 判定键用 接收人 + 业务类型 + 单号 + 标题，不同订单/不同事件不会误删。
-- --------------------------------------------------------------------------
DELETE n
FROM sys_notification n
JOIN (
    SELECT recipient_id, biz_type, IFNULL(biz_no, '') AS bno, title, MIN(id) AS keep_id
    FROM sys_notification
    GROUP BY recipient_id, biz_type, IFNULL(biz_no, ''), title
    HAVING COUNT(*) > 1
) d ON n.recipient_id = d.recipient_id
   AND n.biz_type = d.biz_type
   AND IFNULL(n.biz_no, '') = d.bno
   AND n.title = d.title
   AND n.id > d.keep_id;
