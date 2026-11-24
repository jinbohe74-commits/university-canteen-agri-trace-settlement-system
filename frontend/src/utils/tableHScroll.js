/* ==========================================================================
   全站表格横向可查看
   --------------------------------------------------------------------------
   用户反馈「有些表格不能向右查看」——实测并非滚动被 CSS 禁掉，而是：
   1. Element Plus 的 el-scrollbar 横向条默认 opacity:0，hover 才出现，用户无感知；
   2. 全站 29 张表没有任何一处提示「还有右侧列」；
   3. 4 个页面用 .table-card { overflow:hidden } 把固定列阴影/滚动条边缘裁掉。

   这里只做两件低侵入的事：给真正溢出的表格容器打 has-hscroll 类（由 CSS
   显示常驻提示），并用 ResizeObserver 在列宽/窗口变化后重算。
   不改表格 DOM 结构、不注入行内元素，避免影响既有导出/筛选逻辑。
   ========================================================================== */
let mutation = null
let resize = null
let timer = null

function scrollerOf(table) {
  return table.querySelector('.el-table__body-wrapper .el-scrollbar__wrap')
    || table.querySelector('.el-table__body-wrapper')
    || table.querySelector('.el-scrollbar__wrap')
}

function scan() {
  document.querySelectorAll('.el-table').forEach(table => {
    const sc = scrollerOf(table)
    if (!sc) return
    const wrap = table.closest('.table-wrap') || table.parentElement
    if (!wrap) return
    const overflowed = sc.scrollWidth > sc.clientWidth + 2
    wrap.classList.toggle('has-hscroll', overflowed)
    if (resize && !sc.__ctHScrollObserved) {
      sc.__ctHScrollObserved = true
      resize.observe(sc)
      resize.observe(table)
    }
  })
}

function schedule() {
  if (timer) clearTimeout(timer)
  timer = setTimeout(scan, 140)
}

export function installTableHScroll() {
  if (mutation) { schedule(); return }
  if (typeof ResizeObserver !== 'undefined') {
    resize = new ResizeObserver(schedule)
  }
  mutation = new MutationObserver(schedule)
  mutation.observe(document.body, { childList: true, subtree: true })
  window.addEventListener('resize', schedule)
  schedule()
}

export function uninstallTableHScroll() {
  if (mutation) mutation.disconnect()
  if (resize) resize.disconnect()
  if (timer) clearTimeout(timer)
  window.removeEventListener('resize', schedule)
  mutation = null
  resize = null
  timer = null
}
