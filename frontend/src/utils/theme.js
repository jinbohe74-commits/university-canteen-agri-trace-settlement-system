import { computed, onBeforeUnmount, onMounted, ref } from 'vue'

/**
 * 图表用色：从 CSS 令牌实时读取，并在 html[data-mode] 变化时重新求值。
 * 之前多处图表把颜色写死在 option 里（#8b9c96 / #f1f5f3 / #fff），
 * 切到深色模式后坐标轴变浅色压在白底上、或深底上几乎看不见，
 * 用户会以为是"图表坏了"。所有图表颜色都必须走这里。
 */
const tick = ref(0)
let observer = null
let users = 0

const read = (name, fallback) => {
  const v = getComputedStyle(document.documentElement).getPropertyValue(name).trim()
  return v || fallback
}

export function useThemeColors() {
  onMounted(() => {
    users += 1
    if (!observer && typeof MutationObserver !== 'undefined') {
      observer = new MutationObserver(() => { tick.value += 1 })
      observer.observe(document.documentElement, { attributes: true, attributeFilter: ['data-mode'] })
    }
  })
  onBeforeUnmount(() => {
    users -= 1
    if (users <= 0 && observer) { observer.disconnect(); observer = null }
  })

  return computed(() => {
    tick.value
    return {
      accent: read('--accent', '#16a34a'),
      accent2: read('--accent-2', '#c2703a'),
      ok: read('--ok', '#0d9488'),
      warn: read('--warn', '#b4741b'),
      danger: read('--danger', '#c0453c'),
      info: read('--info', '#2c6e8f'),
      ink: read('--ink', '#16241f'),
      ink2: read('--ink-2', '#4b5b54'),
      muted: read('--muted', '#7c8b84'),
      line: read('--line', '#dfe7e3'),
      surface: read('--surface', '#ffffff'),
      surface2: read('--surface-2', '#f4f7f6'),
      soft: read('--accent-soft', '#e8f5ed'),
      isDark: document.documentElement.dataset.mode === 'night'
    }
  })
}
