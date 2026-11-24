<template>
  <div ref="el" :style="{ width: '100%', height }"></div>
</template>

<script setup>
import { onMounted, onBeforeUnmount, ref, watch, nextTick } from 'vue'
import * as echarts from 'echarts'

const props = defineProps({
  option: { type: Object, required: true },
  height: { type: String, default: '240px' }
})

const el = ref(null)
let chart = null
let observer = null

/* 主题色读 html 上的设计 token 终值（经验库 L-028）：
   自定义属性不参与 CSS 过渡插值，任何时刻读到的都是目标方案的颜色。 */
function theme() {
  const cs = getComputedStyle(document.documentElement)
  const get = (n, fallback) => cs.getPropertyValue(n).trim() || fallback
  return {
    ink: get('--ink', '#0F172A'),
    muted: get('--muted', '#6B7C93'),
    line: get('--line', '#E3E9F2'),
    surface: get('--surface', '#FFFFFF'),
    accent: get('--accent', '#2F5BEA')
  }
}

/* 只做浅合并，不深拷贝：option 里可能带 formatter 函数，深拷贝会把它弄丢 */
function patchAxis(axis, t) {
  if (!axis) return axis
  const one = (a) => ({
    ...a,
    nameTextStyle: { ...(a.nameTextStyle || {}), color: t.muted },
    axisLine: { ...(a.axisLine || {}), lineStyle: { ...((a.axisLine || {}).lineStyle || {}), color: t.line } },
    axisLabel: { ...(a.axisLabel || {}), color: t.muted },
    axisTick: { ...(a.axisTick || {}), lineStyle: { ...((a.axisTick || {}).lineStyle || {}), color: t.line } },
    splitLine: { ...(a.splitLine || {}), lineStyle: { ...((a.splitLine || {}).lineStyle || {}), color: t.line } }
  })
  return Array.isArray(axis) ? axis.map(one) : one(axis)
}

function themedOption() {
  const t = theme()
  const o = props.option
  return {
    ...o,
    backgroundColor: 'transparent',
    textStyle: { ...(o.textStyle || {}), color: t.ink },
    legend: o.legend ? { ...o.legend, textStyle: { ...(o.legend.textStyle || {}), color: t.muted } } : o.legend,
    tooltip: o.tooltip ? {
      ...o.tooltip,
      backgroundColor: t.surface,
      borderColor: t.line,
      textStyle: { ...(o.tooltip.textStyle || {}), color: t.ink },
      axisPointer: { ...(o.tooltip.axisPointer || {}), lineStyle: { ...((o.tooltip.axisPointer || {}).lineStyle || {}), color: t.accent } }
    } : { backgroundColor: t.surface, borderColor: t.line, textStyle: { color: t.ink } },
    xAxis: patchAxis(o.xAxis, t),
    yAxis: patchAxis(o.yAxis, t)
  }
}

const render = () => {
  if (!el.value) return
  if (!chart) chart = echarts.init(el.value, null, { renderer: 'canvas' })
  chart.setOption(themedOption(), true)
  chart.resize()
}

const resize = () => chart && chart.resize()

onMounted(async () => {
  await nextTick()
  render()
  window.addEventListener('resize', resize)
  // 顶栏切换明度模式时重绘图表，避免坐标轴文字在深底下不可读
  observer = new MutationObserver(() => render())
  observer.observe(document.documentElement, { attributes: true, attributeFilter: ['data-mode'] })
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', resize)
  if (observer) observer.disconnect()
  if (chart) {
    chart.dispose()
    chart = null
  }
})

watch(
  () => props.option,
  async () => { await nextTick(); render() },
  { deep: true }
)
</script>
