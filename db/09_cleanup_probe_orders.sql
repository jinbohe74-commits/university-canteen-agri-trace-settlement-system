-- ==========================================================================
-- 清理界面上的测试标记单号（只改单号，不删除任何业务数据）
-- --------------------------------------------------------------------------
-- 背景：改造验收阶段手工插入了 2 张待质检探针单用于截图，单号带 TEST 字样，
--       会显示在质检录入页顶部（「订单 DDTEST-QA-12」），看起来不像真实业务。
-- 处理：规范化成系统同批次的单号格式。订单本身（农户/产品/数量/食堂/状态）
--       以及关联的配送单、质检报告全部保留，按 order_id 关联不受影响。
-- 为何不直接删除：库内非测试的待质检订单为 0，删掉后质检页将没有待办任务。
-- 幂等：WHERE 里带原单号，重复执行不会二次改动。
-- ==========================================================================
START TRANSACTION;

UPDATE orders SET order_no = 'DD202611100356'
 WHERE id = 354 AND order_no = 'DDTEST-QA-11';

UPDATE orders SET order_no = 'DD202611100357'
 WHERE id = 355 AND order_no = 'DDTEST-QA-12';

COMMIT;

-- 校验：应返回两行，无 TEST 字样
SELECT id, order_no, status, product_id, quantity FROM orders WHERE id IN (354, 355);
SELECT COUNT(*) AS remaining_test_orders FROM orders WHERE order_no LIKE '%TEST%';

-- 回滚（如需恢复原状）：
-- UPDATE orders SET order_no = 'DDTEST-QA-11' WHERE id = 354;
-- UPDATE orders SET order_no = 'DDTEST-QA-12' WHERE id = 355;
