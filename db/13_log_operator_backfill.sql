-- ============================================================================
-- 操作日志「操作人」回填（2026-11-16）
--
-- 背景：登录、登录失败两类日志发生在 token 签发之前，LogService 当时取不到
--   UserContext，只能落成「未知」；前端操作日志页的「操作人」列因此全是"未知"，
--   而同一行的「操作内容」里其实写着账号名。
--   （列表里 1948 条「未知」全部来自 登录 模块，业务操作日志不受影响）
--
-- 代码侧已改为由调用方显式传入操作人（LogService.record(actor, ...)）；
-- 本脚本只负责回填历史数据。幂等：只动 operator = '未知' 的登录类记录，可重复执行。
-- ============================================================================

-- 1) 内容里的账号能在 sys_user 找到的：回填真实姓名、用户 ID、角色中文名
UPDATE operation_log l
JOIN sys_user u
  ON u.username = TRIM(SUBSTRING_INDEX(SUBSTRING_INDEX(l.content, ' ', 2), ' ', -1))
SET l.operator  = COALESCE(NULLIF(TRIM(u.real_name), ''), u.username),
    l.user_id   = u.id,
    l.role_name = CASE u.role_type
                    WHEN 'ADMIN'     THEN '系统管理员'
                    WHEN 'FARMER'    THEN '农户/合作社'
                    WHEN 'PURCHASER' THEN '食堂采购员'
                    WHEN 'INSPECTOR' THEN '质检配送员'
                    ELSE '—'
                  END
WHERE l.operator = '未知'
  AND l.module = '登录'
  AND (l.content LIKE '用户 %' OR l.content LIKE '账号 %');

-- 2) 临时账号 / 已注销账号查不到档案的：至少还原当时使用的账号名，不再显示「未知」
UPDATE operation_log
SET operator = TRIM(SUBSTRING_INDEX(SUBSTRING_INDEX(content, ' ', 2), ' ', -1))
WHERE operator = '未知'
  AND module = '登录'
  AND (content LIKE '用户 %' OR content LIKE '账号 %')
  AND TRIM(SUBSTRING_INDEX(SUBSTRING_INDEX(content, ' ', 2), ' ', -1)) <> '';

-- 3) 自检：执行后应只剩无法解析账号的极少数历史记录（正常为 0）
SELECT COUNT(*) AS still_unknown FROM operation_log WHERE operator = '未知';

-- 回滚（如需）：无法精确还原，只能整体把登录类记录改回「未知」
-- UPDATE operation_log SET operator = '未知', user_id = NULL, role_name = '—' WHERE module = '登录';
