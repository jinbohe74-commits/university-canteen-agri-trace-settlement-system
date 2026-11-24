-- ============================================================================
-- 2026-11-10 修复：信用分变动没有进「操作日志」，管理员无法追溯
-- ----------------------------------------------------------------------------
-- 症状：管理员在操作日志里搜农户姓名/「信用」，结果 0 条；
--       而 credit_score 表里有 251 条扣分/加分记录。
-- 根因：CreditService.change() 只写 credit_score + farmer.credit_score，
--       从未调用 LogService.record()，审计链在"谁动了这个农户的分"这一环断掉。
-- 处置：1) 代码侧补日志（CreditService 已改）；
--       2) 本脚本按已有 credit_score 记录回填历史日志，让历史同样可查。
-- 幂等：按 (module='信用评价' AND content LIKE '%订单%') 先删后插，可重复执行。
-- ============================================================================

DELETE FROM operation_log WHERE module = '信用评价';

INSERT INTO operation_log (user_id, operator, role_name, module, content, ip, result, create_time)
SELECT NULL,
       '系统',
       '系统自动',
       '信用评价',
       CONCAT('调整农户「', f.name, '」信用分 ',
              TRIM(TRAILING '.' FROM TRIM(TRAILING '0' FROM cs.before_score)), ' → ',
              TRIM(TRAILING '.' FROM TRIM(TRAILING '0' FROM cs.after_score)), '（',
              IF(cs.change_score > 0, '+', ''), TRIM(TRAILING '.' FROM TRIM(TRAILING '0' FROM cs.change_score)),
              ' 分）；原因：', cs.reason,
              IF(cs.order_id IS NULL, '', CONCAT('；关联订单 #', cs.order_id))),
       '127.0.0.1',
       '成功',
       cs.create_time
FROM credit_score cs
JOIN farmer f ON f.id = cs.farmer_id;
