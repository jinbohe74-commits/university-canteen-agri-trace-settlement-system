<template>
  <div class="page" v-loading="loading">
    <div class="page-head">
      <div>
        <h2 class="page-title">消息提醒</h2>
        <p class="page-sub">新订单、结算与预警通知（站内信）</p>
      </div>
      <div class="page-actions">
        <el-button type="primary" plain :disabled="readAll" @click="markAllRead">
          <el-icon><Check /></el-icon>全部已读
        </el-button>
      </div>
    </div>

    <!-- 分类胶囊 -->
    <div class="msg-bar">
      <div class="msg-tabs">
        <button
          v-for="c in cats" :key="c.key" type="button" class="msg-tab"
          :class="{ active: cat === c.key }" @click="cat = c.key"
        >
          <el-icon><component :is="c.icon" /></el-icon>
          <span>{{ c.label }}</span>
          <em>{{ c.count }}</em>
        </button>
      </div>
      <div class="msg-unread">
        未读 <b>{{ unreadCount }}</b> 条
      </div>
    </div>

    <SectionCard title="消息列表" :body-style="false" noPad>
      <template #extra>
        <span class="muted small">共 {{ filtered.length }} 条 · 点击查看详情</span>
      </template>

      <div v-if="filtered.length" class="msg-list">
        <div
          v-for="(m, i) in filtered" :key="i" class="msg-row"
          :class="{ unread: isUnread(m) }" @click="openMsg(m)"
        >
          <span v-if="isUnread(m)" class="unread-dot"></span>
          <div class="msg-icon" :class="toneClass(m)">
            <el-icon><component :is="iconOf(m)" /></el-icon>
          </div>
          <div class="grow msg-main">
            <div class="msg-title">
              {{ m.title }}
              <span class="msg-type">{{ m.type }}</span>
            </div>
            <div class="msg-content">{{ contentOf(m) }}</div>
          </div>
          <div class="msg-side">
            <div class="msg-time">{{ fmtDate(m.time) }}</div>
            <div class="msg-tags">
              <span class="tag" :class="levelCls(m.level)">{{ m.level || '通知' }}</span>
              <StatusTag type="warning" :value="m.status" />
            </div>
          </div>
        </div>
      </div>

      <div v-else class="empty">暂无相关消息</div>
    </SectionCard>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { farmerApi } from '@/api'
import SectionCard from '@/components/SectionCard.vue'
import StatusTag from '@/components/StatusTag.vue'
import { fmtDate } from '@/utils/format'

const loading = ref(false)
const list = ref([])
const cat = ref('all')
const readAll = ref(false)

const CATS = [
  { key: 'all', label: '全部', icon: 'Bell' },
  { key: 'order', label: '新订单', icon: 'ShoppingCart' },
  { key: 'settle', label: '结算', icon: 'Wallet' },
  { key: 'warn', label: '预警', icon: 'Warning' }
]

const catOf = (m) => {
  const t = String(m?.type || '')
  if (t.includes('结算')) return 'settle'
  if (t.includes('订单') || t.includes('催办')) return 'order'
  return 'warn'
}

const cats = computed(() =>
  CATS.map((c) => ({
    ...c,
    count: c.key === 'all' ? list.value.length : list.value.filter((m) => catOf(m) === c.key).length
  }))
)

const filtered = computed(() =>
  cat.value === 'all' ? list.value : list.value.filter((m) => catOf(m) === cat.value)
)

const isUnread = (m) => !readAll.value && ['待处理', '待确认'].includes(m?.status)

const unreadCount = computed(() => list.value.filter((m) => isUnread(m)).length)

const iconOf = (m) => {
  const t = String(m?.type || '')
  if (t.includes('价格')) return 'TrendCharts'
  if (t.includes('质检')) return 'CircleCheck'
  if (t.includes('逾期')) return 'Timer'
  if (t.includes('结算')) return 'Wallet'
  if (t.includes('订单')) return 'ShoppingCart'
  return 'Bell'
}

const toneClass = (m) => {
  const c = catOf(m)
  if (c === 'order') return 'tone-blue'
  if (c === 'settle') return 'tone-green'
  if (m?.level === '高') return 'tone-red'
  if (m?.level === '中') return 'tone-orange'
  return 'tone-teal'
}

const levelCls = (level) => {
  if (level === '高') return 'tag-red'
  if (level === '中') return 'tag-orange'
  if (level === '低') return 'tag-gray'
  return 'tag-teal'
}

const contentOf = (m) => {
  const c = catOf(m)
  if (c === 'warn') {
    return `风险等级：${m.level || '—'} · 处理状态：${m.status || '待处理'} · 请及时跟进处理`
  }
  if (c === 'settle') return '结算款项状态更新，请关注到账情况'
  return '新订单提醒，请在 3 小时内响应接单'
}

const esc = (v) =>
  String(v ?? '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')

const openMsg = (m) => {
  ElMessageBox.alert(
    `<div style="line-height:1.9;font-size:13px">
      <div style="font-weight:600;margin-bottom:4px">${esc(m.title)}</div>
      <div><b>消息类型：</b>${esc(m.type || '—')}</div>
      <div><b>风险等级：</b>${esc(m.level || '—')}</div>
      <div><b>处理状态：</b>${esc(m.status || '—')}</div>
      <div><b>通知时间：</b>${esc(fmtDate(m.time))}</div>
    </div>`,
    '消息详情',
    { dangerouslyUseHTMLString: true, confirmButtonText: '知道了' }
  )
}

const markAllRead = () => {
  readAll.value = true
  ElMessage.success('全部消息已标记为已读')
}

const load = async () => {
  loading.value = true
  try {
    const res = await farmerApi.messages()
    list.value = res.data || []
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<style scoped>
/* ---------------- 分类栏 ---------------- */
.msg-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  padding: 9px 14px;
  margin-bottom: 14px;
  background: #fff;
  border: 1px solid var(--c-border);
  border-radius: var(--radius);
  box-shadow: var(--shadow-card);
}

.msg-tabs {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}

.msg-tab {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 32px;
  padding: 0 12px;
  border-radius: 8px;
  border: 1px solid transparent;
  background: transparent;
  color: var(--c-muted);
  font-size: 12.5px;
  font-family: inherit;
  cursor: pointer;
  transition: all 0.18s;
}

.msg-tab:hover {
  background: #f4faf7;
  color: #15803d;
}

.msg-tab.active {
  background: #e8f7ee;
  border-color: #d2efdf;
  color: #15803d;
  font-weight: 600;
}

.msg-tab .el-icon {
  font-size: 14px;
}

.msg-tab em {
  min-width: 17px;
  height: 17px;
  padding: 0 4px;
  border-radius: 9px;
  background: #f0f4f2;
  color: #6b7280;
  font-size: 10.5px;
  font-style: normal;
  line-height: 17px;
  text-align: center;
}

.msg-tab.active em {
  background: #fff;
  color: #15803d;
}

.msg-unread {
  flex-shrink: 0;
  font-size: 12px;
  color: var(--c-muted);
}

.msg-unread b {
  color: #b45309;
  font-family: var(--font-num);
  font-size: 13.5px;
}

/* ---------------- 消息列表 ---------------- */
.msg-list {
  display: flex;
  flex-direction: column;
}

.msg-row {
  position: relative;
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 15px 18px;
  border-bottom: 1px dashed var(--c-border);
  cursor: pointer;
  transition: background 0.18s;
}

.msg-row:last-child {
  border-bottom: none;
}

.msg-row:hover {
  background: #f8fcfa;
}

.msg-row.unread {
  background: #f6fdf9;
}

.msg-row.unread:hover {
  background: #f0fbf5;
}

.unread-dot {
  position: absolute;
  left: 8px;
  top: 24px;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #16a34a;
  box-shadow: 0 0 0 3px rgba(22, 163, 74, 0.12);
}

.msg-icon {
  width: 38px;
  height: 38px;
  flex-shrink: 0;
  border-radius: 11px;
  display: grid;
  place-items: center;
  font-size: 18px;
}

.tone-blue {
  color: #1d4ed8;
  background: #e8efff;
}

.tone-green {
  color: #15803d;
  background: #e8f7ee;
}

.tone-red {
  color: #b91c1c;
  background: #fdeaea;
}

.tone-orange {
  color: #b45309;
  background: #fdf2e2;
}

.tone-teal {
  color: #0f766e;
  background: #e6f6f4;
}

.msg-main {
  min-width: 0;
  padding-top: 1px;
}

.msg-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  font-weight: 600;
  color: #1b2a24;
  line-height: 1.5;
}

.msg-type {
  flex-shrink: 0;
  padding: 0 6px;
  height: 17px;
  border-radius: 5px;
  background: #f3f6f5;
  color: #6b7280;
  font-size: 10px;
  font-weight: 400;
  line-height: 17px;
}

.msg-content {
  margin-top: 4px;
  font-size: 12px;
  color: var(--c-muted);
  line-height: 1.6;
}

.msg-side {
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 7px;
  padding-top: 1px;
}

.msg-time {
  font-size: 11px;
  color: var(--c-muted-2);
  font-family: var(--font-num);
}

.msg-tags {
  display: flex;
  align-items: center;
  gap: 5px;
}

@media (max-width: 960px) {
  .msg-row {
    flex-wrap: wrap;
  }

  .msg-side {
    width: 100%;
    flex-direction: row;
    align-items: center;
    justify-content: space-between;
    padding-left: 50px;
  }
}
</style>
