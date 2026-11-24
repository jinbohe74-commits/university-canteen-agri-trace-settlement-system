INSERT INTO sys_notification
 (biz_type, biz_no, order_id, sender_id, sender_name, sender_role,
  recipient_id, recipient_name, recipient_role, title, content, route, is_read, create_time)
SELECT 'ARRIVE', o.order_no, o.id, 15, '赵质检', 'INSPECTOR', 10, '李建国', 'PURCHASER',
  '批次已送达待验收',
  CONCAT('订单 ', o.order_no, ' 已于今日送达第一食堂，请安排仓管验收并确认入库数量。'),
  '/purchaser/inbound', 0, NOW() - INTERVAL 6 MINUTE
FROM orders o ORDER BY o.id DESC LIMIT 1;

INSERT INTO sys_notification
 (biz_type, biz_no, order_id, sender_id, sender_name, sender_role,
  recipient_id, recipient_name, recipient_role, title, content, route, is_read, create_time)
SELECT 'QUALITY', o.order_no, o.id, 15, '赵质检', 'INSPECTOR', 10, '李建国', 'PURCHASER',
  '质检合格可入库',
  CONCAT('订单 ', o.order_no, ' 农残抽检结果合格，已生成溯源批次，可正常入库。'),
  '/purchaser/inbound', 0, NOW() - INTERVAL 25 MINUTE
FROM orders o ORDER BY o.id DESC LIMIT 1;

INSERT INTO sys_notification
 (biz_type, biz_no, order_id, sender_id, sender_name, sender_role,
  recipient_id, recipient_name, recipient_role, title, content, route, is_read, create_time)
SELECT 'DEPART', o.order_no, o.id, 15, '赵质检', 'INSPECTOR', 10, '李建国', 'PURCHASER',
  '配送已发车',
  CONCAT('订单 ', o.order_no, ' 已装车发运，预计 2 小时内送达，请留意接车。'),
  '/purchaser/inbound', 0, NOW() - INTERVAL 70 MINUTE
FROM orders o ORDER BY o.id DESC LIMIT 1;

INSERT INTO sys_notification
 (biz_type, biz_no, order_id, sender_id, sender_name, sender_role,
  recipient_id, recipient_name, recipient_role, title, content, route, is_read, create_time)
SELECT 'SETTLE', s.settlement_no, s.order_id, 17, '钱会计', 'ADMIN', 10, '李建国', 'PURCHASER',
  '结算单待审核',
  CONCAT('结算单 ', s.settlement_no, ' 已生成，请核对金额与扣款后提交审核。'),
  '/purchaser/orders', 0, NOW() - INTERVAL 150 MINUTE
FROM settlement s ORDER BY s.id DESC LIMIT 1;

INSERT INTO sys_notification
 (biz_type, biz_no, order_id, sender_id, sender_name, sender_role,
  recipient_id, recipient_name, recipient_role, title, content, route, is_read, create_time)
SELECT 'SHIP', o.order_no, o.id, 3, '王丰收', 'FARMER', 10, '李建国', 'PURCHASER',
  '农户已接单待发货',
  CONCAT('农户已确认订单 ', o.order_no, '，预计明日 6:00 前完成采摘并发货。'),
  '/purchaser/orders', 0, NOW() - INTERVAL 5 HOUR
FROM orders o ORDER BY o.id DESC LIMIT 1;
