# 面向高校食堂的农产品直供溯源与结算管理系统

> 管理信息系统课程实验项目 · 高保真原型实现
> 四角色（系统管理员 / 农户·合作社 / 食堂采购员 / 质检配送员）× 九大子系统 × 25 张数据表

---

## 一、技术栈

| 层次 | 技术 |
| --- | --- |
| 后端 | Spring Boot 3.2.5、MyBatis-Plus 3.5.7、JWT（jjwt 0.12）、BCrypt、ZXing（二维码）、SHA-256 |
| 前端 | Vue 3.4、Vite 5、Element Plus 2.8、ECharts 5、Pinia、Vue Router 4、Axios |
| 数据库 | MySQL 8/9（库名 `canteen_trace`，utf8mb4，25 张表） |
| 运行环境 | JDK 17、Node 18+、Maven 3.9 |

## 二、目录结构

```
管理信息系统/
├─ backend/                     # 后端（Spring Boot）
│  ├─ pom.xml
│  └─ src/main/java/com/canteen/
│     ├─ common/                # Result / 分页 / JWT / 拦截器 / 异常 / 单号生成
│     ├─ config/                # MyBatis-Plus、Web（跨域、拦截器、静态资源）
│     ├─ entity/ mapper/        # 25 张表的实体与 Mapper
│     ├─ dto/ vo/               # 请求体与视图对象
│     ├─ service/               # 19 个业务服务（状态机、匹配、结算、溯源、看板…）
│     └─ controller/            # 认证、管理员、农户、采购员、质检员、公共、文件
├─ frontend/                    # 前端（Vue3）
│  └─ src/
│     ├─ api/                   # axios 封装 + 全部接口
│     ├─ components/            # StatCard、SectionCard、StatusTag、BaseChart、OrderDetailDrawer、TracePanel…
│     ├─ layout/                # 侧边导航 + 顶栏布局
│     ├─ router/ store/ styles/ utils/
│     └─ views/                 # 23+ 页面（admin / farmer / purchaser / inspector / login）
├─ db/
│  ├─ 01_schema.sql             # 25 张表 DDL（主外键、唯一约束、索引、乐观锁字段）
│  └─ 02_data.sql               # 初始化数据（326 订单、32 农户、220 农产品、12486 条日志…）
├─ tools/gen_data.py            # 初始化数据生成脚本（可重跑）
└─ docs/                        # 说明文档
```

## 三、快速启动

### 1. 数据库（已初始化，可直接使用；如需重建）

```powershell
$env:MYSQL_PWD='168168'
$mysql="C:\Program Files\MySQL\MySQL Server 9.6\bin\mysql.exe"
Get-Content db\01_schema.sql -Raw -Encoding UTF8 | & $mysql -uroot --default-character-set=utf8mb4
Get-Content db\02_data.sql  -Raw -Encoding UTF8 | & $mysql -uroot --default-character-set=utf8mb4
```

也可直接执行 `db\01_schema.sql`、`db\02_data.sql`（Navicat / DataGrip 中运行）。
数据库连接配置见 `backend/src/main/resources/application.yml`（账号 root / 密码 168168）。

### 2. 后端

```powershell
cd backend
mvn spring-boot:run
# 或：mvn -DskipTests package; java -jar target\canteen-trace.jar
```

启动后接口地址：`http://127.0.0.1:8080/api`，健康检查 `GET /api/common/health`。

### 3. 前端

```powershell
cd frontend
npm install          # 首次
npm run dev          # http://127.0.0.1:5173
```

> 若 Windows 上 `vite build` 出现 esbuild 临时文件 `Access is denied`，
> 先把临时目录指到工作区内：`$env:TEMP="$PWD\..\.work\tmp"; $env:TMP=$env:TEMP`，再执行。

### 4. 演示账号（密码统一 `123456`）

| 角色 | 账号 | 姓名 | 说明 |
| --- | --- | --- | --- |
| 系统管理员 | `admin` | 王管理 | 全量数据、参数配置、结算审核 |
| 农户/合作社 | `farmer24` | 张大山 | 绿源蔬菜合作社，信用 92 |
| 食堂采购员 | `purchaser01` | 李建国 | 第一食堂 |
| 质检配送员 | `inspector01` | 赵质检 | 质检中心 |
| 财务（管理员兼任） | `finance01` | 钱会计 | 结算审核 |
| 待审核农户 | `farmer29` | 赵守田 | 账号已停用，可在后台审核 |

登录页支持四类角色一键切换；连续输错 5 次锁定 10 分钟。

## 四、核心业务闭环

```
农户发布货源 → 食堂发布需求 → 五因子智能匹配(Top5) → 食堂下单 → 农户接单
→ 农户发货(待质检) → 质检录入(合格→生成批次号+二维码+SHA-256 存证)
→ 冷链配送(温度/轨迹) → 食堂扫码入库(实收数量→自动核算损耗) → 生成结算单
→ 管理员审核结算 → 采购评价(多维评分) → 信用分变动 → 订单完成
```

**订单状态机**（`orders.version` 乐观锁）：
`10 待接单 → 20 已接单 → 30 待质检 → 40 质检通过 / 41 质检不通过 → 50 配送中 → 60 已送达 → 70 已入库 → 80 待结算 → 90 已结算 → 100 已完成`，另有 `99 已取消`。

**生鲜损耗结算规则**：
- 订单金额 = 下单数量 × 成交单价
- 损耗数量 = 下单数量 − 实收数量；损耗扣款 = 损耗数量 × 成交单价
- 实际结算金额 = 订单金额 − 损耗扣款 − 质检扣款
- 示例：下单 400kg、实收 380kg、单价 ¥1.80 → 订单金额 ¥720.00、损耗扣款 ¥36.00、实际结算 ¥684.00

**五因子智能匹配**：
`综合得分 = 0.20×品类匹配 + 0.25×价格竞争力 + 0.15×数量满足度 + 0.25×农户信用 + 0.15×距离/历史合作`，
权重存储于 `sys_config`，管理员可在「系统参数配置」中调整，保存后即时生效。

**信用分规则**：初始 80 分；正常履约 +1、超时发货 −3、质检不合格 −5、损耗率超阈值 −2，范围 0~100，全程留痕。

**预警规则**：价格波动超 ±15%、订单逾期超 2 天、证照到期提前 30 天、损耗率超 5% 阈值，均可后台配置。

## 五、关键实现说明

1. **认证与权限**：JWT 无状态认证（`AuthInterceptor` 解析 token → `UserContext`），BCrypt 存储密码；
   角色菜单来自 RBAC 五表（`sys_user/sys_role/sys_menu/sys_user_role/sys_role_menu`），
   数据权限在 Service 层按角色拼接（农户只看自己、采购员只看本食堂、质检员看任务）。
2. **并发控制**：订单状态流转使用 `version` 乐观锁（`UPDATE ... WHERE id=? AND version=?`），
   冲突时返回 409「订单已被他人修改，请刷新后重试」。
3. **批次溯源**：批次号 = `PC` + 日期 + `F` + 农户ID + 产品ID + 随机数；
   质检合格后用 ZXing 生成二维码，对关键节点计算 SHA-256 存证哈希（数据库内简易存证，非区块链）。
4. **结算联动**：入库确认时按实收数量自动核算损耗 → 生成结算单与明细 → 订单流转至待结算 → 审核后已结算。
5. **安全细节**：登录失败 5 次锁定 10 分钟、文件上传后缀白名单 + 5MB 限制、全局异常统一返回、操作日志全量留痕。

## 六、接口一览（节选）

| 模块 | 方法 | 路径 |
| --- | --- | --- |
| 认证 | POST | `/api/auth/login`、`/logout`；GET `/api/auth/me`、`/roles` |
| 看板 | GET | `/api/admin/dashboard` |
| 档案 | GET/POST/PUT | `/api/admin/farmers`、`/canteens`、`/products`、`/users` |
| 订单 | GET | `/api/admin/orders`、`/orders/{id}` |
| 结算 | GET/POST | `/api/admin/settlements`、`/settlements/audit` |
| 预警 | GET/POST | `/api/admin/warnings`、`/warnings/{id}/handle` |
| 溯源 | GET | `/api/admin/trace?batchNo=` |
| 参数 | GET/PUT | `/api/admin/config` |
| 日志 | GET | `/api/admin/logs` |
| 农户 | GET/POST | `/api/farmer/workbench`、`/sources`、`/orders/{id}/accept` |
| 采购 | GET/POST | `/api/purchaser/demands`、`/match/{demandId}`、`/inbound` |
| 质检 | GET/POST | `/api/inspector/tasks`、`/quality`、`/deliveries` |

完整接口见 `docs/接口文档.md`。

## 七、页面清单（对齐原型 23 屏）

| 角色 | 页面 |
| --- | --- |
| 系统管理员 | 数据看板、农户/合作社管理、食堂档案、农产品档案、用户与权限、订单监管、结算审核、食安预警、批次溯源、系统参数配置、操作日志（11） |
| 农户/合作社 | 我的工作台、货源管理、订单管理、结算与收入、信用评价、消息提醒（6） |
| 食堂采购员 | 采购需求、货源大厅、智能匹配、我的订单、入库确认、采购评价、批次溯源（7） |
| 质检配送员 | 我的任务、质检录入、配送任务、异常上报、批次溯源（5） |
| 公共 | 登录页（1） |

## 八、自测记录

- 后端接口冒烟：**38 项全部通过**（含四角色登录、各模块列表/详情/统计），脚本 `tools/test_api.ps1`。
- 端到端流程：采购员发布需求 → 匹配 → 下单 → 农户接单（并发重复接单被乐观锁拦截）→ 发货 →
  质检合格生成批次与二维码 → 配送 → 送达 → 入库（实收 190/下单 200，自动核算损耗 10kg、5%）→
  结算单生成 → 审核 → 评价 → 订单完成 → 溯源可查（5 个链路节点、64 位 SHA-256 哈希），
  **29 项断言全部通过**，脚本 `tools/test_flow.ps1`。
- 前端页面巡检：四角色 **27 个路由全部通过**，控制台 0 报错、无 undefined/NaN、列表页均渲染真实数据，
  脚本 `tools/pw_check.cjs`。
- 布局审计：侧栏 232px、顶栏 60px、统计卡一行 4 张、表格行高 54~56px、主色 `#16a34a`、
  1440×900 下无横向溢出，与原型一致。
- 详细结果见 `docs/测试报告.md`。

## 九、文档与脚本

| 文件 | 说明 |
| --- | --- |
| `docs/数据库设计说明.md` | 25 张表清单、关键字段与约束、参数表、数据规模 |
| `docs/接口文档.md` | 全部 RESTful 接口与参数说明 |
| `docs/测试报告.md` | 接口/流程/页面/布局测试记录与结论 |
| `docs/screenshots/` | 28 张页面截图（登录页 + 四角色全部页面） |
| `tools/gen_data.py` | 初始化数据生成器（生成 `db/02_data.sql`） |
| `tools/test_api.ps1` | 接口冒烟测试 |
| `tools/test_flow.ps1` | 端到端业务流程测试 |
| `tools/pw_check.cjs` / `pw_shots.cjs` | 页面巡检 / 截图脚本（需 Playwright） |
| `start.bat` | 一键启动后端与前端并打开浏览器 |

## 十、常见问题

| 现象 | 处理 |
| --- | --- |
| 后端启动报 `Access denied for user 'root'` | 修改 `backend/src/main/resources/application.yml` 中的数据库密码 |
| 登录提示用户名或密码错误 | 统一密码为 `123456`；若账号被锁 10 分钟，可用管理员重置密码 |
| 前端 8080 接口 404 | 确认后端已启动（`GET /api/common/health`） |
| Windows 上 `vite build` 报 esbuild `Access is denied` | 先设置 `$env:TEMP="$PWD\..\.work\tmp"` 再执行 |
| 页面数据为空 | 确认已导入 `db/02_data.sql`（订单、农户、产品等基础数据） |

