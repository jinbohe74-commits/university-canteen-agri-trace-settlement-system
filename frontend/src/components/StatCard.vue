<template>
  <div class="stat-card">
    <div class="stat-icon" :style="iconStyle">
      <el-icon><component :is="icon" /></el-icon>
    </div>
    <div class="stat-label">{{ label }}</div>
    <div class="stat-value" :style="{ color: valueColor || '#111827' }">
      {{ value }}<small v-if="suffix">{{ suffix }}</small>
    </div>
    <div class="stat-foot">
      <span v-if="trend" :class="up ? 'stat-up' : 'stat-down'">
        <el-icon><component :is="up ? 'Top' : 'Bottom'" /></el-icon>{{ trend }}
      </span>
      <span>{{ foot }}</span>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  label: String,
  value: [String, Number],
  suffix: String,
  foot: String,
  trend: String,
  up: { type: Boolean, default: true },
  icon: { type: String, default: 'TrendCharts' },
  color: { type: String, default: 'green' },
  valueColor: String
})

const PALETTE = {
  green: { color: '#15803d', bg: '#e8f7ee' },
  blue: { color: '#1d4ed8', bg: '#e8efff' },
  orange: { color: '#b45309', bg: '#fdf2e2' },
  red: { color: '#b91c1c', bg: '#fdeaea' },
  teal: { color: '#0f766e', bg: '#e6f6f4' },
  purple: { color: '#6d28d9', bg: '#f1eafe' },
  cyan: { color: '#0e7490', bg: '#e6f7fb' }
}

const iconStyle = computed(() => {
  const p = PALETTE[props.color] || PALETTE.green
  return { color: p.color, background: p.bg }
})
</script>
