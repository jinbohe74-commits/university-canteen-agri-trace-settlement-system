<template>
  <el-drawer v-model="s.open" :title="s.title" size="900px" append-to-body class="wi-drawer">
    <div v-if="s.loading" class="wi-state">正在读取该单据的真实业务数据…</div>
    <div v-else-if="s.error" class="wi-state is-error">{{ s.error }}</div>
    <div v-else class="wi-body" :class="{ 'has-list': s.items.length > 1 }">
      <!-- 多工单（待办汇总）必须能切换，且明确「当前处理的是哪一单」(L-041) -->
      <aside v-if="s.items.length > 1" class="wi-list">
        <div class="wi-list-head">共 {{ s.items.length }} 笔待处理</div>
        <button v-for="it in s.items" :key="it.key" type="button"
                class="wi-list-item" :class="{ on: s.activeKey === String(it.key) }"
                @click="selectWorkItem(it.key)">
          <span class="wi-dot" :class="'lv-' + it.level"></span>
          <span class="grow">
            <b>{{ it.title }}</b>
            <small>{{ it.status }}</small>
          </span>
        </button>
      </aside>

      <section v-if="current" class="wi-detail">
        <div class="wi-head">
          <div class="wi-title-row">
            <h3>{{ current.title }}</h3>
            <span class="wi-badge" :class="'lv-' + current.level">{{ current.status }}</span>
          </div>
          <div class="wi-sub">{{ current.subtitle }}</div>
        </div>

        <div class="wi-fields">
          <div v-for="(f, i) in current.fields || []" :key="i" class="wi-field">
            <span>{{ f.label }}</span><b>{{ f.value }}</b>
          </div>
        </div>

        <!-- 用户说的「没有显示特定的问题」：这里按真实数据逐条列出 -->
        <div class="wi-sec-title">
          需要关注的问题 <em>{{ (current.issues || []).length }}</em>
          <small>数据来自订单 / 质检 / 入库 / 配送 / 结算真实记录</small>
        </div>
        <div v-if="!(current.issues || []).length" class="wi-empty">该单据当前无待关注事项</div>
        <div v-for="(is, i) in current.issues || []" :key="i" class="wi-issue" :class="'lv-' + is.level">
          <span class="wi-issue-bar"></span>
          <div class="grow">
            <div class="wi-issue-top">
              <b>{{ is.title }}</b>
              <span class="wi-metric mono">{{ is.metric }}</span>
            </div>
            <div class="wi-issue-detail">{{ is.detail }}</div>
          </div>
        </div>

        <div class="wi-sec-title">业务时间线 <em>{{ (current.timeline || []).length }}</em></div>
        <div v-if="!(current.timeline || []).length" class="wi-empty">暂无业务节点记录</div>
        <el-timeline v-else class="wi-timeline">
          <el-timeline-item v-for="(t, i) in current.timeline" :key="i"
                            :timestamp="t.time" placement="top" size="small">
            <b>{{ t.label }}</b><span class="wi-tl-desc">{{ t.desc }}</span>
          </el-timeline-item>
        </el-timeline>

        <div class="wi-sec-title">可执行动作</div>
        <div class="wi-actions">
          <el-button v-for="(a, i) in current.actions || []" :key="i"
                     :type="a.danger ? 'danger' : (i === 0 ? 'primary' : 'default')"
                     :plain="a.danger || i !== 0"
                     @click="runAction(a)">{{ a.label }}</el-button>
          <span v-if="!(current.actions || []).length" class="wi-empty">当前角色在该单据上暂无可执行动作</span>
        </div>
      </section>
    </div>

    <template #footer>
      <div class="wi-foot">
        <span class="wi-foot-note">仅展示与当前账号数据权限相符的单据</span>
        <el-button @click="closeWorkItem">关闭</el-button>
      </div>
    </template>
  </el-drawer>
</template>

<script setup>
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { adminApi } from '@/api'
import {
  workItemState, closeWorkItem, selectWorkItem, reloadWorkItem
} from '@/utils/workItem'

/* workItemState 导出的是「取状态的函数」（与 realtimeState/notifState 一致）。
   直接把函数当对象用，s.open 永远是 undefined —— 抽屉不报错也永远弹不出来。 */
const s = workItemState()

const router = useRouter()
const current = computed(() => s.items.find(x => String(x.key) === s.activeKey) || s.items[0] || null)

/** 跳转到业务页时带上 focus 单号，目标页会自动筛选并打开这一单 */
const goRoute = (a) => {
  const raw = String(a.route || '/')
  const qi = raw.indexOf('?')
  const path = qi >= 0 ? raw.slice(0, qi) : raw
  const query = Object.fromEntries(new URLSearchParams(qi >= 0 ? raw.slice(qi + 1) : ''))
  if (a.focus) query.focus = a.focus
  closeWorkItem()
  router.push({ path, query })
}

const runAction = async (a) => {
  if (a.type === 'route') { goRoute(a); return }
  /* 以前这里直接 return：后端给了动作、前端没实现，按钮点了像坏了一样。
     任何不能执行的动作都要说清楚原因与去处。 */
  if (!a.id) {
    ElMessage.warning(`「${a.label}」需要在对应业务页操作，请从左侧菜单进入该单据`)
    return
  }
  try {
    let remark = ''
    let okText = '处理完成'
    if (a.type === 'audit-pass' || a.type === 'audit-reject') {
      const pass = a.type === 'audit-pass'
      const r = await ElMessageBox.prompt(
        pass ? '请填写审核意见（写入结算单备注）' : '请说明驳回原因（写入结算单备注）',
        a.label, {
          confirmButtonText: '确认提交', cancelButtonText: '取消',
          inputPlaceholder: pass ? '例如：损耗已与农户核实，同意结算' : '例如：损耗原因说明不充分，退回重核'
        })
      remark = r.value || ''
      await adminApi.auditSettlement({ ids: [a.id], pass, remark })
      okText = pass ? '审核通过，订单已流转为已结算' : '已驳回，订单回退待入库'
    } else if (a.type === 'warning-handle') {
      const r = await ElMessageBox.prompt('请填写处置说明（会留痕）', a.label, {
        confirmButtonText: '确认处置', cancelButtonText: '取消',
        inputPlaceholder: '例如：已联系农户补齐证照，复核通过'
      })
      remark = r.value || ''
      await adminApi.handleWarning(a.id, { status: '已完成', remark })
      okText = '预警已处置'
    } else {
      ElMessage.warning(`「${a.label}」暂不支持在此处执行，请到对应业务页处理`)
      return
    }
    ElMessage.success(okText)
    await reloadWorkItem()
  } catch (e) {
    if (e === 'cancel' || e === 'close' || e?.message === 'cancel') return
    ElMessage.error(e?.message || '操作失败，请稍后重试')
  }
}
</script>

<style scoped>
.wi-state { padding: 48px 12px; text-align: center; color: var(--muted); font-size: var(--fs-sm); }
.wi-state.is-error { color: var(--danger); }
.wi-body { display: flex; gap: 16px; min-height: 420px; }
.wi-body.has-list { gap: 14px; }
.wi-list {
  width: 252px; flex: 0 0 252px; border-right: 1px solid var(--line);
  padding-right: 12px; max-height: 62vh; overflow-y: auto;
  /* 底部渐隐：列表被容器裁断时不再像「渲染坏了」，一眼能看出下面还有 */
  mask-image: linear-gradient(to bottom, #000 calc(100% - 22px), transparent);
  -webkit-mask-image: linear-gradient(to bottom, #000 calc(100% - 22px), transparent);
}
.wi-list::-webkit-scrollbar { width: 8px; }
.wi-list::-webkit-scrollbar-thumb { background: var(--line-strong); border-radius: 4px; }
.wi-list-head { font-size: var(--fs-xs); color: var(--muted); padding: 2px 6px 10px; }
.wi-list-item {
  width: 100%; display: flex; align-items: flex-start; gap: 8px; text-align: left;
  padding: 9px 8px; margin-bottom: 6px; border: 1px solid transparent;
  border-radius: var(--radius-sm); background: transparent; color: var(--ink-2); cursor: pointer;
}
.wi-list-item:hover { background: var(--surface-2); }
.wi-list-item.on { background: var(--accent-soft); border-color: var(--accent); color: var(--ink); }
.wi-list-item b { display: block; font-size: var(--fs-sm); font-weight: 600; line-height: 1.5; }
.wi-list-item small { display: block; font-size: var(--fs-xs); color: var(--muted); margin-top: 2px; }
.grow { flex: 1; min-width: 0; }
.wi-dot { width: 8px; height: 8px; border-radius: 50%; margin-top: 6px; flex: 0 0 8px; background: var(--muted); }
.wi-dot.lv-danger { background: var(--danger); }
.wi-dot.lv-warn { background: var(--warn); }
.wi-dot.lv-ok { background: var(--ok); }
/* 内容交给 el-drawer 自身滚动：以前给 .wi-detail 设 max-height:68vh + overflow，
   时间线一长就被裁在窗口里，右边没有可见滚动条，用户以为「下面没内容了」。 */
.wi-detail { flex: 1; min-width: 0; padding-right: 6px; }
.wi-head { border-bottom: 1px solid var(--line); padding-bottom: 12px; margin-bottom: 14px; }
.wi-title-row { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.wi-title-row h3 { margin: 0; font-size: 17px; color: var(--ink); }
.wi-badge { font-size: var(--fs-xs); padding: 2px 9px; border-radius: 10px; background: var(--surface-2); color: var(--ink-2); }
.wi-badge.lv-danger { background: var(--danger-soft); color: var(--danger); }
.wi-badge.lv-warn { background: var(--warn-soft); color: var(--warn); }
.wi-badge.lv-ok { background: var(--ok-soft); color: var(--ok); }
.wi-sub { margin-top: 6px; font-size: var(--fs-sm); color: var(--muted); }
.wi-fields { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 8px 14px; margin-bottom: 18px; }
.wi-field { background: var(--surface-2); border-radius: var(--radius-sm); padding: 8px 10px; min-width: 0; }
.wi-field span { display: block; font-size: var(--fs-xs); color: var(--muted); }
.wi-field b { display: block; font-size: var(--fs-sm); color: var(--ink); margin-top: 3px; word-break: break-all; }
.wi-sec-title {
  display: flex; align-items: baseline; gap: 7px; font-size: var(--fs-sm);
  font-weight: 600; color: var(--ink); margin: 18px 0 10px;
}
.wi-sec-title em { font-style: normal; font-size: var(--fs-xs); color: var(--muted); }
.wi-sec-title small { margin-left: auto; font-weight: 400; font-size: var(--fs-xs); color: var(--muted); }
.wi-issue {
  display: flex; gap: 10px; background: var(--surface-2); border-radius: var(--radius-sm);
  padding: 10px 12px; margin-bottom: 8px; overflow: hidden;
}
.wi-issue-bar { width: 3px; border-radius: 2px; flex: 0 0 3px; background: var(--info); }
.wi-issue.lv-danger .wi-issue-bar { background: var(--danger); }
.wi-issue.lv-warn .wi-issue-bar { background: var(--warn); }
.wi-issue.lv-ok .wi-issue-bar { background: var(--ok); }
.wi-issue-top { display: flex; align-items: baseline; gap: 8px; }
.wi-issue-top b { font-size: var(--fs-sm); color: var(--ink); }
.wi-issue.lv-danger .wi-issue-top b { color: var(--danger); }
.wi-issue.lv-warn .wi-issue-top b { color: var(--warn); }
.wi-metric { margin-left: auto; font-size: var(--fs-xs); color: var(--ink-2); white-space: nowrap; }
.wi-issue-detail { font-size: var(--fs-xs); color: var(--ink-2); line-height: 1.65; margin-top: 4px; }
.wi-empty { font-size: var(--fs-xs); color: var(--muted); padding: 8px 2px; }
.wi-timeline { padding-top: 4px; }
.wi-timeline b { font-size: var(--fs-sm); color: var(--ink); }
.wi-tl-desc { display: block; font-size: var(--fs-xs); color: var(--muted); margin-top: 2px; }
.wi-actions { display: flex; flex-wrap: wrap; gap: 8px; }
.wi-foot { display: flex; align-items: center; justify-content: flex-end; gap: 10px; }
.wi-foot-note { margin-right: auto; font-size: var(--fs-xs); color: var(--muted); }
</style>
