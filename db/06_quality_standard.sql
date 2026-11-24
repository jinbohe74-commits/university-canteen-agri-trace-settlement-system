-- ==========================================================================
-- 质检标准表（2026-11-10）
-- --------------------------------------------------------------------------
-- 背景：改造前质检页的检测项是写死在 Java 里的 6 条「农药残留 0.02 / 含水率 92%」，
-- 不管检的是白萝卜、马铃薯还是香菇，都是同一套值；而且前端根本没渲染输入框，
-- 质检员只要点「达标 / 不合格」再点结论就能提交 —— 这就是「随便点点就行」的根因。
--
-- 本表把检测项按「一级品类」下沉为数据：
--   judge_type = range → 录入数值，后端按 [min_value, max_value] 自动判定
--   judge_type = pass  → 感官项，人工判「合格 / 不合格」
--   judge_type = equal → 复核项，判「相符 / 不符」
-- 提交时后端以本表为准重新判定结论，前端传来的结论若与判定不一致直接拒绝。
--
-- 可重复执行（uk_cat_item 去重）。
-- ==========================================================================

CREATE TABLE IF NOT EXISTS quality_standard (
  id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  category_id BIGINT       NOT NULL COMMENT '一级品类 id（product_category.parent_id = 0）',
  item_name   VARCHAR(50)  NOT NULL COMMENT '检测项名称',
  method      VARCHAR(120) NULL     COMMENT '检测方法 / 判定依据',
  unit        VARCHAR(16)  NULL     COMMENT '计量单位',
  min_value   DECIMAL(10,3) NULL    COMMENT '合格下限，NULL 表示不限',
  max_value   DECIMAL(10,3) NULL    COMMENT '合格上限，NULL 表示不限',
  judge_type  VARCHAR(16)  NOT NULL DEFAULT 'range' COMMENT 'range=数值区间 / pass=感官 / equal=复核相符',
  required    TINYINT      NOT NULL DEFAULT 1 COMMENT '1=必检项，缺填不允许提交',
  sample_spec DECIMAL(8,2) NULL     COMMENT '该品类建议抽检量(kg)',
  sort        INT          NOT NULL DEFAULT 0 COMMENT '展示顺序',
  create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_cat_item (category_id, item_name),
  KEY idx_category (category_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='分品类质检标准（检测项与限值）';

-- ---------------------------------------------------------------- 种子数据
-- 用「品类 + 检测项」做幂等键，重跑只更新限值与说明，不产生重复行。
INSERT INTO quality_standard
  (category_id, item_name, method, unit, min_value, max_value, judge_type, required, sample_spec, sort)
VALUES
-- 叶菜类（1）：水分是新鲜度核心，腐烂率直接决定可食率
(1, '农药残留', '有机磷 / 氨基甲酸酯类快检（GB 2763）', 'mg/kg', NULL, 0.050, 'range', 1, 0.50, 10),
(1, '含水率',   '烘干法，低于限值说明失水萎蔫',           '%',     93.000, NULL, 'range', 1, 0.50, 20),
(1, '腐烂率',   '整批抽样子样中腐烂叶片重量占比',         '%',     NULL, 1.000, 'range', 1, 0.50, 30),
(1, '感官指标', '叶片挺括、色泽正常、无黄叶、无异味',      NULL,    NULL, NULL,  'pass',  1, 0.50, 40),
(1, '到货中心温度', '插入式温度计测菜心温度（冷链 2~8℃）', '℃',     2.000, 8.000, 'range', 1, 0.50, 50),
(1, '重量复核', '实秤重量与随货单核对',                   NULL,    NULL, NULL,  'equal', 1, 0.50, 60),

-- 根茎类（20）：看机械损伤与发芽/霉变，耐储但要防软化
(20, '农药残留', '有机磷 / 氨基甲酸酯类快检（GB 2763）',   'mg/kg', NULL, 0.050, 'range', 1, 1.00, 10),
(20, '重金属铅', '原子吸收法（GB 2762 限量 0.1）',         'mg/kg', NULL, 0.100, 'range', 1, 1.00, 20),
(20, '机械损伤率', '破皮、断裂、明显压伤个体重量占比',     '%',     NULL, 2.000, 'range', 1, 1.00, 30),
(20, '腐烂发芽率', '霉斑、软化、发芽个体重量占比',         '%',     NULL, 1.000, 'range', 1, 1.00, 40),
(20, '感官指标', '表皮完整、无霉斑、无软化、无异味',        NULL,    NULL, NULL,  'pass',  1, 1.00, 50),
(20, '到货中心温度', '插入式温度计测中心温度（2~8℃）',     '℃',     2.000, 8.000, 'range', 1, 1.00, 60),
(20, '重量复核', '实秤重量与随货单核对',                   NULL,    NULL, NULL,  'equal', 1, 1.00, 70),

-- 茄果类（35）：裂果与软化是主要损耗来源
(35, '农药残留', '有机磷 / 氨基甲酸酯类快检（GB 2763）',   'mg/kg', NULL, 0.050, 'range', 1, 0.50, 10),
(35, '腐烂率',   '霉烂、软化果重量占比',                   '%',     NULL, 1.000, 'range', 1, 0.50, 20),
(35, '机械损伤率', '裂果、压伤果重量占比',                 '%',     NULL, 3.000, 'range', 1, 0.50, 30),
(35, '感官指标', '无裂果、无软化、色泽均匀、无畸形',        NULL,    NULL, NULL,  'pass',  1, 0.50, 40),
(35, '到货中心温度', '插入式温度计测果心温度（2~8℃）',     '℃',     2.000, 8.000, 'range', 1, 0.50, 50),
(35, '重量复核', '实秤重量与随货单核对',                   NULL,    NULL, NULL,  'equal', 1, 0.50, 60),

-- 瓜果类（48）：含水高、失水快，另看表皮完整度
(48, '农药残留', '有机磷 / 氨基甲酸酯类快检（GB 2763）',   'mg/kg', NULL, 0.050, 'range', 1, 1.00, 10),
(48, '含水率',   '烘干法，低于限值说明失水糠心',           '%',     90.000, NULL, 'range', 1, 1.00, 20),
(48, '腐烂率',   '霉烂、水渍状果重量占比',                 '%',     NULL, 1.000, 'range', 1, 1.00, 30),
(48, '感官指标', '表皮完整、无凹陷、无苦味异味',            NULL,    NULL, NULL,  'pass',  1, 1.00, 40),
(48, '到货中心温度', '插入式温度计测果肉温度（2~8℃）',     '℃',     2.000, 8.000, 'range', 1, 1.00, 50),
(48, '重量复核', '实秤重量与随货单核对',                   NULL,    NULL, NULL,  'equal', 1, 1.00, 60),

-- 菌菇类（59）：泡水增重是行业常见掺假，含水率设上限而非下限
(59, '农药残留', '有机磷 / 氨基甲酸酯类快检（GB 2763）',   'mg/kg', NULL, 0.050, 'range', 1, 0.30, 10),
(59, '含水率',   '烘干法，高于限值疑泡水增重',             '%',     NULL, 92.000, 'range', 1, 0.30, 20),
(59, '杂质率',   '培养基碎屑、泥沙等杂质重量占比',         '%',     NULL, 0.500, 'range', 1, 0.30, 30),
(59, '感官指标', '菌盖完整、无黏液、无异味、无虫蛀',        NULL,    NULL, NULL,  'pass',  1, 0.30, 40),
(59, '到货中心温度', '插入式温度计测菇体温度（2~8℃）',     '℃',     2.000, 8.000, 'range', 1, 0.30, 50),
(59, '重量复核', '实秤重量与随货单核对',                   NULL,    NULL, NULL,  'equal', 1, 0.30, 60),

-- 有机菜（70）：有机认证要求更严，另需核验证书
(70, '农药残留', '有机标准快检（限量收紧至 0.01）',         'mg/kg', NULL, 0.010, 'range', 1, 0.50, 10),
(70, '有机证书核验', '证书编号与批次、供货主体一致且在有效期', NULL,  NULL, NULL,  'pass',  1, 0.50, 20),
(70, '含水率',   '烘干法，低于限值说明失水萎蔫',           '%',     93.000, NULL, 'range', 1, 0.50, 30),
(70, '腐烂率',   '腐烂叶片重量占比',                       '%',     NULL, 1.000, 'range', 1, 0.50, 40),
(70, '感官指标', '叶片挺括、色泽正常、无黄叶、无异味',      NULL,    NULL, NULL,  'pass',  1, 0.50, 50),
(70, '到货中心温度', '插入式温度计测菜心温度（2~8℃）',     '℃',     2.000, 8.000, 'range', 1, 0.50, 60),
(70, '重量复核', '实秤重量与随货单核对',                   NULL,    NULL, NULL,  'equal', 1, 0.50, 70)
AS new
ON DUPLICATE KEY UPDATE
  method      = new.method,
  unit        = new.unit,
  min_value   = new.min_value,
  max_value   = new.max_value,
  judge_type  = new.judge_type,
  required    = new.required,
  sample_spec = new.sample_spec,
  sort        = new.sort;

-- 校验
SELECT c.category_name AS 品类, COUNT(s.id) AS 检测项数,
       SUM(s.judge_type = 'range') AS 数值项,
       SUM(s.judge_type = 'pass')  AS 感官项,
       MAX(s.sample_spec)          AS 建议抽检量
FROM quality_standard s
JOIN product_category c ON c.id = s.category_id
GROUP BY s.category_id, c.category_name
ORDER BY s.category_id;
