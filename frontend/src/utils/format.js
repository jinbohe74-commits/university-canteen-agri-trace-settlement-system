export const fmtMoney = (v, digits = 2) => {
  if (v === null || v === undefined || v === '') return '—'
  const n = Number(v)
  if (Number.isNaN(n)) return v
  return n.toLocaleString('zh-CN', { minimumFractionDigits: digits, maximumFractionDigits: digits })
}

export const fmtNum = (v, digits = 0) => {
  if (v === null || v === undefined || v === '') return '—'
  const n = Number(v)
  if (Number.isNaN(n)) return v
  return n.toLocaleString('zh-CN', { minimumFractionDigits: digits, maximumFractionDigits: digits })
}

export const fmtWan = (v) => {
  const n = Number(v || 0)
  if (n >= 100000000) return (n / 100000000).toFixed(2) + ' 亿'
  if (n >= 10000) return (n / 10000).toFixed(2) + ' 万'
  return fmtMoney(n)
}

export const fmtDate = (v, withTime = true) => {
  if (!v) return '—'
  const s = String(v).replace('T', ' ')
  return withTime ? s.slice(0, 16) : s.slice(0, 10)
}

export const fmtDay = (v) => fmtDate(v, false)

export const today = () => {
  const d = new Date()
  const p = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`
}

export const monthOf = (v) => (v ? String(v).slice(0, 7) : '')

export const daysBetween = (a, b) => {
  const d1 = new Date(a)
  const d2 = new Date(b)
  return Math.round((d2 - d1) / 86400000)
}

export const percent = (part, total) => {
  if (!total) return 0
  return Math.round((Number(part) / Number(total)) * 1000) / 10
}
