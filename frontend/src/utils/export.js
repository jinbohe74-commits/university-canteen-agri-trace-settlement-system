/* ==========================================================================
   通用导出工具（2026-11-10）
   --------------------------------------------------------------------------
   把原先「只弹一条已导出提示」的按钮统一接成真实文件下载。
   - downloadCsv：结构化数据 → CSV（带 BOM，Excel 中文不乱码）
   - exportTableDom：无需知道页面数据变量名，直接从已渲染表格抽取
   - exportSectionsCsv：多段报表（看板 / 溯源报告）
   所有函数返回导出行数，调用方据此给出如实反馈，不再编造数量。
   ========================================================================== */

import { exportWorkbook } from './exportWorkbook'

const esc = (v) => '"' + String(v === null || v === undefined ? '' : v).replace(/"/g, '""') + '"'

function save(filename, content, mime) {
  const blob = new Blob([content], { type: mime })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  document.body.appendChild(a)
  a.click()
  a.remove()
  setTimeout(() => URL.revokeObjectURL(url), 1500)
}

export function downloadCsv(filename, header, rows) {
  const csv = [header.map(esc).join(','), ...rows.map(r => r.map(esc).join(','))].join('\r\n')
  save(filename, '\ufeff' + csv, 'text/csv;charset=utf-8;')
  return rows.length
}

export function downloadText(filename, text, mime = 'text/plain;charset=utf-8;') {
  save(filename, '\ufeff' + text, mime)
  return (text.match(/\n/g) || []).length + 1
}

/**
 * 多段报表：sections = [{ title, header, rows, note }]
 * 产物是真正的 .xlsx，每段一个工作表（CSV 没有工作表概念，会把 8 段
 * 不同列数的报表全堆在 Sheet1 里）。文件名里的 .csv 会自动改写成 .xlsx。
 * 保留原函数名是为了让已有调用方零改动。
 */
export function exportSectionsCsv(filename, sections) {
  return exportWorkbook(filename, sections)
}

/** 从页面已渲染的 Element Plus 表格抽取当前页数据（无需改动业务数据源）。
 *  自动剔除「操作」列（含按钮），避免把「详情 / 编辑 / 复制」文字混进 CSV。 */
export function exportTableDom(filename, root) {
  const scope = root || document.querySelector('.lay-content') || document
  const table = scope.querySelector('.el-table')
  if (!table) return 0
  const ths = Array.from(table.querySelectorAll('.el-table__header th'))
  const bodyTrs = Array.from(table.querySelectorAll('.el-table__body tbody tr'))
  const firstTds = bodyTrs.length ? Array.from(bodyTrs[0].querySelectorAll('td')) : []
  const drop = ths.map((th, i) => {
    const td = firstTds[i]
    return !td || td.querySelectorAll('button, .el-button').length > 0
  })
  const headers = ths.map(th => (th.innerText || '').trim())
    .filter((h, i) => h && !drop[i])
  const rows = bodyTrs.map(tr => {
    const tds = Array.from(tr.querySelectorAll('td'))
    return tds.map(td => (td.innerText || '').replace(/\s*\n\s*/g, ' / ').trim())
      .filter((v, i) => !drop[i])
  }).filter(r => r.some(Boolean))
  if (!headers.length || !rows.length) return 0
  return downloadCsv(filename, headers, rows)
}

/** 纯文本业务报告（溯源报告 / 质检报告） */
export function exportTextReport(filename, title, lines) {
  const now = new Date().toLocaleString('zh-CN', { hour12: false })
  const text = [title, '生成时间：' + now, '='.repeat(46), ...lines, '', '— 农校直供 · 溯源与结算管理平台'].join('\n')
  return downloadText(filename, text)
}

/** 生成带时间戳的文件名，避免覆盖 */
export function stamped(name, ext = 'csv') {
  const d = new Date()
  const p = n => String(n).padStart(2, '0')
  return `${name}_${d.getFullYear()}${p(d.getMonth() + 1)}${p(d.getDate())}_${p(d.getHours())}${p(d.getMinutes())}${p(d.getSeconds())}.${ext}`
}
