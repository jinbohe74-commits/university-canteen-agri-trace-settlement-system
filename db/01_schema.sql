-- =====================================================================
--  面向高校食堂的农产品直供溯源与结算管理系统
--  数据库：canteen_trace   字符集：utf8mb4
--  共 25 张数据表，与《系统设计说明书》关系模式一一对应
-- =====================================================================

CREATE DATABASE IF NOT EXISTS canteen_trace
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_general_ci;

USE canteen_trace;

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

DROP TABLE IF EXISTS operation_log;
DROP TABLE IF EXISTS warning;
DROP TABLE IF EXISTS price_history;
DROP TABLE IF EXISTS credit_score;
DROP TABLE IF EXISTS evaluation;
DROP TABLE IF EXISTS trace_record;
DROP TABLE IF EXISTS settlement_item;
DROP TABLE IF EXISTS settlement;
DROP TABLE IF EXISTS inbound_record;
DROP TABLE IF EXISTS delivery_order;
DROP TABLE IF EXISTS quality_report;
DROP TABLE IF EXISTS order_item;
DROP TABLE IF EXISTS orders;
DROP TABLE IF EXISTS purchase_demand;
DROP TABLE IF EXISTS supply_source;
DROP TABLE IF EXISTS product;
DROP TABLE IF EXISTS product_category;
DROP TABLE IF EXISTS canteen;
DROP TABLE IF EXISTS farmer;
DROP TABLE IF EXISTS sys_config;
DROP TABLE IF EXISTS sys_user_role;
DROP TABLE IF EXISTS sys_role_menu;
DROP TABLE IF EXISTS sys_menu;
DROP TABLE IF EXISTS sys_role;
DROP TABLE IF EXISTS sys_user;

SET FOREIGN_KEY_CHECKS = 1;

-- ---------------------------------------------------------------------
-- 1. 用户表
-- ---------------------------------------------------------------------
CREATE TABLE sys_user (
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '用户主键',
    username        VARCHAR(50)  NOT NULL COMMENT '登录账号',
    password        VARCHAR(100) NOT NULL COMMENT 'BCrypt 加密密码',
    real_name       VARCHAR(50)  NOT NULL COMMENT '真实姓名',
    phone           VARCHAR(20)  DEFAULT NULL COMMENT '手机号',
    role_type       VARCHAR(20)  NOT NULL COMMENT '角色类型 ADMIN/FARMER/PURCHASER/INSPECTOR',
    avatar          VARCHAR(10)  DEFAULT NULL COMMENT '头像文字',
    status          TINYINT      NOT NULL DEFAULT 1 COMMENT '1 启用 0 停用',
    online          TINYINT      NOT NULL DEFAULT 0 COMMENT '1 在线 0 离线',
    login_fail      INT          NOT NULL DEFAULT 0 COMMENT '连续登录失败次数',
    lock_until      DATETIME     DEFAULT NULL COMMENT '锁定截止时间',
    last_login_time DATETIME     DEFAULT NULL COMMENT '最近登录时间',
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_username (username)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户表';

-- ---------------------------------------------------------------------
-- 2. 角色表
-- ---------------------------------------------------------------------
CREATE TABLE sys_role (
    id          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '角色主键',
    role_name   VARCHAR(50) NOT NULL COMMENT '角色名称',
    role_code   VARCHAR(30) NOT NULL COMMENT '角色编码',
    description VARCHAR(200) DEFAULT NULL COMMENT '角色说明',
    data_scope  VARCHAR(30) DEFAULT 'ALL' COMMENT '数据权限范围 ALL/CANTEEN/FARMER/SELF',
    create_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_role_code (role_code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '角色表';

-- ---------------------------------------------------------------------
-- 3. 菜单表
-- ---------------------------------------------------------------------
CREATE TABLE sys_menu (
    id        BIGINT      NOT NULL AUTO_INCREMENT COMMENT '菜单主键',
    parent_id BIGINT      NOT NULL DEFAULT 0 COMMENT '父级菜单',
    menu_name VARCHAR(50) NOT NULL COMMENT '菜单名称',
    menu_type CHAR(1)     NOT NULL DEFAULT 'M' COMMENT 'M 目录 C 菜单 B 按钮',
    path      VARCHAR(100) DEFAULT NULL COMMENT '路由地址',
    perms     VARCHAR(100) DEFAULT NULL COMMENT '权限标识',
    icon      VARCHAR(50) DEFAULT NULL COMMENT '图标',
    sort      INT         NOT NULL DEFAULT 0 COMMENT '排序',
    PRIMARY KEY (id),
    KEY idx_parent (parent_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '菜单表';

-- ---------------------------------------------------------------------
-- 4/5. 角色菜单、用户角色关联表
-- ---------------------------------------------------------------------
CREATE TABLE sys_role_menu (
    role_id BIGINT NOT NULL COMMENT '角色ID',
    menu_id BIGINT NOT NULL COMMENT '菜单ID',
    PRIMARY KEY (role_id, menu_id),
    CONSTRAINT fk_rm_role FOREIGN KEY (role_id) REFERENCES sys_role (id),
    CONSTRAINT fk_rm_menu FOREIGN KEY (menu_id) REFERENCES sys_menu (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '角色菜单关联表';

CREATE TABLE sys_user_role (
    user_id BIGINT NOT NULL COMMENT '用户ID',
    role_id BIGINT NOT NULL COMMENT '角色ID',
    PRIMARY KEY (user_id, role_id),
    CONSTRAINT fk_ur_user FOREIGN KEY (user_id) REFERENCES sys_user (id),
    CONSTRAINT fk_ur_role FOREIGN KEY (role_id) REFERENCES sys_role (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户角色关联表';

-- ---------------------------------------------------------------------
-- 6. 系统配置表（智能匹配权重、预警阈值、信用规则）
-- ---------------------------------------------------------------------
CREATE TABLE sys_config (
    id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '配置主键',
    config_key   VARCHAR(60)  NOT NULL COMMENT '配置键',
    config_value VARCHAR(200) NOT NULL COMMENT '配置值',
    config_group VARCHAR(30)  NOT NULL DEFAULT 'COMMON' COMMENT '分组 MATCH/CREDIT/WARNING/BUSINESS',
    value_type   VARCHAR(20)  NOT NULL DEFAULT 'string' COMMENT '值类型 number/string/bool',
    remark       VARCHAR(200) DEFAULT NULL COMMENT '备注说明',
    update_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_config_key (config_key)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '系统配置表';

-- ---------------------------------------------------------------------
-- 7. 农户/合作社表
-- ---------------------------------------------------------------------
CREATE TABLE farmer (
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '农户主键',
    farmer_no       VARCHAR(20)  NOT NULL COMMENT '农户编号 F9929',
    user_id         BIGINT       DEFAULT NULL COMMENT '关联用户',
    name            VARCHAR(100) NOT NULL COMMENT '农户/合作社名称',
    type            VARCHAR(20)  NOT NULL COMMENT '合作社/生产基地/个体农户',
    contact         VARCHAR(50)  DEFAULT NULL COMMENT '联系人',
    phone           VARCHAR(20)  DEFAULT NULL COMMENT '联系电话',
    main_category   VARCHAR(100) DEFAULT NULL COMMENT '主营品类',
    supply_capacity VARCHAR(50)  DEFAULT NULL COMMENT '供应能力',
    address         VARCHAR(200) DEFAULT NULL COMMENT '产地地址',
    cert_no         VARCHAR(100) DEFAULT NULL COMMENT '证件号',
    cert_file       VARCHAR(255) DEFAULT NULL COMMENT '证件文件',
    cert_expire     DATE         DEFAULT NULL COMMENT '证照到期日',
    distance_km     DECIMAL(6,1) NOT NULL DEFAULT 10.0 COMMENT '配送距离(km)',
    credit_score    DECIMAL(5,2) NOT NULL DEFAULT 80.00 COMMENT '信用分 0-100',
    status          VARCHAR(20)  NOT NULL DEFAULT '待审核' COMMENT '待审核/已认证/已停用',
    deal_count      INT          NOT NULL DEFAULT 0 COMMENT '累计交易次数',
    good_rate       DECIMAL(5,2) NOT NULL DEFAULT 100.00 COMMENT '好评率',
    join_time       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '入驻时间',
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_farmer_no (farmer_no),
    KEY idx_status (status),
    KEY idx_name (name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '农户/合作社表';

-- ---------------------------------------------------------------------
-- 8. 食堂表
-- ---------------------------------------------------------------------
CREATE TABLE canteen (
    id             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '食堂主键',
    canteen_no     VARCHAR(20)  NOT NULL COMMENT '食堂编号 C991',
    user_id        BIGINT       DEFAULT NULL COMMENT '关联采购员用户',
    name           VARCHAR(100) NOT NULL COMMENT '食堂名称',
    campus         VARCHAR(50)  NOT NULL COMMENT '所属校区',
    manager        VARCHAR(50)  DEFAULT NULL COMMENT '采购经理',
    phone          VARCHAR(20)  DEFAULT NULL COMMENT '联系电话',
    scale          INT          NOT NULL DEFAULT 1000 COMMENT '供餐规模(人次/日)',
    supplier_count INT          NOT NULL DEFAULT 0 COMMENT '合作供应商数',
    monthly_orders INT          NOT NULL DEFAULT 0 COMMENT '本月订单数',
    monthly_amount DECIMAL(12,2) NOT NULL DEFAULT 0 COMMENT '本月采购金额',
    status         VARCHAR(20)  NOT NULL DEFAULT '正常' COMMENT '正常/停用',
    create_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_canteen_no (canteen_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '食堂表';

-- ---------------------------------------------------------------------
-- 9. 农产品分类表
-- ---------------------------------------------------------------------
CREATE TABLE product_category (
    id            BIGINT      NOT NULL AUTO_INCREMENT COMMENT '分类主键',
    category_name VARCHAR(50) NOT NULL COMMENT '分类名称',
    category_code VARCHAR(20) NOT NULL COMMENT '分类编码',
    parent_id     BIGINT      NOT NULL DEFAULT 0 COMMENT '父分类',
    sort          INT         NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_category_code (category_code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '农产品分类表';

-- ---------------------------------------------------------------------
-- 10. 农产品表
-- ---------------------------------------------------------------------
CREATE TABLE product (
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '产品主键',
    product_no    VARCHAR(20)  NOT NULL COMMENT '产品编号 P9191',
    name          VARCHAR(100) NOT NULL COMMENT '农产品名称',
    category_id   BIGINT       NOT NULL COMMENT '产品分类',
    unit          VARCHAR(20)  NOT NULL DEFAULT 'kg' COMMENT '计量单位',
    avg_price     DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT '参考均价',
    price_min     DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT '近期最低价',
    price_max     DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT '近期最高价',
    farmer_count  INT          NOT NULL DEFAULT 0 COMMENT '供货农户数',
    storage_req   VARCHAR(50)  DEFAULT '冷链配送' COMMENT '储存要求',
    status        VARCHAR(20)  NOT NULL DEFAULT '在售' COMMENT '在售/停售',
    description   VARCHAR(255) DEFAULT NULL COMMENT '产品说明',
    create_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_product_no (product_no),
    KEY idx_category (category_id),
    CONSTRAINT fk_product_category FOREIGN KEY (category_id) REFERENCES product_category (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '农产品表';

-- ---------------------------------------------------------------------
-- 11. 货源表
-- ---------------------------------------------------------------------
CREATE TABLE supply_source (
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '货源主键',
    farmer_id     BIGINT       NOT NULL COMMENT '农户ID',
    product_id    BIGINT       NOT NULL COMMENT '农产品ID',
    quantity      DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT '可供数量',
    unit          VARCHAR(20)  NOT NULL DEFAULT 'kg' COMMENT '单位',
    price         DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT '单价',
    supply_start  DATE         DEFAULT NULL COMMENT '供应开始',
    supply_end    DATE         DEFAULT NULL COMMENT '供应结束',
    delivery_mode VARCHAR(30)  NOT NULL DEFAULT '现货·当日发' COMMENT '交货方式',
    storage_req   VARCHAR(50)  DEFAULT '冷链配送' COMMENT '储存要求',
    origin_remark VARCHAR(255) DEFAULT NULL COMMENT '产地备注',
    images        VARCHAR(500) DEFAULT NULL COMMENT '产品/质检照片',
    sold_quantity DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT '已成交数量',
    status        VARCHAR(20)  NOT NULL DEFAULT '正常' COMMENT '正常/已售罄/下架',
    create_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_farmer (farmer_id),
    KEY idx_product (product_id),
    CONSTRAINT fk_source_farmer FOREIGN KEY (farmer_id) REFERENCES farmer (id),
    CONSTRAINT fk_source_product FOREIGN KEY (product_id) REFERENCES product (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '货源表';

-- ---------------------------------------------------------------------
-- 12. 采购需求表
-- ---------------------------------------------------------------------
CREATE TABLE purchase_demand (
    id             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '需求主键',
    demand_no      VARCHAR(30)  NOT NULL COMMENT '需求编号',
    canteen_id     BIGINT       NOT NULL COMMENT '食堂ID',
    product_id     BIGINT       NOT NULL COMMENT '农产品ID',
    quantity       DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT '需求数量',
    expected_price DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT '期望单价',
    deadline       DATETIME     DEFAULT NULL COMMENT '期望交货时间',
    delivery_mode  VARCHAR(30)  DEFAULT '冷链配送' COMMENT '配送方式',
    settlement_mode VARCHAR(30) DEFAULT '月结' COMMENT '结算方式',
    quality_req    VARCHAR(255) DEFAULT NULL COMMENT '质量要求',
    quote_count    INT          NOT NULL DEFAULT 0 COMMENT '报价数',
    status         VARCHAR(20)  NOT NULL DEFAULT '处理中' COMMENT '处理中/已成交/已撤回',
    create_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_demand_no (demand_no),
    KEY idx_canteen (canteen_id),
    KEY idx_status (status),
    CONSTRAINT fk_demand_canteen FOREIGN KEY (canteen_id) REFERENCES canteen (id),
    CONSTRAINT fk_demand_product FOREIGN KEY (product_id) REFERENCES product (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '采购需求表';

-- ---------------------------------------------------------------------
-- 13. 订单表（version 乐观锁）
-- ---------------------------------------------------------------------
CREATE TABLE orders (
    id                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '订单主键',
    order_no          VARCHAR(50)  NOT NULL COMMENT '订单编号',
    demand_id         BIGINT       DEFAULT NULL COMMENT '来源需求ID',
    source_id         BIGINT       DEFAULT NULL COMMENT '成交货源ID',
    farmer_id         BIGINT       NOT NULL COMMENT '供货农户ID',
    canteen_id        BIGINT       NOT NULL COMMENT '采购食堂ID',
    product_id        BIGINT       NOT NULL COMMENT '农产品ID',
    quantity          DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT '下单数量',
    unit_price        DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT '成交单价',
    total_amount      DECIMAL(12,2) NOT NULL DEFAULT 0 COMMENT '订单总金额',
    status            INT          NOT NULL DEFAULT 10 COMMENT '订单状态码',
    batch_no          VARCHAR(50)  DEFAULT NULL COMMENT '溯源批次号',
    expected_delivery DATETIME     DEFAULT NULL COMMENT '期望交付时间',
    actual_delivery   DATETIME     DEFAULT NULL COMMENT '实际交付时间',
    remark            VARCHAR(255) DEFAULT NULL COMMENT '备注',
    version           INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    create_time       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_no (order_no),
    KEY idx_farmer (farmer_id),
    KEY idx_canteen (canteen_id),
    KEY idx_status (status),
    KEY idx_create_time (create_time),
    CONSTRAINT fk_orders_farmer FOREIGN KEY (farmer_id) REFERENCES farmer (id),
    CONSTRAINT fk_orders_canteen FOREIGN KEY (canteen_id) REFERENCES canteen (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '订单表';

-- ---------------------------------------------------------------------
-- 14. 订单明细表
-- ---------------------------------------------------------------------
CREATE TABLE order_item (
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '明细主键',
    order_id        BIGINT       NOT NULL COMMENT '订单ID',
    product_id      BIGINT       NOT NULL COMMENT '农产品ID',
    order_quantity  DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT '下单数量',
    actual_quantity DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT '实际入库数量',
    loss_quantity   DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT '损耗数量',
    unit_price      DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT '单价',
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_order (order_id),
    CONSTRAINT fk_item_order FOREIGN KEY (order_id) REFERENCES orders (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '订单明细表';

-- ---------------------------------------------------------------------
-- 15. 质检报告表
-- ---------------------------------------------------------------------
CREATE TABLE quality_report (
    id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '质检主键',
    order_id     BIGINT       NOT NULL COMMENT '订单ID',
    batch_no     VARCHAR(50)  NOT NULL COMMENT '批次号',
    inspector_id BIGINT       DEFAULT NULL COMMENT '质检员用户ID',
    inspector    VARCHAR(50)  DEFAULT NULL COMMENT '质检员姓名',
    sample_qty   DECIMAL(10,2) DEFAULT 0 COMMENT '抽样数量',
    result       VARCHAR(20)  NOT NULL COMMENT '合格/不合格',
    items        TEXT         COMMENT '检测项目 JSON',
    standards    TEXT         COMMENT '检测标准 JSON',
    report_file  VARCHAR(255) DEFAULT NULL COMMENT '报告文件',
    remark       VARCHAR(255) DEFAULT NULL COMMENT '备注',
    hash         VARCHAR(128) DEFAULT NULL COMMENT 'SHA-256 存证哈希',
    inspect_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '质检时间',
    PRIMARY KEY (id),
    KEY idx_order (order_id),
    KEY idx_batch (batch_no),
    CONSTRAINT fk_qr_order FOREIGN KEY (order_id) REFERENCES orders (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '质检报告表';

-- ---------------------------------------------------------------------
-- 16. 配送单表
-- ---------------------------------------------------------------------
CREATE TABLE delivery_order (
    id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '配送主键',
    delivery_no  VARCHAR(30)  NOT NULL COMMENT '配送单号',
    order_id     BIGINT       NOT NULL COMMENT '订单ID',
    vehicle      VARCHAR(30)  DEFAULT NULL COMMENT '车牌',
    driver       VARCHAR(30)  DEFAULT NULL COMMENT '司机',
    driver_phone VARCHAR(20)  DEFAULT NULL COMMENT '司机电话',
    temperature  DECIMAL(4,1) DEFAULT 6.0 COMMENT '车厢温度',
    mileage      DECIMAL(6,1) DEFAULT 0 COMMENT '全程里程',
    origin       VARCHAR(100) DEFAULT NULL COMMENT '起点',
    destination  VARCHAR(100) DEFAULT NULL COMMENT '终点',
    status       VARCHAR(20)  NOT NULL DEFAULT '配送中' COMMENT '待发车/配送中/已送达',
    start_time   DATETIME     DEFAULT NULL COMMENT '发车时间',
    arrive_time  DATETIME     DEFAULT NULL COMMENT '到达时间',
    traces       TEXT         COMMENT 'GPS 轨迹 JSON',
    create_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_delivery_no (delivery_no),
    KEY idx_order (order_id),
    CONSTRAINT fk_do_order FOREIGN KEY (order_id) REFERENCES orders (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '配送单表';

-- ---------------------------------------------------------------------
-- 17. 入库记录表（含损耗）
-- ---------------------------------------------------------------------
CREATE TABLE inbound_record (
    id                  BIGINT       NOT NULL AUTO_INCREMENT COMMENT '入库主键',
    order_id            BIGINT       NOT NULL COMMENT '订单ID',
    inspector_id        BIGINT       DEFAULT NULL COMMENT '质检配送员ID',
    operator_canteen_id BIGINT       NOT NULL COMMENT '操作食堂',
    order_quantity      DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT '下单数量',
    receive_quantity    DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT '实收数量',
    loss_quantity       DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT '损耗数量',
    loss_rate           DECIMAL(5,2) NOT NULL DEFAULT 0 COMMENT '损耗率(%)',
    is_partial          TINYINT      NOT NULL DEFAULT 0 COMMENT '是否部分收货',
    quality_result      VARCHAR(20)  DEFAULT '质检合格' COMMENT '质检结果',
    operator_name       VARCHAR(50)  DEFAULT NULL COMMENT '验收人',
    remark              VARCHAR(255) DEFAULT NULL COMMENT '损耗备注',
    inbound_time        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '入库时间',
    PRIMARY KEY (id),
    KEY idx_order (order_id),
    CONSTRAINT fk_ir_order FOREIGN KEY (order_id) REFERENCES orders (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '入库记录表';

-- ---------------------------------------------------------------------
-- 18. 结算单表
-- ---------------------------------------------------------------------
CREATE TABLE settlement (
    id             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '结算主键',
    settlement_no  VARCHAR(30)  NOT NULL COMMENT '结算单号',
    order_id       BIGINT       NOT NULL COMMENT '订单ID',
    farmer_id      BIGINT       NOT NULL COMMENT '农户ID',
    canteen_id     BIGINT       NOT NULL COMMENT '食堂ID',
    total_amount   DECIMAL(12,2) NOT NULL DEFAULT 0 COMMENT '订单金额',
    loss_deduction DECIMAL(12,2) NOT NULL DEFAULT 0 COMMENT '损耗扣款',
    deduction      DECIMAL(12,2) NOT NULL DEFAULT 0 COMMENT '质检/其他扣款',
    actual_amount  DECIMAL(12,2) NOT NULL DEFAULT 0 COMMENT '实际结算金额',
    status         VARCHAR(20)  NOT NULL DEFAULT '待审核' COMMENT '待审核/已结算/已驳回',
    auditor        VARCHAR(50)  DEFAULT NULL COMMENT '审核人',
    remark         VARCHAR(255) DEFAULT NULL COMMENT '备注',
    create_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    confirm_time   DATETIME     DEFAULT NULL COMMENT '确认时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_settlement_no (settlement_no),
    UNIQUE KEY uk_settlement_order (order_id),
    KEY idx_farmer (farmer_id),
    CONSTRAINT fk_st_order FOREIGN KEY (order_id) REFERENCES orders (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '结算单表';

-- ---------------------------------------------------------------------
-- 19. 结算明细表
-- ---------------------------------------------------------------------
CREATE TABLE settlement_item (
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '明细主键',
    settlement_id BIGINT       NOT NULL COMMENT '结算单ID',
    item_name     VARCHAR(100) NOT NULL COMMENT '项目名称',
    item_type     VARCHAR(20)  NOT NULL DEFAULT 'ADD' COMMENT 'ADD 收入 / SUB 扣款',
    amount        DECIMAL(12,2) NOT NULL DEFAULT 0 COMMENT '金额',
    remark        VARCHAR(200) DEFAULT NULL COMMENT '说明',
    PRIMARY KEY (id),
    KEY idx_settlement (settlement_id),
    CONSTRAINT fk_si_settlement FOREIGN KEY (settlement_id) REFERENCES settlement (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '结算明细表';

-- ---------------------------------------------------------------------
-- 20. 溯源记录表
-- ---------------------------------------------------------------------
CREATE TABLE trace_record (
    id                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '溯源主键',
    batch_no          VARCHAR(50)  NOT NULL COMMENT '批次号',
    order_id          BIGINT       NOT NULL COMMENT '订单ID',
    farmer_id         BIGINT       NOT NULL COMMENT '农户ID',
    product_id        BIGINT       NOT NULL COMMENT '农产品ID',
    quality_report_id BIGINT       DEFAULT NULL COMMENT '质检报告ID',
    delivery_id       BIGINT       DEFAULT NULL COMMENT '配送单ID',
    inbound_id        BIGINT       DEFAULT NULL COMMENT '入库记录ID',
    hash              VARCHAR(128) DEFAULT NULL COMMENT 'SHA-256 哈希（数据库简易存证）',
    qr_code           VARCHAR(255) DEFAULT NULL COMMENT '二维码内容',
    chain             TEXT         COMMENT '全链路节点 JSON',
    create_time       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_batch_no (batch_no),
    KEY idx_order (order_id),
    CONSTRAINT fk_tr_order FOREIGN KEY (order_id) REFERENCES orders (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '溯源记录表';

-- ---------------------------------------------------------------------
-- 21. 评价表
-- ---------------------------------------------------------------------
CREATE TABLE evaluation (
    id             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '评价主键',
    order_id       BIGINT       NOT NULL COMMENT '订单ID',
    canteen_id     BIGINT       NOT NULL COMMENT '食堂ID',
    farmer_id      BIGINT       NOT NULL COMMENT '农户ID',
    overall_score  DECIMAL(3,1) NOT NULL DEFAULT 5.0 COMMENT '总体评分',
    quality_score  DECIMAL(3,1) NOT NULL DEFAULT 5.0 COMMENT '产品品质',
    fresh_score    DECIMAL(3,1) NOT NULL DEFAULT 5.0 COMMENT '新鲜程度',
    delivery_score DECIMAL(3,1) NOT NULL DEFAULT 5.0 COMMENT '配送时效',
    service_score  DECIMAL(3,1) NOT NULL DEFAULT 5.0 COMMENT '服务态度',
    package_score  DECIMAL(3,1) NOT NULL DEFAULT 5.0 COMMENT '包装与分量',
    tags           VARCHAR(200) DEFAULT NULL COMMENT '评价标签',
    content        VARCHAR(500) DEFAULT NULL COMMENT '文字评价',
    create_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_eval_order (order_id),
    KEY idx_farmer (farmer_id),
    CONSTRAINT fk_ev_order FOREIGN KEY (order_id) REFERENCES orders (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '评价表';

-- ---------------------------------------------------------------------
-- 22. 信用评分表（变动明细）
-- ---------------------------------------------------------------------
CREATE TABLE credit_score (
    id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '信用明细主键',
    farmer_id    BIGINT       NOT NULL COMMENT '农户ID',
    order_id     BIGINT       DEFAULT NULL COMMENT '关联订单',
    before_score DECIMAL(5,2) NOT NULL DEFAULT 80 COMMENT '变动前分数',
    change_score DECIMAL(5,2) NOT NULL DEFAULT 0 COMMENT '变动分数',
    after_score  DECIMAL(5,2) NOT NULL DEFAULT 80 COMMENT '变动后分数',
    reason       VARCHAR(200) NOT NULL COMMENT '变更原因',
    create_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_farmer (farmer_id),
    CONSTRAINT fk_cs_farmer FOREIGN KEY (farmer_id) REFERENCES farmer (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '信用评分表';

-- ---------------------------------------------------------------------
-- 23. 价格行情表
-- ---------------------------------------------------------------------
CREATE TABLE price_history (
    id         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '行情主键',
    product_id BIGINT       NOT NULL COMMENT '农产品ID',
    stat_date  DATE         NOT NULL COMMENT '统计日期',
    avg_price  DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT '均价',
    min_price  DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT '最低价',
    max_price  DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT '最高价',
    source     VARCHAR(30)  DEFAULT '平台成交' COMMENT '数据来源',
    PRIMARY KEY (id),
    UNIQUE KEY uk_product_date (product_id, stat_date),
    CONSTRAINT fk_ph_product FOREIGN KEY (product_id) REFERENCES product (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '价格行情表';

-- ---------------------------------------------------------------------
-- 24. 预警表
-- ---------------------------------------------------------------------
CREATE TABLE warning (
    id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '预警主键',
    warning_no   VARCHAR(30)  NOT NULL COMMENT '预警编号',
    risk_level   VARCHAR(10)  NOT NULL DEFAULT '中' COMMENT '高/中/低',
    warning_type VARCHAR(20)  NOT NULL COMMENT '证照预警/质检预警/价格预警/逾期预警',
    target       VARCHAR(100) NOT NULL COMMENT '预警对象',
    content      VARCHAR(300) NOT NULL COMMENT '预警内容',
    status       VARCHAR(20)  NOT NULL DEFAULT '待处理' COMMENT '待处理/待确认/已完成/已忽略',
    trigger_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '触发时间',
    handler      VARCHAR(50)  DEFAULT NULL COMMENT '处理人',
    handle_time  DATETIME     DEFAULT NULL COMMENT '处理时间',
    handle_remark VARCHAR(255) DEFAULT NULL COMMENT '处理说明',
    PRIMARY KEY (id),
    UNIQUE KEY uk_warning_no (warning_no),
    KEY idx_type (warning_type),
    KEY idx_status (status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '预警表';

-- ---------------------------------------------------------------------
-- 25. 操作日志表
-- ---------------------------------------------------------------------
CREATE TABLE operation_log (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '日志主键',
    user_id     BIGINT       DEFAULT NULL COMMENT '操作人ID',
    operator    VARCHAR(50)  NOT NULL COMMENT '操作人',
    role_name   VARCHAR(50)  DEFAULT NULL COMMENT '角色名称',
    module      VARCHAR(50)  NOT NULL COMMENT '功能模块',
    content     VARCHAR(300) NOT NULL COMMENT '操作内容',
    ip          VARCHAR(50)  DEFAULT NULL COMMENT 'IP 地址',
    result      VARCHAR(10)  NOT NULL DEFAULT '成功' COMMENT '成功/失败',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_create_time (create_time),
    KEY idx_operator (operator)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '操作日志表';
