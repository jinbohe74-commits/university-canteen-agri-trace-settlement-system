<template>
  <div v-if="modelValue" class="cmd-overlay" @mousedown.self="close">
    <div class="cmd-panel" role="dialog" aria-label="命令面板">
      <div class="cmd-head">
        <span>⌕</span>
        <input
          ref="inputEl"
          v-model="query"
          type="text"
          placeholder="搜索页面或操作，输入关键词直达…"
          aria-label="命令搜索"
          @keydown="onKey"
        />
        <span class="lay-kbd">ESC</span>
      </div>
      <div class="cmd-list">
        <div
          v-for="(c, i) in filtered"
          :key="c.key"
          class="cmd-item"
          :class="{ on: i === active }"
          @mouseenter="active = i"
          @click="run(c)"
        >
          <span class="ci">{{ c.icon || '›' }}</span>
          <span>{{ c.label }}</span>
          <span class="ck2">{{ c.hint || '' }}</span>
        </div>
        <div v-if="!filtered.length" class="cmd-empty">没有匹配的页面或操作</div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  commands: { type: Array, default: () => [] }
})
const emit = defineEmits(['update:modelValue'])

const router = useRouter()
const query = ref('')
const active = ref(0)
const inputEl = ref(null)

const filtered = computed(() => {
  const q = query.value.trim().toLowerCase()
  const list = props.commands.filter((c) =>
    !q || (c.label + ' ' + (c.hint || '') + ' ' + (c.path || '')).toLowerCase().includes(q)
  )
  return list.slice(0, 12)
})

const close = () => emit('update:modelValue', false)

const run = (c) => {
  if (!c) return
  close()
  if (c.run) c.run()
  else if (c.path) router.push(c.path)
}

const onKey = (e) => {
  if (e.key === 'ArrowDown') { e.preventDefault(); active.value = Math.min(active.value + 1, filtered.value.length - 1) }
  else if (e.key === 'ArrowUp') { e.preventDefault(); active.value = Math.max(active.value - 1, 0) }
  else if (e.key === 'Enter') { e.preventDefault(); run(filtered.value[active.value]) }
  else if (e.key === 'Escape') { e.preventDefault(); close() }
}

const onGlobalKey = (e) => {
  if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') {
    e.preventDefault()
    emit('update:modelValue', !props.modelValue)
  } else if (e.key === 'Escape' && props.modelValue) {
    close()
  }
}

watch(() => props.modelValue, async (open) => {
  if (open) {
    query.value = ''
    active.value = 0
    await nextTick()
    inputEl.value?.focus()
  }
})
watch(query, () => { active.value = 0 })

onMounted(() => window.addEventListener('keydown', onGlobalKey))
onBeforeUnmount(() => window.removeEventListener('keydown', onGlobalKey))
</script>
