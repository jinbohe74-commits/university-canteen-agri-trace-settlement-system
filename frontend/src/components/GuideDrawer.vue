<template>
  <el-drawer
    v-model="s.open" size="680px" append-to-body :with-header="false"
    class="guide-drawer" @close="closeGuide"
  >
    <div class="gd-wrap">
      <!-- 顶部：我这个角色到底负责什么 -->
      <div class="gd-head">
        <div class="gd-eyebrow">怎么用 · 我的业务流程</div>
        <div class="gd-role">
          <span class="gd-role-badge">{{ d.roleLabel || roleName }}</span>
          <b>{{ d.userName || '' }}</b>
          <button class="gd-x" type="button" aria-label="关闭" @click="closeGuide">✕</button>
        </div>
        <p class="gd-duty">{{ d.duty || '正在读取你的岗位职责…' }}</p>

        <!-- 全链路四棒：让用户先建立"系统怎么转"的全局印象 -->
        <div class="gd-pipe">
          <div v-for="(p, i) in d.pipeline || []" :key="p.key" class="gd-pipe-item" :class="{ mine: p.mine }">
            <div class="gd-pipe-bar"></div>
            <div class="gd-pipe-label">{{ p.label }}<span v-if="p.mine" class="gd-pipe-me">你在这</span></div>
            <div class="gd-pipe-who">{{ p.who }}</div>
            <div class="gd-pipe-desc">{{ p.desc }}</div>
          </div>
        </div>
      </div>

      <div v-if="s.loading && !d.steps" class="gd-state">正在按你的角色读取待办…</div>
      <div v-else-if="s.error" class="gd-state is-error">{{ s.error }}</div>

      <template v-else>
        <div class="gd-sec">
          <b>你的 {{ (d.steps || []).length }} 步工作主线</b>
          <small>数字来自业务库当前真实待办，点「{{ firstCta }}」直接进对应页面</small>
        </div>

        <div class="gd-steps">
          <div
            v-for="st in d.steps || []" :key="st.key"
            class="gd-step" :class="{ 'is-urgent': st.urgent, 'is-first': st.key === d.firstActionable }"
          >
            <div class="gd-no">{{ st.no }}</div>
            <div class="gd-body">
              <div class="gd-title-row">
                <b class="gd-title">{{ st.title }}</b>
                <span class="gd-count" :class="{ zero: !st.count }">{{ st.countText }}</span>
                <span v-if="st.urgent" class="gd-flag">卡住下一棒</span>
              </div>
              <p class="gd-what">{{ st.what }}</p>
              <p class="gd-arrow"><span>做完之后</span>{{ st.next }}</p>
              <p v-if="st.footnote" class="gd-note">{{ st.footnote }}</p>
            </div>
            <el-button class="gd-go" :type="st.urgent ? 'primary' : 'default'" size="small" @click="go(st)">
              {{ st.count ? st.cta : '查看' }}
            </el-button>
          </div>
        </div>

        <!-- 角色相关的真实规则答疑，替代"请联系管理员"这类废话 -->
        <div class="gd-sec gd-sec-faq">
          <b>常见疑问</b><small>这些是系统里真实成立的规则</small>
        </div>
        <div class="gd-faq">
          <details v-for="(t, i) in d.tips || []" :key="i" class="gd-faq-item">
            <summary>{{ t.q }}</summary>
            <p>{{ t.a }}</p>
          </details>
        </div>

        <p class="gd-foot-note">
          这条主线随时可以从顶栏「怎么用」重新打开；页面右上角的
          <span class="gd-chip-demo">流程 {{ posText }}</span> 会告诉你当前停在第几步。
        </p>
      </template>
    </div>
  </el-drawer>
</template>

<script setup>
import { computed } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { guideState, closeGuide, stepAt } from '@/utils/guide'

/* 注意（L-055 同类第 3 次）：guideState 是 getter 函数，必须调用后拿到 reactive 实例。
   直接 `import { guideState as s }` 会让 s 是个函数，s.open 恒为 undefined，
   结果是接口 200、元素也渲染了，但抽屉永远不弹——上一轮 WorkItemDrawer 就栽在这里。 */
const s = guideState()

const router = useRouter()
const route = useRoute()
const d = computed(() => s.data || {})
const roleName = computed(() => s.data?.roleLabel || '')
const firstCta = computed(() => {
  const st = (d.value.steps || []).find((x) => x.key === d.value.firstActionable)
  return st?.cta || '进入页面'
})
const posText = computed(() => {
  const p = stepAt(route.path)
  if (!p) return '—'
  // 不在主线上（农户工作台/消息页等）不要拼出 "undefined/5"，直接说总览
  if (!p.step) return '总览'
  return (p.from === p.to ? p.from : `${p.from}–${p.to}`) + '/' + p.total
})

/** 引导里的每一步都必须真的落到对应页面，否则就是"点了没反应" */
const go = (st) => {
  if (!st?.path) { ElMessage.info('该步骤暂无对应页面'); return }
  s.open = false
  // 这一步是 0 笔待办时，去掉状态筛选：否则点进去是一张空表，用户会以为系统坏了。
  // 落到「全部」至少能看到自己这类单据的上下文，配合抽屉里的"暂无待办"才讲得通。
  const path = Number(st.count) > 0 ? st.path : String(st.path).split('?')[0]
  router.push(path)
}
</script>

<style scoped>
.gd-wrap { padding: 4px 2px 24px; }
.gd-head { border-bottom: 1px solid var(--line); padding-bottom: 14px; }
.gd-eyebrow { font-size: var(--fs-xs); color: var(--muted); letter-spacing: .06em; }
.gd-role { display: flex; align-items: center; gap: 10px; margin-top: 8px; }
.gd-role b { font-size: 17px; color: var(--ink); }
.gd-role-badge {
  font-size: var(--fs-xs); color: var(--accent); background: var(--accent-soft);
  border-radius: 999px; padding: 2px 10px;
}
.gd-x {
  margin-left: auto; border: 0; background: transparent; cursor: pointer;
  color: var(--muted); font-size: var(--fs-base); padding: 4px 6px; border-radius: var(--radius);
}
.gd-x:hover { background: var(--surface-2); color: var(--ink); }
.gd-duty { margin-top: 8px; font-size: var(--fs-sm); color: var(--ink-2); line-height: 1.7; }

.gd-pipe { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 8px; margin-top: 14px; }
.gd-pipe-item { background: var(--surface-2); border-radius: var(--radius-sm); padding: 9px 10px 10px; position: relative; }
.gd-pipe-item.mine { background: var(--accent-soft); }
.gd-pipe-bar { height: 3px; border-radius: 2px; background: var(--line); margin-bottom: 7px; }
.gd-pipe-item.mine .gd-pipe-bar { background: var(--accent); }
.gd-pipe-label { font-size: var(--fs-sm); font-weight: 600; color: var(--ink); display: flex; align-items: center; gap: 5px; }
.gd-pipe-me { font-size: var(--fs-2xs); color: var(--accent-ink); background: var(--accent); border-radius: 4px; padding: 1px 4px; }
.gd-pipe-who { font-size: var(--fs-2xs); color: var(--muted); margin-top: 3px; }
.gd-pipe-desc { font-size: var(--fs-2xs); color: var(--ink-2); margin-top: 4px; line-height: 1.5; }

.gd-state { padding: 40px 12px; text-align: center; font-size: var(--fs-sm); color: var(--muted); }
.gd-state.is-error { color: var(--danger); }

.gd-sec { display: flex; align-items: baseline; gap: 8px; margin: 18px 0 10px; }
.gd-sec b { font-size: var(--fs-base); color: var(--ink); }
.gd-sec small { margin-left: auto; font-size: var(--fs-xs); color: var(--muted); }

.gd-steps { display: flex; flex-direction: column; gap: 8px; }
.gd-step {
  display: flex; gap: 12px; align-items: flex-start;
  border: 1px solid var(--line); border-radius: var(--radius); padding: 12px 14px;
  background: var(--surface);
}
.gd-step.is-urgent { border-color: color-mix(in srgb, var(--warn) 45%, var(--line)); background: var(--warn-soft); }
.gd-step.is-first { box-shadow: inset 3px 0 0 var(--accent); }
.gd-no {
  flex: 0 0 26px; width: 26px; height: 26px; border-radius: 50%;
  display: flex; align-items: center; justify-content: center;
  background: var(--surface-2); color: var(--ink-2);
  font-family: var(--font-num); font-size: var(--fs-sm); font-weight: 700;
}
.gd-step.is-urgent .gd-no { background: var(--warn); color: #fff; }
.gd-body { flex: 1; min-width: 0; }
.gd-title-row { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.gd-title { font-size: var(--fs-base); color: var(--ink); }
.gd-count {
  font-size: var(--fs-xs); color: var(--ink-2); background: var(--surface-2);
  border-radius: 999px; padding: 1px 8px; font-family: var(--font-num);
}
.gd-count.zero { color: var(--muted); }
.gd-flag { font-size: var(--fs-2xs); color: var(--warn); border: 1px solid var(--warn); border-radius: 4px; padding: 0 4px; }
.gd-what { margin-top: 5px; font-size: var(--fs-sm); color: var(--ink-2); line-height: 1.6; }
.gd-arrow { margin-top: 4px; font-size: var(--fs-xs); color: var(--muted); line-height: 1.6; }
.gd-arrow span { color: var(--accent); margin-right: 6px; }
.gd-note { margin-top: 4px; font-size: var(--fs-xs); color: var(--warn); }
.gd-go { flex: 0 0 auto; align-self: center; }

.gd-faq { border-top: 1px solid var(--line); }
.gd-faq-item { border-bottom: 1px solid var(--line); padding: 9px 2px; }
.gd-faq-item summary {
  cursor: pointer; font-size: var(--fs-sm); color: var(--ink-2); list-style: none;
  display: flex; align-items: center; gap: 6px;
}
.gd-faq-item summary::before { content: '?'; color: var(--accent); font-weight: 700; }
.gd-faq-item summary::-webkit-details-marker { display: none; }
.gd-faq-item p { margin: 6px 0 2px 16px; font-size: var(--fs-xs); color: var(--muted); line-height: 1.7; }
.gd-foot-note { margin-top: 14px; font-size: var(--fs-xs); color: var(--muted); line-height: 1.7; }
.gd-chip-demo {
  font-family: var(--font-num); color: var(--accent); background: var(--accent-soft);
  border-radius: 4px; padding: 1px 6px;
}
</style>
