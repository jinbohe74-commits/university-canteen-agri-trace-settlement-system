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

const render = () => {
  if (!el.value) return
  if (!chart) {
    chart = echarts.init(el.value, null, { renderer: 'canvas' })
  }
  chart.setOption(props.option, true)
  chart.resize()
}

const resize = () => chart && chart.resize()

onMounted(async () => {
  await nextTick()
  render()
  window.addEventListener('resize', resize)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', resize)
  if (chart) {
    chart.dispose()
    chart = null
  }
})

watch(
  () => props.option,
  async () => {
    await nextTick()
    render()
  },
  { deep: true }
)
</script>
