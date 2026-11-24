<template>
  <el-drawer v-model="s.open" size="640px" append-to-body class="tc-drawer" :with-header="false">
    <div class="tc-wrap">
      <div class="tc-head">
        <div class="tc-head-top">
          <h3>待办</h3>
          <span class="tc-role">{{ s.roleLabel }}</span>
          <span class="grow"></span>
          <button class="tc-icon" title="刷新" :disabled="s.loading" @click="reload">
            <el-icon :class="{ spin: s.loading }"><Refresh /></el-icon>
          </button>
          <button class="tc-icon" title="关闭" @click="close"><el-icon><Close /></el-icon></button>
        </div>
        <p class="tc-sub">
          共 <b>{{ s.total }}</b> 件需要你动手的事
          <span v-if="restCount > 0">，另有 {{ restCount }} 条只是让你知道进度（不计入数字）</span>
        </p>
      </div>

      <div v-if="s.loading && !s.groups.length" class="tc-state">正在读取你名下的待办…</div>
      <div v-else-if="s.error" class="tc-state is-error">{{ s.error }}</div>
      <div v-else-if="!s.groups.length" class="tc-state">当前角色暂无待办</div>

      <div v-else class="tc-body">
        <div v-for="g in s.groups" :key="g.key" class="tc-group" :class="{ 'is-quiet': !g.actionable }">
          <div class="tc-group-head">
            <span class="tc-g-label">{{ g.label }}</span>
            <span class="tc-g-count" :class="{ zero: g.count === 0 }">{{ g.count }}</span>
            <span v-if="!g.actionable" class="tc-g-tag">仅知晓</span>
            <span class="tc-g-hint">{{ g.hint }}</span>
          </div>

          <div v-if="g.count === 0" class="tc-empty">这一类已经清空</div>

          <button
            v-for="it in shown(g)"
            :key="it.key"
            type="button"
            class="tc-item"
            :class="'lv-' + (it.level || 'info')"
            @click="open(it)"
          >
            <span class="tc-bar"></span>
            <span class="grow tc-i-main">
              <span class="tc-i-title">
                {{ it.title }}
                <em class="tc-i-status">{{ it.status }}</em>
              </span>
              <span class="tc-i-note">{{ it.note || it.subtitle }}</span>
            </span>
            <span class="tc-i-side">
              <span v-if="it.ageText" class="tc-i-age">{{ it.ageText }}</span>
              <span class="tc-i-go">{{ it.actionLabel || '查看' }} ›</span>
            </span>
          </button>

          <button v-if="g.count > shown(g).length" type="button" class="tc-more" @click="expand(g.key)">
            还有 {{ g.count - shown(g).length }} 条，点开全部
          </button>
        </div>
      </div>

      <div class="tc-foot">
        <span class="tc-foot-note">数字与各业务列表页同源，点任意一条直接打开该单据的问题清单与可执行动作</span>
        <el-button @click="close">关闭</el-button>
      </div>
    </div>
  </el-drawer>
</template>

<script setup>
import { computed, reactive } from 'vue'
import { useRouter } from 'vue-router'
import {
  todoCenterState, loadTodoCenter, closeTodoCenter
} from '@/utils/todoCenter'
import { openWorkItem } from '@/utils/workItem'

/* L-055：状态模块一律用函数取实例，不要 `import { xxxState as s }` —— 写成 as s
   会让 s 是个函数，s.open 恒 undefined，抽屉永远不弹且不报错。 */
const s = todoCenterState()

/** 每组默认只展开 5 条：待办清单本身不该让人先滚屏 */
const PAGE = 5
const expanded = reactive({})

const restCount = computed(() =>
  s.groups.filter((g) => !g.actionable).reduce((n, g) => n + Number(g.count || 0), 0)
)
const shown = (g) => (expanded[g.key] ? g.items : (g.items || []).slice(0, PAGE))

const router = useRouter()

const reload = () => loadTodoCenter()
const close = () => closeTodoCenter()

function expand(key) { expanded[key] = true }

/**
 * 点一条待办：
 * - 订单 / 结算单 → 打开单据工作台（字段 + 问题 + 时间线 + 角色动作），
 *   这是第六轮就定下的契约，待办入口不能退回「跳列表页让自己找」。
 * - 需求 / 预警 / 农户档案 → 目前没有单据工作台视图，直接落到对应页面。
 */
async function open(it) {
  if (!it) return
  s.open = false
  if (it.bizType === 'ORDER' || it.bizType === 'SETTLEMENT') {
    await openWorkItem({
      scope: 'notification',
      bizType: it.bizType,
      bizNo: it.bizNo,
      orderId: it.orderId || undefined,
      title: '待办 · ' + (it.actionLabel || '查看')
    })
    return
  }
  if (it.path) router.push(it.path)
}
</script>

<style scoped>
.tc-wrap { display: flex; flex-direction: column; height: 100%; min-height: 0; }
.tc-head { padding: 18px 20px 14px; border-bottom: 1px solid var(--line); }
.tc-head-top { display: flex; align-items: center; gap: 8px; }
.tc-head-top h3 { margin: 0; font-size: var(--fs-lg); color: var(--ink); }
.tc-role {
  font-size: var(--fs-2xs); color: var(--accent); background: var(--accent-soft);
  border-radius: 999px; padding: 2px 8px;
}
.tc-icon {
  width: 26px; height: 26px; display: inline-flex; align-items: center; justify-content: center;
  border: 1px solid var(--line); background: var(--surface-2); color: var(--ink-2);
  border-radius: var(--radius-sm); cursor: pointer;
}
.tc-icon:hover { color: var(--ink); border-color: var(--accent); }
.spin { animation: tc-spin .8s linear infinite; }
@keyframes tc-spin { to { transform: rotate(360deg); } }
.tc-sub { margin: 8px 0 0; font-size: var(--fs-xs); color: var(--muted); }
.tc-sub b { font-family: var(--font-num); color: var(--warn); font-size: var(--fs-base); }

.tc-body { flex: 1; min-height: 0; overflow-y: auto; padding: 14px 20px 20px; }
.tc-group { margin-bottom: 18px; }
.tc-group.is-quiet .tc-g-label { color: var(--muted); }
.tc-group-head { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; }
.tc-g-label { font-size: var(--fs-sm); font-weight: 600; color: var(--ink); }
.tc-g-count {
  font-family: var(--font-num); font-size: var(--fs-2xs); font-weight: 700;
  color: #fff; background: var(--warn); border-radius: 999px; padding: 0 6px; line-height: 16px;
}
.tc-g-count.zero { background: var(--line); color: var(--muted); }
.tc-g-tag {
  font-size: var(--fs-2xs); color: var(--muted); border: 1px dashed var(--line);
  border-radius: 999px; padding: 0 6px;
}
.tc-g-hint { font-size: var(--fs-2xs); color: var(--muted); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.tc-empty { font-size: var(--fs-xs); color: var(--muted); padding: 6px 2px; }

.tc-item {
  display: flex; align-items: stretch; gap: 10px; width: 100%; text-align: left;
  background: var(--surface); border: 1px solid var(--line); border-radius: var(--radius-md);
  padding: 10px 12px; margin-bottom: 8px; cursor: pointer; transition: all .14s var(--ease);
}
.tc-item:hover { border-color: var(--accent); background: var(--accent-soft); transform: translateX(2px); }
.tc-bar { width: 3px; border-radius: 999px; background: var(--line); flex: none; }
.tc-item.lv-danger .tc-bar { background: var(--danger); }
.tc-item.lv-warn .tc-bar { background: var(--warn); }
.tc-item.lv-ok .tc-bar { background: var(--ok); }
.tc-item.lv-info .tc-bar { background: var(--accent); }
.tc-i-main { display: flex; flex-direction: column; gap: 3px; min-width: 0; }
.tc-i-title { font-size: var(--fs-sm); color: var(--ink); font-weight: 600; display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
.tc-i-status {
  font-style: normal; font-size: var(--fs-2xs); font-weight: 500; color: var(--ink-2);
  background: var(--surface-2); border: 1px solid var(--line); border-radius: 999px; padding: 0 6px;
}
.tc-i-note { font-size: var(--fs-xs); color: var(--ink-2); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.tc-i-side { display: flex; flex-direction: column; align-items: flex-end; justify-content: space-between; gap: 4px; flex: none; }
.tc-i-age { font-size: var(--fs-2xs); color: var(--warn); font-family: var(--font-num); }
.tc-i-go { font-size: var(--fs-xs); color: var(--accent); font-weight: 600; }
.tc-more {
  width: 100%; border: 1px dashed var(--line); background: transparent; color: var(--muted);
  font-size: var(--fs-xs); border-radius: var(--radius-sm); padding: 6px; cursor: pointer;
}
.tc-more:hover { color: var(--accent); border-color: var(--accent); }

.tc-state { padding: 40px 20px; text-align: center; color: var(--muted); font-size: var(--fs-sm); }
.tc-state.is-error { color: var(--danger); }
.tc-foot {
  display: flex; align-items: center; gap: 12px; padding: 10px 20px;
  border-top: 1px solid var(--line); background: var(--surface-2);
}
.tc-foot-note { flex: 1; font-size: var(--fs-2xs); color: var(--muted); }
</style>
