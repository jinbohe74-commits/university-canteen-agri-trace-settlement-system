<template>
  <span class="tag tag-dot" :class="cls">{{ text }}</span>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  type: { type: String, default: 'order' },
  value: [String, Number]
})

const ORDER = {
  10: ['orange', '待接单'],
  20: ['blue', '已接单'],
  30: ['purple', '待质检'],
  40: ['teal', '质检通过'],
  41: ['red', '质检不通过'],
  50: ['blue', '配送中'],
  60: ['teal', '已送达'],
  70: ['teal', '已入库'],
  80: ['orange', '待结算'],
  90: ['green', '已结算'],
  99: ['gray', '已取消'],
  100: ['green', '已完成']
}

const MAPS = {
  settlement: { 待审核: ['orange', '待审核'], 已结算: ['green', '已结算'], 已驳回: ['red', '已驳回'] },
  risk: { 高: ['red', '高'], 中: ['orange', '中'], 低: ['gray', '低'] },
  warning: {
    待处理: ['orange', '待处理'], 待确认: ['blue', '待确认'],
    已完成: ['green', '已完成'], 已忽略: ['gray', '已忽略']
  },
  cert: { 已认证: ['green', '已认证'], 待审核: ['orange', '待审核'], 已停用: ['gray', '已停用'] },
  source: { 正常: ['green', '在售'], 在售: ['green', '在售'], 已售罄: ['gray', '已售罄'], 下架: ['gray', '已下架'] },
  delivery: { 待发车: ['orange', '待发车'], 配送中: ['blue', '配送中'], 已送达: ['green', '已送达'] },
  result: { 合格: ['green', '合格'], 不合格: ['red', '不合格'] },
  demand: { 处理中: ['orange', '处理中'], 已成交: ['green', '已成交'], 已下单: ['green', '已下单'], 已撤回: ['gray', '已撤回'], 已完成: ['green', '已完成'] }
}

const meta = computed(() => {
  if (props.type === 'order') {
    const m = ORDER[Number(props.value)]
    return m || ['gray', '—']
  }
  const map = MAPS[props.type] || {}
  return map[props.value] || ['gray', props.value ?? '—']
})

const cls = computed(() => 'tag-' + meta.value[0])
const text = computed(() => meta.value[1])
</script>
