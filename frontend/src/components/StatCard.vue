<template>
  <div
    class="stat-card stat-click"
    role="button" tabindex="0"
    @click="onClick" @keydown.enter.prevent="onClick"
  >
    <div class="stat-icon" :style="iconStyle">
      <el-icon><component :is="icon" /></el-icon>
    </div>
    <div class="stat-label">{{ label }}</div>
    <div class="stat-value" :style="{ color: valueColor || 'var(--ink)' }">
      {{ value }}<small v-if="suffix">{{ suffix }}</small>
    </div>
    <div class="stat-foot">
      <span v-if="trend" :class="up ? 'stat-up' : 'stat-down'">
        <el-icon><component :is="up ? 'Top' : 'Bottom'" /></el-icon>{{ trend }}
      </span>
      <span>{{ foot }}</span>
    </div>
    <!-- 可点的卡片必须自己说出来：否则用户永远只把它当装饰数字 -->
    <span class="stat-go">明细 ›</span>
  </div>
</template>

<script setup>
import { computed, getCurrentInstance } from 'vue'
import { useRoute } from 'vue-router'
import { openStatDrill } from '@/utils/statDrill'

const props = defineProps({
  label: String,
  value: [String, Number],
  suffix: String,
  foot: String,
  trend: String,
  up: { type: Boolean, default: true },
  icon: { type: String, default: 'TrendCharts' },
  color: { type: String, default: 'green' },
  valueColor: String,
  clickable: { type: Boolean, default: false }
})
const emit = defineEmits(['click'])
const route = useRoute()
// click 已声明为 emit，Vue 不会把它塞进 $attrs；必须直接看 vnode.props
const instance = getCurrentInstance()
const hasParentClick = () => !!(instance?.vnode?.props?.onClick)

/* 契约：卡片默认可点。父级显式绑了 @click（如「待审核」跳筛选）时按原行为走，
   否则统一打开指标明细抽屉 —— 不再出现「数字只能看、点了没反应」。 */
const onClick = () => {
  if (hasParentClick()) { emit('click'); return }
  openStatDrill(props.label, props.value, route.path)
}

/* 图标底色走语义令牌：写死浅绿/浅蓝在深色模式下会变成一排刺眼白块 */
const PALETTE = {
  green: { color: 'var(--ok)', bg: 'var(--ok-soft)' },
  blue: { color: 'var(--info)', bg: 'var(--info-soft)' },
  orange: { color: 'var(--warn)', bg: 'var(--warn-soft)' },
  red: { color: 'var(--danger)', bg: 'var(--danger-soft)' },
  teal: { color: 'var(--accent)', bg: 'var(--accent-soft)' },
  purple: { color: 'var(--info)', bg: 'var(--info-soft)' },
  cyan: { color: 'var(--accent)', bg: 'var(--accent-soft)' }
}

const iconStyle = computed(() => {
  const p = PALETTE[props.color] || PALETTE.green
  return { color: p.color, background: p.bg }
})
</script>

<style scoped>
.stat-click { cursor: pointer; transition: border-color .18s var(--ease), transform .12s var(--ease); }
.stat-click:hover { border-color: var(--accent); }
.stat-click:active { transform: scale(.994); }
.stat-click:focus-visible { outline: 2px solid var(--accent); outline-offset: 2px; }
/* 「明细」不再只在 hover 时凭空出现：常显浅色，hover 加深。
   以前 opacity:0 → 鼠标刚移过去文字就消失，看着像点不到、点了也没反应。 */
.stat-go {
  position: absolute; right: 14px; bottom: 11px;
  font-size: var(--fs-xs); font-weight: 700; color: var(--accent);
  opacity: .55; transition: opacity .18s var(--ease), transform .18s var(--ease);
  pointer-events: auto;
}
.stat-click:hover .stat-go, .stat-click:focus-visible .stat-go { opacity: 1; transform: translateX(2px); }
</style>
