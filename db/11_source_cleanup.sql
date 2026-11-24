-- ============================================================================
-- 货源测试残留清理（2026-11-11，第六轮整改）
--
-- 背景：端到端闭环脚本 loop_procurement.cjs 每跑一次都会把农户 1 的「大白菜 66kg」
-- 货源上下架一次，留下 5 条 quantity=66、status=下架、且没有任何订单引用的历史行。
-- 农户货源页不筛状态时按 id 倒序，这些死数据会把在售货源压到第二页，看起来像
-- 「一堆重复的白萝卜 / 下架菜」，与真实经营情况不符。
--
-- 清理条件（三重收敛，宁可少删）：
--   1. status ∈ (下架, 已售罄)
--   2. 没有任何订单引用（orders.source_id 不指向它）——保证溯源链不断
--   3. 同一个 农户 + 品项 还有更新的同状态记录（保留最新那条）
--
-- 先备份到 supply_source_bak_20261111，可随时回滚。
-- ============================================================================

CREATE TABLE IF NOT EXISTS supply_source_bak_20261111 LIKE supply_source;

INSERT INTO supply_source_bak_20261111
SELECT s.*
FROM supply_source s
WHERE s.status IN ('下架', '已售罄')
  AND NOT EXISTS (SELECT 1 FROM orders o WHERE o.source_id = s.id)
  AND EXISTS (SELECT 1 FROM supply_source s2
              WHERE s2.farmer_id = s.farmer_id
                AND s2.product_id = s.product_id
                AND s2.id > s.id)
  AND NOT EXISTS (SELECT 1 FROM supply_source_bak_20261111 b WHERE b.id = s.id);

DELETE FROM supply_source
WHERE id IN (
    /* MySQL 不允许 DELETE 的同表在子查询里被直接引用，套一层派生表绕开 */
    SELECT id FROM (
        SELECT s.id
        FROM supply_source s
        WHERE s.status IN ('下架', '已售罄')
          AND NOT EXISTS (SELECT 1 FROM orders o WHERE o.source_id = s.id)
          AND EXISTS (SELECT 1 FROM supply_source s2
                      WHERE s2.farmer_id = s.farmer_id
                        AND s2.product_id = s.product_id
                        AND s2.id > s.id)
    ) t
);
