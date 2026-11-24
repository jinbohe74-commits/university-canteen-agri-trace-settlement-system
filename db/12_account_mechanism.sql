-- ============================================================================
-- 账号与密码机制完善（2026-11-11）
--
-- 背景：原机制只写 sys_user + sys_user_role 两张表，
--   1) 角色关系依赖前端传 roleId，前端没拿到就建出"无角色账号"，菜单为空；
--   2) 农户/采购员账号没有和 farmer / canteen 档案绑定，
--      登录后 farmerId / canteenId 为 null，业务页直接 403 或看到全量数据；
--   3) 初始密码由管理员口头转交，没有任何"首次登录必须改密"的约束。
--
-- 本脚本只做增量字段，可在已有库上重复执行（先判断列是否存在由启动脚本负责）。
-- ============================================================================

ALTER TABLE sys_user
    ADD COLUMN need_change_pwd TINYINT NOT NULL DEFAULT 0
        COMMENT '1=需强制修改密码（新建账号或被管理员重置后置 1，用户自助改密后置 0）' AFTER status,
    ADD COLUMN pwd_update_time DATETIME NULL
        COMMENT '最后一次主动修改密码的时间' AFTER need_change_pwd;

-- 历史演示账号均为初始化密码，不做强制改密，避免演示环境被弹窗打断
UPDATE sys_user SET need_change_pwd = 0 WHERE need_change_pwd IS NULL;

-- 档案与账号是一对一：一个农户/食堂档案最多被一个登录账号占用。
-- 这里只加索引不加唯一约束，避免历史脏数据导致整个初始化脚本失败。
CREATE INDEX idx_farmer_user ON farmer (user_id);
CREATE INDEX idx_canteen_user ON canteen (user_id);
