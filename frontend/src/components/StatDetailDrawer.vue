<template>
  <el-drawer v-model="s.open" :title="s.title + ' · 指标分析'" size="920px" append-to-body class="sd-drawer">
    <div class="sd-head">
      <div class="sd-value">
        <span class="sd-k">当前值</span>
        <b>{{ s.value }}</b>
      </div>
      <div class="sd-desc">{{ s.desc }}</div>
      <div class="sd-source">数据来源：{{ s.source }}</div>
    </div>

    <div v-if="s.loading" class="sd-state">正在从业务接口读取明细并计算构成…</div>
    <div v-else-if="s.error" class="sd-state is-error">{{ s.error }}</div>
    <template v-else>
      <!-- 概览：由真实明细计算，不是把页面表格搬进来 -->
      <div class="sd-summary">
        <div v-for="(x, i) in s.summary" :key="i" class="sd-sum-item">
          <span>{{ x.label }}</span><b>{{ x.value }}</b><small>{{ x.hint }}</small>
        </div>
      </div>

      <div class="sd-sec">
        构成分析 <small>按{{ s.dimLabel }}分组，取前 {{ s.breakdown.length }} 组</small>
      </div>
      <div v-if="!s.breakdown.length" class="sd-empty">当前口径下没有记录</div>
      <div v-for="(g, i) in s.breakdown" :key="i" class="sd-bar-row">
        <span class="sd-bar-name" :title="g.name">{{ g.name }}</span>
        <div class="sd-bar"><i :style="{ width: g.percent + '%' }"></i></div>
        <span class="sd-bar-num mono">{{ g.count }} 条 · {{ g.percent }}%</span>
        <span v-if="s.metricLabel" class="sd-bar-num mono">{{ fmtCompact(g.sum) }}</span>
      </div>

      <div class="sd-sec">需要关注 <small>由明细真实字段计算</small></div>
      <div v-for="(x, i) in s.insights" :key="i" class="sd-insight" :class="'lv-' + x.level">
        <span class="sd-ins-bar"></span>
        <div>
          <b>{{ x.title }}</b>
          <div>{{ x.detail }}</div>
        </div>
      </div>

      <div class="sd-sec">
        明细记录 <small>共 {{ s.rows.length }} 行 · {{ s.focusable ? '点击任意一行查看该单据的具体问题' : '该指标为货源/档案类记录，无关联订单单据' }}</small>
      </div>
      <div v-if="!s.rows.length" class="sd-empty">
        该指标当前口径下没有明细记录（数值为 0，或业务上尚未产生）。
      </div>
      <template v-else>
        <div class="sd-table-hint">
          ↔ 表格可左右滚动查看全部列{{ s.focusable ? ' · 点击行下钻到单据工作台' : '' }}
        </div>
        <el-table :data="s.rows" size="small" border stripe max-height="330"
                  class="sd-table" scrollbar-always-on highlight-current-row
                  @row-click="openRow">
          <el-table-column v-for="c in s.columns.slice(0, 7)" :key="c.key"
                           :prop="c.key" :label="c.label" min-width="112" show-overflow-tooltip>
            <template #default="{ row }">{{ drillCell(row[c.key]) }}</template>
          </el-table-column>
          <el-table-column v-if="s.focusable" label="单据" width="92" fixed="right" align="center">
            <template #default="{ row }"><span class="sd-go">查看 ›</span></template>
          </el-table-column>
        </el-table>
        <div v-if="s.columns.length > 7" class="sd-note">
          该数据共 {{ s.columns.length }} 个字段，为避免列拥挤仅展示前 7 个；
          {{ s.focusable ? '点击该行可在单据工作台查看完整业务上下文。' : '可左右滚动查看，或点下方「查看完整列表」。' }}
        </div>
        <div v-if="s.truncated" class="sd-note">仅显示前 50 条，点击下方按钮查看完整列表与分页。</div>
      </template>
    </template>

    <template #footer>
      <div class="sd-foot">
        <el-button @click="s.open = false">关闭</el-button>
        <el-button v-if="s.route" type="primary" @click="goList">查看完整列表 →</el-button>
      </div>
    </template>
  </el-drawer>
</template>

<script setup>
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { statDrillState as s, drillCell, focusOfRow } from '@/utils/statDrill'
import { openWorkItem } from '@/utils/workItem'

const router = useRouter()
const fmtCompact = (n) => Number(n || 0).toLocaleString('zh-CN', { maximumFractionDigits: 2 })

const goList = () => {
  const to = s.route
  s.open = false
  if (to) router.push(to)
}

/** 明细行 → 单据工作台：显示这单到底有什么问题、可以做什么
 *  任何点不动的情况都必须给出原因：静默 return = 用户眼里「点了没反应」。 */
const openRow = async (row) => {
  if (!s.focusField) {              // 货源/档案/汇总口径：本来就没有业务单据可下钻
    ElMessage.info('该指标为档案或汇总口径，没有关联的业务单据，明细已在表中完整列出')
    return
  }
  const key = focusOfRow(row)
  if (!key) {
    ElMessage.info('这一行没有业务单号，无法定位单据；可点下方「查看完整列表」按单号查')
    return
  }
  await openWorkItem({ scope: 'drill', keyword: key, title: s.title + ' · ' + key })
}
</script>

<style scoped>
.sd-head {
  padding: 2px 0 12px;
  margin-bottom: 12px;
  border-bottom: 1px solid var(--line);
}
.sd-value { display: flex; align-items: baseline; gap: 10px; }
.sd-k { font-size: var(--fs-sm); color: var(--muted); }
.sd-value b { font-size: 26px; color: var(--ink); font-family: var(--font-num); }
.sd-desc { margin-top: 8px; font-size: var(--fs-sm); color: var(--ink-2); line-height: 1.6; }
.sd-source { margin-top: 4px; font-size: var(--fs-xs); color: var(--muted); font-family: var(--font-num); }
.sd-state { padding: 36px 12px; text-align: center; font-size: var(--fs-sm); color: var(--muted); }
.sd-state.is-error { color: var(--danger); }
.sd-summary { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 10px; }
.sd-sum-item { background: var(--surface-2); border-radius: var(--radius-sm); padding: 10px 12px; min-width: 0; }
.sd-sum-item span { display: block; font-size: var(--fs-xs); color: var(--muted); }
.sd-sum-item b { display: block; font-size: 19px; color: var(--ink); font-family: var(--font-num); margin-top: 3px; }
.sd-sum-item small { display: block; font-size: var(--fs-xs); color: var(--muted); margin-top: 2px; }
.sd-sec {
  display: flex; align-items: baseline; gap: 8px; margin: 20px 0 10px;
  font-size: var(--fs-sm); font-weight: 600; color: var(--ink);
}
.sd-sec small { margin-left: auto; font-weight: 400; font-size: var(--fs-xs); color: var(--muted); }
.sd-empty { font-size: var(--fs-xs); color: var(--muted); padding: 10px 2px; }
.sd-bar-row { display: flex; align-items: center; gap: 10px; margin-bottom: 7px; }
.sd-bar-name { width: 108px; flex: 0 0 108px; font-size: var(--fs-xs); color: var(--ink-2); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.sd-bar { flex: 1; height: 9px; border-radius: 5px; background: var(--surface-2); overflow: hidden; }
.sd-bar i { display: block; height: 100%; border-radius: 5px; background: var(--accent); }
.sd-bar-num { width: 96px; flex: 0 0 96px; text-align: right; font-size: var(--fs-xs); color: var(--ink-2); }
.sd-insight { display: flex; gap: 10px; background: var(--surface-2); border-radius: var(--radius-sm); padding: 9px 12px; margin-bottom: 7px; overflow: hidden; }
.sd-ins-bar { width: 3px; flex: 0 0 3px; border-radius: 2px; background: var(--info); }
.sd-insight.lv-danger .sd-ins-bar { background: var(--danger); }
.sd-insight.lv-warn .sd-ins-bar { background: var(--warn); }
.sd-insight b { font-size: var(--fs-sm); color: var(--ink); }
.sd-insight.lv-danger b { color: var(--danger); }
.sd-insight.lv-warn b { color: var(--warn); }
.sd-insight div { font-size: var(--fs-xs); color: var(--ink-2); line-height: 1.6; margin-top: 3px; }
.sd-table-hint { font-size: var(--fs-xs); color: var(--muted); padding: 0 2px 6px; }
.sd-table { width: 100%; cursor: pointer; }
.sd-go { color: var(--accent); font-size: var(--fs-xs); }
.sd-note { margin-top: 8px; font-size: var(--fs-xs); color: var(--warn); }
.sd-foot { display: flex; justify-content: flex-end; gap: 8px; }
</style>
