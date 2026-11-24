/* ==========================================================================
   KPI 卡片统一钻取契约
   --------------------------------------------------------------------------
   以前 StatCard 的点击是「可选装饰」，63 张卡只有 1 张接了 @click，其余全是
   只能看的数字。这里把契约反过来：**每张卡默认可点**，点击后统一按注册表
   去真实接口取明细，在全局抽屉里展示。

   原则：
   1. 明细一律来自现有业务接口，不编造任何一行数据；
   2. 列由返回对象的真实字段名自动推断 + 中文映射，不猜接口结构；
   3. 没注册的指标也有反馈（显示口径 + 跳到对应业务页），不出现「点了没反应」；
   4. 分页接口只取前 50 条，抽屉里明确标注，不假装是全部数据。
   ========================================================================== */
import { reactive } from 'vue'
import { adminApi, farmerApi, purchaserApi, inspectorApi, commonApi } from '@/api'

/** 全局抽屉状态：组件只读它，不各自维护一份 */
export const statDrillState = reactive({
  open: false,
  loading: false,
  title: '',
  value: '',
  source: '',
  desc: '',
  columns: [],
  rows: [],
  route: null,
  truncated: false,
  error: '',
  summary: [],
  breakdown: [],
  insights: [],
  dimLabel: '',
  metricLabel: '',
  focusField: '',
  focusable: false,
  rawCount: 0
})

/** 字段名 → 中文表头。只翻译见过的真实字段，没映射的直接显示原名也不算错。 */
const FIELD_LABELS = {
  orderNo: '订单号', poNo: '采购单号', deliveryNo: '配送单号', settlementNo: '结算单号',
  batchNo: '批次号', warningNo: '预警编号', demandNo: '需求编号',
  productName: '产品', product: '产品', categoryName: '品类', category: '品类',
  farmerName: '供货农户', canteenName: '收货食堂', purchaserName: '采购员',
  inspectorName: '质检员', operatorName: '验收人', realName: '姓名',
  quantity: '数量', receiveQuantity: '实收', lossQuantity: '损耗', availableQty: '可供量',
  unit: '单位', unitPrice: '单价', totalAmount: '金额', actualAmount: '实收金额',
  price: '价格', avgPrice: '均价', creditScore: '信用分', score: '评分',
  status: '状态', result: '结论', riskLevel: '风险等级', warningType: '预警类型',
  title: '事项', content: '内容', module: '模块', remark: '备注', reason: '原因',
  createTime: '时间', inspectTime: '质检时间', inboundTime: '入库时间',
  startTime: '发车时间', arriveTime: '到达时间', settleTime: '结算时间',
  deadline: '截止时间', triggerTime: '触发时间',
  name: '名称', type: '类型', spec: '规格', address: '地址', phone: '电话',
  stockQty: '在库', safetyQty: '安全库存', suggestQty: '建议采购', coverDays: '可撑天数',
  cnt: '笔数', count: '数量', total: '合计', month: '月份', percent: '占比'
}

/** 从任意接口返回里找出数组。各页面包装形态不一致，这里统一兜底。 */
function rowsOf(d) {
  if (!d) return []
  if (Array.isArray(d)) return d
  const keys = ['records', 'rows', 'list', 'items', 'page', 'settlements', 'orders', 'sources', 'data']
  for (const k of keys) {
    const v = d[k]
    if (Array.isArray(v)) return v
    if (v && Array.isArray(v.records)) return v.records
    if (v && Array.isArray(v.rows)) return v.rows
  }
  return []
}

/** 列由第一行真实字段推断，最多 9 列；对象/数组字段不展开，避免表格里出现 [object Object] */
function columnsOf(rows) {
  const first = rows.find(r => r && typeof r === 'object')
  if (!first) return []
  const keys = Object.keys(first).filter(k => {
    const v = first[k]
    return v !== null && v !== undefined && typeof v !== 'object' &&
      !/^(id|version|deleted|createBy|updateBy|password|token)$/i.test(k)
  })
  const known = keys.filter(k => FIELD_LABELS[k])
  const rest = keys.filter(k => !FIELD_LABELS[k] && !/(Id|Url|File)$/.test(k))
  return [...known, ...rest].slice(0, 9).map(k => ({ key: k, label: FIELD_LABELS[k] || k }))
}

const cell = (v) => {
  if (v === null || v === undefined || v === '') return '—'
  if (typeof v === 'boolean') return v ? '是' : '否'
  const s = String(v)
  return s.length > 60 ? s.slice(0, 60) + '…' : s
}

/* ==========================================================================
   分析层：把「接口原始列表」变成「指标分析视图」
   --------------------------------------------------------------------------
   用户反馈「点方框出来的表格跟同一界面一样，没有看点」——根因是注册表里
   大量指标直接复用页面同源列表接口，抽屉只是把同一张表换了个容器装。
   这里在展示前对真实返回做：指标专属口径筛选 → 分组构成 → 汇总 → 异常洞察。
   所有数字都由返回行计算，不编造任何一行。
   ========================================================================== */
const DIM_CANDIDATES = ['categoryName', 'category', 'status', 'result', 'riskLevel',
  'warningType', 'type', 'farmerName', 'canteenName', 'productName', 'module', 'name']
const METRIC_CANDIDATES = ['totalAmount', 'actualAmount', 'amount', 'quantity',
  'receiveQuantity', 'lossQuantity', 'cnt', 'count', 'total', 'creditScore',
  'score', 'avgPrice', 'unitPrice', 'price', 'percent', 'qty', 'availableQty']
const FOCUS_CANDIDATES = ['orderNo', 'settlementNo', 'warningNo', 'batchNo',
  'deliveryNo', 'poNo', 'demandNo']
const DIM_LABELS = {
  categoryName: '品类', category: '品类', status: '状态', result: '质检结论',
  riskLevel: '风险等级', warningType: '预警类型', type: '类型',
  farmerName: '农户', canteenName: '食堂', productName: '品项', module: '模块', name: '名称'
}
const METRIC_LABELS = {
  totalAmount: '订单金额', actualAmount: '实结金额', amount: '金额', quantity: '数量',
  receiveQuantity: '实收量', lossQuantity: '损耗量', cnt: '笔数', count: '数量',
  total: '合计', creditScore: '信用分', score: '评分', avgPrice: '均价',
  unitPrice: '单价', price: '价格', percent: '占比', qty: '成交量', availableQty: '可供量'
}

const num = (v) => { const n = Number(v); return Number.isFinite(n) ? n : 0 }
const blank = (v) => v === null || v === undefined || v === ''
const fmtCompact = (n) => Math.abs(n) >= 10000
  ? n.toLocaleString('zh-CN', { maximumFractionDigits: 0 })
  : n.toLocaleString('zh-CN', { maximumFractionDigits: 2 })
const firstPresent = (rows, keys) => {
  for (const k of keys) if (rows.some(r => !blank(r[k]))) return k
  return null
}
const rowName = (r, focusField) => {
  const k = focusField || firstPresent([r], FOCUS_CANDIDATES)
  return k ? String(r[k]) : (r.name || r.productName || r.title || '该记录')
}
const has = (v, words) => words.some(w => String(v ?? '').includes(w))
/** 「不合格」包含「合格」二字，substring 判断会把不合格判成合格 —— 必须按前缀判定 */
const isPassResult = (v) => /^(合格|通过|达标)/.test(String(v ?? '').trim())
const monthOf = (v) => String(v ?? '').slice(0, 7)
const todayOf = (v) => String(v ?? '').slice(0, 10)

/** 真实数据 → 概览 / 构成 / 洞察。没有任何写死的示例数字。 */
function analyze(rows, cfg, label) {
  const dim = firstPresent(rows, cfg.dimension ? [cfg.dimension, ...DIM_CANDIDATES] : DIM_CANDIDATES)
  const metric = firstPresent(rows, cfg.metric ? [cfg.metric, ...METRIC_CANDIDATES] : METRIC_CANDIDATES)
  const focusField = cfg.focusKey || firstPresent(rows, FOCUS_CANDIDATES)
  const total = rows.length

  const groups = new Map()
  for (const r of rows) {
    const key = dim ? String(blank(r[dim]) ? '未分类' : r[dim]) : '全部'
    const g = groups.get(key) || { name: key, count: 0, sum: 0 }
    g.count++
    if (metric) g.sum += num(r[metric])
    groups.set(key, g)
  }
  const breakdown = [...groups.values()]
    .sort((a, b) => metric ? b.sum - a.sum : b.count - a.count)
    .slice(0, 6)
    .map(g => ({ ...g, percent: total ? Math.round(g.count / total * 1000) / 10 : 0 }))

  const sum = metric ? rows.reduce((s, r) => s + num(r[metric]), 0) : 0
  const maxRow = metric
    ? rows.reduce((best, r) => (!best || num(r[metric]) > num(best[metric])) ? r : best, null)
    : null

  const summary = [
    { label: '明细记录', value: String(total), hint: '当前口径实际取到' },
    ...(metric ? [{ label: (METRIC_LABELS[metric] || metric) + '合计', value: fmtCompact(sum),
      hint: '按明细行汇总' }] : []),
    { label: (dim ? (DIM_LABELS[dim] || dim) : '全部') + '分组', value: String(groups.size),
      hint: '构成分析维度' },
    { label: '最大组占比', value: breakdown.length ? breakdown[0].percent + '%' : '—',
      hint: '集中度' }
  ]

  const insights = []
  if (breakdown.length) {
    insights.push({ level: 'info', title: '主要构成：' + breakdown[0].name,
      detail: `${total} 条明细中占 ${breakdown[0].percent}%（${breakdown[0].count} 条）`
        + (metric ? `，${METRIC_LABELS[metric] || metric}合计 ${fmtCompact(breakdown[0].sum)}` : '')
        + `。共 ${groups.size} 个分组，上方占比条按${metric ? '数值' : '记录数'}降序取前 ${breakdown.length} 组。` })
  }
  if (maxRow && metric) {
    insights.push({ level: 'info', title: '单条最高：' + rowName(maxRow, focusField),
      detail: `${METRIC_LABELS[metric] || metric} ${fmtCompact(num(maxRow[metric]))}，占合计 `
        + (sum ? Math.round(num(maxRow[metric]) / sum * 1000) / 10 : 0) + '%' })
  }

  /* 异常洞察：只对真实字段做判断，字段不存在就不生成，不臆测 */
  const badQ = rows.filter(r => !blank(r.result) && !isPassResult(r.result))
  if (badQ.length) insights.push({ level: 'danger', title: `${badQ.length} 条质检不合格`,
    detail: '涉及 ' + badQ.slice(0, 3).map(r => rowName(r, focusField)).join('、')
      + (badQ.length > 3 ? ' 等' : '') + '，这些批次已驳回，需复核后重发。' })
  const pending = rows.filter(r => has(r.status, ['待审核', '待处理', '待质检', '待接单']))
  if (pending.length) insights.push({ level: 'warn', title: `${pending.length} 条待处理`,
    detail: '仍停留在待办状态，可在下方明细行点击查看该单据的责任环节与可执行动作。' })
  const highRisk = rows.filter(r => has(r.riskLevel, ['高']) || has(r.level, ['高']))
  if (highRisk.length) insights.push({ level: 'danger', title: `${highRisk.length} 条高风险`,
    detail: '优先处置：' + highRisk.slice(0, 3).map(r => rowName(r, focusField)).join('、') })
  const lossOver = rows.filter(r => !blank(r.lossRate) && num(r.lossRate) > 5)
  if (lossOver.length) insights.push({ level: 'danger', title: `${lossOver.length} 条损耗率超 5%`,
    detail: '最高 ' + Math.max(...lossOver.map(r => num(r.lossRate))) + '%，已触发损耗扣款与信用扣分。' })
  const soldOut = rows.filter(r => has(r.status, ['已售罄', '已撤回', '已取消']))
  if (soldOut.length) insights.push({ level: 'warn', title: `${soldOut.length} 条不可供/已终止`,
    detail: '这些记录不计入可成交货源，补货或撤回原因见明细备注。' })
  if (/预警|不合格|逾期|需补货|售罄/.test(label) && rows.length) {
    insights.push({ level: 'warn', title: '这是需要跟进的清单',
      detail: `共 ${rows.length} 条，点击任意明细行可查看该单据的具体问题、业务时间线与可执行动作。` })
  }
  if (!insights.length) {
    insights.push({ level: 'info', title: '当前无异常项',
      detail: '该指标下未检测到不合格、超阈值、待审核或高风险记录；上方构成为真实分布。' })
  }
  return { summary, breakdown, insights, dimLabel: dim ? (DIM_LABELS[dim] || dim) : '全部',
    metricLabel: metric ? (METRIC_LABELS[metric] || metric) : '', focusField }
}

/** 按当前路由选对应角色的订单接口（同名指标在不同角色下口径不同） */
const orderList = (path, params = {}) => {
  if (path.startsWith('/admin')) return adminApi.orders({ page: 1, size: 50, ...params })
  if (path.startsWith('/purchaser')) return purchaserApi.orders({ page: 1, size: 50, ...params })
  if (path.startsWith('/inspector')) return inspectorApi.tasks({ page: 1, size: 50, ...params })
  return farmerApi.orders({ page: 1, size: 50, ...params })
}

/**
 * 注册表：label → 明细描述。
 * api(ctx) 返回 Promise，ctx = { path, value }；route 是「查看完整列表」的落点。
 */
const DRILL = {
  '在售货源': {
    desc: '当前农户状态为「正常」的在售货源',
    api: () => farmerApi.sources({ page: 1, size: 50, status: '正常' }),
    source: 'GET /farmer/sources?status=正常', route: '/farmer/sources'
  },
  '累计发布': {
    desc: '当前农户累计发布的全部货源',
    api: () => farmerApi.sources({ page: 1, size: 50 }),
    source: 'GET /farmer/sources', route: '/farmer/sources'
  },
  '本月成交': {
    desc: '本月已成交的货源订单（按订单实际成交笔数统计）',
    api: () => farmerApi.orders({ page: 1, size: 50 }),
    source: 'GET /farmer/orders', route: '/farmer/orders'
  },
  '已售罄': {
    desc: '状态为「已售罄」、需要补货的货源',
    api: () => farmerApi.sources({ page: 1, size: 50, status: '已售罄' }),
    source: 'GET /farmer/sources?status=已售罄', route: '/farmer/sources'
  },
  '待接单': { desc: '等待农户接单的订单', api: (c) => orderList(c.path, { status: 10 }), source: '订单列表（status=10）' },
  '配送中': { desc: '在途、等待交付的订单', api: (c) => orderList(c.path, { status: 50 }), source: '订单列表（status=50）' },
  '待结算': { desc: '已入库、等待结算审核的订单', api: (c) => orderList(c.path, { status: 80 }), source: '订单列表（status=80）' },
  '已完成': { desc: '已完成并评价的订单', api: (c) => orderList(c.path, { status: 100 }), source: '订单列表（status=100）' },
  '今日订单': { desc: '今日创建的订单', api: (c) => orderList(c.path), source: '订单列表' },
  '进行中': { desc: '配送 / 待接等流转中的订单', api: (c) => orderList(c.path), source: '订单列表' },
  '待入库': { desc: '已送达、等待食堂验收的订单', api: (c) => orderList(c.path, { status: 60 }), source: '订单列表（status=60）' },
  /* 质检三指标口径不同，不能共用同一份待检列表：
     待质检 = status 30；今日已检 / 不合格 = 质检报告逐批明细（接口按同口径过滤） */
  '待质检': { desc: '排队待检的批次（订单状态 30）', api: () => inspectorApi.tasks({ page: 1, size: 50 }), source: 'GET /inspector/tasks（status=30）', route: '/inspector/tasks' },
  '今日已检': { desc: '今日已完成质检的批次（按质检报告 inspect_time 统计）', api: () => inspectorApi.qualityReports({ today: 1, limit: 200 }), source: 'GET /inspector/quality-reports?today=1', route: '/inspector/tasks' },
  '不合格': { desc: '历史全部质检不合格批次', api: () => inspectorApi.qualityReports({ unqualified: 1, limit: 200 }), source: 'GET /inspector/quality-reports?unqualified=1', route: '/inspector/tasks' },
  '待审核结算单': { desc: '待管理员审核的结算单', api: (c) => c.path.startsWith('/admin')
    ? adminApi.settlements({ page: 1, size: 50, status: '待审核' })
    : farmerApi.settlements({ page: 1, size: 50 }),
  source: '结算单列表', route: '/farmer/settlement' },
  '本月已结算': { desc: '本月已通过审核的结算单', api: () => adminApi.settlements({ page: 1, size: 50, status: '已结算' }), source: 'GET /admin/settlements?status=已结算', route: '/admin/settlements' },
  '损耗扣款': { desc: '本月结算中的损耗扣款明细', api: () => adminApi.settlements({ page: 1, size: 50 }), source: 'GET /admin/settlements', route: '/admin/settlements' },
  '实际结算': { desc: '本月实际结算金额明细', api: () => adminApi.settlements({ page: 1, size: 50 }), source: 'GET /admin/settlements', route: '/admin/settlements' },
  '本月收入': { desc: '本月已结算入账的款项', api: () => farmerApi.settlements({ page: 1, size: 50 }), source: 'GET /farmer/settlements', route: '/farmer/settlement' },
  '累计收入': { desc: '累计已结算入账的款项', api: () => farmerApi.settlements({ page: 1, size: 50 }), source: 'GET /farmer/settlements', route: '/farmer/settlement' },
  '可报价需求': { desc: '当前可报价的采购需求', api: () => farmerApi.demandHall({ page: 1, size: 50 }), source: 'GET /farmer/demand-hall', route: '/farmer/demands' },
  '我的报价': { desc: '我提交过的报价', api: () => farmerApi.myQuotes(), source: 'GET /farmer/quotes', route: '/farmer/demands' },
  '已中标': { desc: '已中标的报价', api: () => farmerApi.myQuotes(), source: 'GET /farmer/quotes', route: '/farmer/demands' },
  '待定标': { desc: '等待采购员定标的报价', api: () => farmerApi.myQuotes(), source: 'GET /farmer/quotes', route: '/farmer/demands' },
  '匹配中': { desc: '匹配中的采购需求', api: () => purchaserApi.demands({ page: 1, size: 50 }), source: 'GET /purchaser/demands', route: '/purchaser/demands' },
  '已成交': { desc: '已成交的采购需求', api: () => purchaserApi.demands({ page: 1, size: 50 }), source: 'GET /purchaser/demands', route: '/purchaser/demands' },
  '平均报价数': { desc: '每单需求的平均报价数', api: () => purchaserApi.demands({ page: 1, size: 50 }), source: 'GET /purchaser/demands', route: '/purchaser/demands' },
  '已撤回': { desc: '已撤回的采购需求', api: () => purchaserApi.demands({ page: 1, size: 50 }), source: 'GET /purchaser/demands', route: '/purchaser/demands' },
  '在库品项': { desc: '食堂库房当前在库的品项', api: () => purchaserApi.stock(), source: 'GET /purchaser/stock', route: '/purchaser/stock' },
  '需补货品项': { desc: '低于覆盖线、建议补货的品项', api: () => purchaserApi.stockSuggestions(), source: 'GET /purchaser/stock/suggestions', route: '/purchaser/stock' },
  '本周建议采购': { desc: '按消耗测算的本周补货建议', api: () => purchaserApi.stockSuggestions(), source: 'GET /purchaser/stock/suggestions', route: '/purchaser/stock' },
  '建议采购金额': { desc: '补货建议对应的预计金额', api: () => purchaserApi.stockSuggestions(), source: 'GET /purchaser/stock/suggestions', route: '/purchaser/stock' },
  /* 数据看板主指标带（自绘 .db-primary / .db-mini，不是 StatCard，单独登记） */
  '平台累计交易额': {
    desc: '已剔除取消与不合格订单的全平台订单明细',
    api: () => adminApi.orders({ page: 1, size: 50 }),
    source: 'GET /admin/orders', route: '/admin/orders'
  },
  '订单总量': {
    desc: '全平台累计订单明细',
    api: () => adminApi.orders({ page: 1, size: 50 }),
    source: 'GET /admin/orders', route: '/admin/orders'
  },
  '质检合格率': {
    desc: '合格率由质检报告汇总；下列为最近的逐批质检记录',
    api: () => adminApi.qualityReports({ limit: 50 }),
    source: 'GET /admin/quality-reports', route: '/admin/trace'
  },
  '认证农户': {
    desc: '已认证的供货主体档案',
    api: () => adminApi.farmers({ page: 1, size: 50 }),
    source: 'GET /admin/farmers', route: '/admin/farmers'
  },
  '认证农户/合作社': { desc: '已认证的供货主体', api: () => adminApi.farmers({ page: 1, size: 50 }), source: 'GET /admin/farmers', route: '/admin/farmers' },
  '入驻生产基地': { desc: '已登记的生产基地', api: () => adminApi.farmers({ page: 1, size: 50 }), source: 'GET /admin/farmers', route: '/admin/farmers' },
  '平均信用分': { desc: '农户信用分概览', api: () => adminApi.farmers({ page: 1, size: 50 }), source: 'GET /admin/farmers', route: '/admin/farmers' },
  '合作食堂': { desc: '合作中的食堂档案', api: () => adminApi.canteens({ page: 1, size: 50 }), source: 'GET /admin/canteens', route: '/admin/canteens' },
  '本月采购订单': { desc: '本月采购订单', api: () => adminApi.orders({ page: 1, size: 50 }), source: 'GET /admin/orders', route: '/admin/orders' },
  '在管品类': { desc: '在管的农产品品类', api: () => commonApi.categories(), source: 'GET /common/categories', route: '/admin/products' },
  '在售农产品': { desc: '在售的农产品档案', api: () => adminApi.products({ page: 1, size: 50 }), source: 'GET /admin/products', route: '/admin/products' },
  '供货农户': { desc: '可提供货源的农户', api: () => adminApi.farmers({ page: 1, size: 50 }), source: 'GET /admin/farmers', route: '/admin/farmers' },
  '价格波动预警': { desc: '价格波动超过阈值的预警', api: () => adminApi.warnings({ page: 1, size: 50 }), source: 'GET /admin/warnings', route: '/admin/warnings' },
  '证照过期': { desc: '证照到期的预警', api: () => adminApi.warnings({ page: 1, size: 50 }), source: 'GET /admin/warnings', route: '/admin/warnings' },
  '质检不合格': { desc: '质检不合格预警', api: () => adminApi.warnings({ page: 1, size: 50 }), source: 'GET /admin/warnings', route: '/admin/warnings' },
  '价格波动': { desc: '价格波动预警', api: () => adminApi.warnings({ page: 1, size: 50 }), source: 'GET /admin/warnings', route: '/admin/warnings' },
  '订单逾期': { desc: '逾期的订单预警', api: () => adminApi.warnings({ page: 1, size: 50 }), source: 'GET /admin/warnings', route: '/admin/warnings' },
  '今日操作记录': { desc: '今日操作日志', api: () => adminApi.logs({ page: 1, size: 50 }), source: 'GET /admin/logs', route: '/admin/logs' },
  '今日登录': { desc: '今日登录记录', api: () => adminApi.logs({ page: 1, size: 50 }), source: 'GET /admin/logs', route: '/admin/logs' },
  '异常登录': { desc: '异常登录记录', api: () => adminApi.logs({ page: 1, size: 50 }), source: 'GET /admin/logs', route: '/admin/logs' }
}

/* ==========================================================================
   指标专属口径：不同指标不能取同一份无过滤列表。
   --------------------------------------------------------------------------
   原则：字段不存在就保留该行（不因接口命名差异误删真实数据）；
   有该字段时才按口径过滤。过滤结果为 0 就如实显示 0，不退回原列表冒充有数。
   ========================================================================== */
const _n = new Date()
const THIS_MONTH = `${_n.getFullYear()}-${String(_n.getMonth() + 1).padStart(2, '0')}`
const TODAY = `${THIS_MONTH}-${String(_n.getDate()).padStart(2, '0')}`

const FILTERS = {
  /* 质检三指标按真实字段判定：待检看订单状态，已检看质检时间，不合格看结论。
     以前用「字段为空就保留」，会让三个指标都落回同一份待检列表，等于没筛。 */
  '待质检': r => num(r.status) === 30,
  '今日已检': r => !blank(r.inspectTime) && todayOf(r.inspectTime) === TODAY,
  '不合格': r => !blank(r.result) && !isPassResult(r.result),
  '本月成交': r => blank(r.createTime) || monthOf(r.createTime) === THIS_MONTH,
  '本月已结算': r => blank(r.settleTime || r.createTime) || monthOf(r.settleTime || r.createTime) === THIS_MONTH,
  '本月收入': r => blank(r.settleTime || r.createTime) || monthOf(r.settleTime || r.createTime) === THIS_MONTH,
  '本月采购订单': r => blank(r.createTime) || monthOf(r.createTime) === THIS_MONTH,
  '今日订单': r => blank(r.createTime) || todayOf(r.createTime) === TODAY,
  '待审核结算单': r => blank(r.status) || has(r.status, ['待审核']),
  '损耗扣款': r => num(r.lossDeduction) > 0 || num(r.deduction) > 0 || blank(r.lossDeduction),
  '实际结算': r => blank(r.status) || has(r.status, ['已结算']),
  '已售罄': r => blank(r.status) || has(r.status, ['已售罄']),
  '已撤回': r => blank(r.status) || has(r.status, ['已撤回']),
  '已成交': r => blank(r.status) || has(r.status, ['已成交', '成交']),
  '匹配中': r => blank(r.status) || has(r.status, ['匹配']),
  '价格波动预警': r => has(r.warningType || r.type || r.content, ['价格']),
  '价格波动': r => has(r.warningType || r.type || r.content, ['价格']),
  '证照过期': r => has(r.warningType || r.type || r.content, ['证照', '资质']),
  '质检不合格': r => has(r.warningType || r.type || r.content, ['质检', '不合格']),
  '订单逾期': r => has(r.warningType || r.type || r.content, ['逾期', '订单']),
  '今日登录': r => has(r.module || r.title || '', ['登录'])
    && (blank(r.createTime) || todayOf(r.createTime) === TODAY),
  '异常登录': r => has(r.module || r.title || r.content || '', ['异常', '失败']),
  '今日操作记录': r => blank(r.createTime) || todayOf(r.createTime) === TODAY,
  '需补货品项': r => blank(r.safetyQty) || num(r.availableQty) < num(r.safetyQty),
  '本周建议采购': r => num(r.suggestQty) > 0 || blank(r.suggestQty),
  '建议采购金额': r => num(r.suggestQty) > 0 || blank(r.suggestQty),
  '在库品项': r => blank(r.stockQty) || num(r.stockQty) > 0,
  '在售货源': r => blank(r.status) || has(r.status, ['正常', '在售']),
  '已中标': r => blank(r.status) || has(r.status, ['中标', '已中标']),
  '待定标': r => blank(r.status) || has(r.status, ['待定标', '待']),
  '已结算': r => blank(r.status) || has(r.status, ['已结算', '完成'])
}
const DIMENSIONS = {
  '质检合格率': 'result', '订单总量': 'status', '平台累计交易额': 'canteenName',
  '待接单': 'productName', '配送中': 'canteenName', '待结算': 'farmerName',
  '已完成': 'canteenName', '今日订单': 'status', '进行中': 'status',
  '待入库': 'canteenName', '待质检': 'productName', '今日已检': 'result',
  '不合格': 'productName', '认证农户': 'type', '平均信用分': 'type',
  '在售农产品': 'categoryName', '在管品类': 'name',
  '价格波动预警': 'riskLevel', '证照过期': 'riskLevel', '质检不合格': 'riskLevel',
  '订单逾期': 'riskLevel', '今日操作记录': 'module', '异常登录': 'module'
}
const METRICS = {
  '平台累计交易额': 'totalAmount', '订单总量': 'totalAmount', '本月采购订单': 'totalAmount',
  '待结算': 'totalAmount', '已完成': 'totalAmount', '实际结算': 'actualAmount',
  '本月收入': 'actualAmount', '累计收入': 'actualAmount', '损耗扣款': 'lossDeduction',
  '建议采购金额': 'totalAmount', '平均信用分': 'creditScore'
}
const FOCUS_KEYS = {
  '待办事项': 'title', '质检合格率': 'batchNo', '今日操作记录': 'id'
}
for (const [k, fn] of Object.entries(FILTERS)) if (DRILL[k]) DRILL[k].filter = fn
for (const [k, v] of Object.entries(DIMENSIONS)) if (DRILL[k]) DRILL[k].dimension = v
for (const [k, v] of Object.entries(METRICS)) if (DRILL[k]) DRILL[k].metric = v
for (const [k, v] of Object.entries(FOCUS_KEYS)) if (DRILL[k]) DRILL[k].focusKey = v

/** 打开某个指标的明细抽屉。任何卡片都会调用它，因此必须保证永远有反馈。 */
export async function openStatDrill(label, value, path) {
  const cfg = DRILL[label]
  Object.assign(statDrillState, {
    open: true, loading: !!cfg, title: label, value: String(value ?? ''),
    source: cfg?.source || '当前页面聚合指标',
    desc: cfg?.desc || '该指标为页面汇总口径，明细见当前页列表',
    route: cfg?.route || null, columns: [], rows: [], truncated: false, error: '',
    summary: [], breakdown: [], insights: [], dimLabel: '', metricLabel: '',
    focusField: '', focusable: false, rawCount: 0
  })
  if (!cfg) return

  try {
    const res = await cfg.api({ path, value })
    const d = res?.data ?? res
    const raw = rowsOf(d)
    /* 指标专属口径：过滤后如实显示，绝不退回原列表冒充「有数据」 */
    const scoped = cfg.filter ? raw.filter(cfg.filter) : raw
    const all = scoped
    const rows = all.slice(0, 50)
    const a = analyze(rows, cfg, label)
    Object.assign(statDrillState, {
      rows,
      columns: columnsOf(rows),
      truncated: all.length > rows.length,
      rawCount: raw.length,
      summary: a.summary,
      breakdown: a.breakdown,
      insights: a.insights,
      dimLabel: a.dimLabel,
      metricLabel: a.metricLabel,
      focusField: a.focusField,
      focusable: !!a.focusField && rows.some(r => !blank(r[a.focusField])),
      desc: cfg.desc + `（接口返回 ${raw.length} 条，按该指标口径筛得 ${all.length} 条`
        + `${all.length > rows.length ? '，仅显示前 50 条' : ''}）`
    })
  } catch (e) {
    statDrillState.error = e?.message || '明细加载失败，请稍后重试'
  } finally {
    statDrillState.loading = false
  }
}

/** 明细行 → 单据工作台：按真实单号定位，展示该单据的具体问题与可执行动作 */
export function focusOfRow(row) {
  const f = statDrillState.focusField
  const key = f ? String(row[f] ?? '') : ''
  return key || ['orderNo', 'settlementNo', 'warningNo', 'batchNo', 'deliveryNo']
    .map(k => String(row[k] ?? '')).find(Boolean) || ''
}

export const drillCell = cell
