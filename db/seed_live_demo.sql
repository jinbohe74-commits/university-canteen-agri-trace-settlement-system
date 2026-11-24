-- ============================================================================
-- 2026-11-10 补：给演示账号留"进行中的单"
-- ----------------------------------------------------------------------------
-- 问题：种子数据里所有订单都停在 已完成/已结算，农户 farmer01（丰禾农业合作社）
--       名下 30 单里没有一张待接单/待发货，六个状态标签点开全是 0。
--       用户以农户身份登录会认为"接单、发货这些功能坏了"，实际是无活可干。
-- 处置：补两张进行中的单（待接单 1、已接单 1），让状态机和跨角色通知可被真实体验。
-- 幂等：按固定 order_no 先删后插，重复执行不会叠加。
-- ============================================================================

DELETE FROM order_item WHERE order_id IN (SELECT id FROM orders WHERE order_no IN ('DD202611100901', 'DD202611100902'));
DELETE FROM orders WHERE order_no IN ('DD202611100901', 'DD202611100902');

-- 待接单：食堂刚下的单，等农户响应
INSERT INTO orders (order_no, demand_id, source_id, farmer_id, canteen_id, product_id, quantity, unit_price, total_amount, status, batch_no, expected_delivery, create_time, update_time, version)
VALUES ('DD202611100901', NULL, 25, 2, 1, 11, 200.00, 1.95, 390.00, 10, NULL, '2026-11-11 06:30:00', '2026-11-10 08:42:00', '2026-11-10 08:42:00', 0);

-- 已接单：农户已接单，待采摘发货
INSERT INTO orders (order_no, demand_id, source_id, farmer_id, canteen_id, product_id, quantity, unit_price, total_amount, status, batch_no, expected_delivery, create_time, update_time, version)
VALUES ('DD202611100902', NULL, 37, 2, 2, 75, 300.00, 2.60, 780.00, 20, NULL, '2026-11-11 05:30:00', '2026-11-10 07:15:00', '2026-11-10 09:05:00', 0);

-- 订单明细（损耗结算与质检抽样都依赖它，缺了会算出 0）
INSERT INTO order_item (order_id, product_id, order_quantity, actual_quantity, loss_quantity, unit_price, create_time)
SELECT o.id, o.product_id, o.quantity, 0, 0, o.unit_price, o.create_time
FROM orders o WHERE o.order_no IN ('DD202611100901', 'DD202611100902');
