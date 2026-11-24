/* ==========================================================================
   多工作表 Excel 导出
   --------------------------------------------------------------------------
   以前 exportSectionsCsv 是把 8 段报表用「# 标题 + 空行」顺序拼进一个 CSV：
   CSV 没有工作表概念，Excel 打开只有一个 Sheet1，且各段列数 2~6 不等，
   全部堆叠、互相错位 —— 这就是「导出报表全挤在一个标签页」的根因。

   这里改用 SheetJS 生成真正的 .xlsx，每段一个工作表，各段独立表头/列宽。
   ========================================================================== */
import * as XLSX from 'xlsx'

/** Excel 工作表命名限制：≤31 字符，且不能含 : \ / ? * [ ] */
function safeSheetName(name, index, used) {
  let n = String(name || `工作表${index + 1}`)
    .replace(/[:\\/?*\[\]]/g, ' ')
    .replace(/\s+/g, ' ')
    .trim()
    .slice(0, 31) || `工作表${index + 1}`
  let base = n
  let i = 2
  while (used.has(n)) {
    const suffix = ` (${i++})`
    n = base.slice(0, 31 - suffix.length) + suffix
  }
  used.add(n)
  return n
}

/** 按每列真实内容长度算列宽，中文按 2 个字符宽估算 */
function colWidths(aoa, headerLen) {
  const n = Math.max(headerLen, ...aoa.map(r => r.length))
  const widths = []
  for (let c = 0; c < n; c++) {
    let max = 10
    for (const r of aoa) {
      const v = r[c]
      if (v === null || v === undefined) continue
      const s = String(v)
      const w = s.replace(/[^\x00-\xff]/g, '**').length
      if (w > max) max = w
    }
    widths.push({ wch: Math.min(46, Math.max(10, max + 2)) })
  }
  return widths
}

/**
 * @param {string} filename 以 .xlsx 结尾（传入 .csv 时会自动改写）
 * @param {Array}  sheets   [{ title, header, rows, note }]
 * @returns {number} 导出的总数据行数（不含标题/表头）
 */
export function exportWorkbook(filename, sheets) {
  const list = Array.isArray(sheets) ? sheets.filter(Boolean) : []
  if (!list.length) return 0
  const wb = XLSX.utils.book_new()
  const used = new Set()
  let count = 0

  list.forEach((s, index) => {
    const header = s.header || []
    const rows = (s.rows || []).map(r => Array.isArray(r) ? r : [r])
    const aoa = []
    aoa.push([s.title || `工作表${index + 1}`])
    if (s.note) aoa.push([s.note])
    if (header.length) aoa.push(header)
    for (const r of rows) { aoa.push(r); count++ }

    const ws = XLSX.utils.aoa_to_sheet(aoa)
    ws['!cols'] = colWidths(aoa, header.length)
    // 表头行（标题 1 行 + 口径 1 行 + 表头 1 行）不参与滚动
    ws['!freezes'] = { xSplit: 0, ySplit: header.length ? (s.note ? 3 : 2) : 1 }
    XLSX.utils.book_append_sheet(wb, ws, safeSheetName(s.title, index, used))
  })

  XLSX.writeFile(wb, String(filename).replace(/\.csv$/i, '.xlsx'), {
    bookType: 'xlsx', compression: true
  })
  return count
}

/** 单表快捷方式：给仍想用结构化数据导出的页面 */
export function exportSingleSheet(filename, title, header, rows) {
  return exportWorkbook(filename, [{ title, header, rows }])
}
