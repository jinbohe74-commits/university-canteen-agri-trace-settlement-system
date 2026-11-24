<template>
  <div class="lay-shell">
    <!-- 侧栏：全站唯一骨架（208px 图标+文字），与明度模式无关 -->
    <aside class="lay-side">
      <div class="lay-brand">
        <div class="lay-mark">农</div>
        <div>
          <div class="lay-name">农校直供</div>
          <div class="lay-tag">TRACE &amp; SETTLEMENT</div>
        </div>
      </div>

      <nav class="lay-nav">
        <div v-for="group in menus" :key="group.id || group.name" class="lay-nav-group">
          <div class="lay-nav-label">{{ group.name }}</div>
          <router-link
            v-for="(item, idx) in group.children"
            :key="item.id || item.path"
            :to="item.path || ''"
            class="lay-nav-item"
            :class="{ on: isActive(item.path) }"
            :title="item.name"
            :aria-label="item.name"
          >
            <span class="lay-nav-ico"><el-icon><component :is="iconOf(item.icon)" /></el-icon></span>
            <span class="lay-nav-text">{{ item.name }}</span>
            <span class="lay-nav-num">{{ String(idx + 1).padStart(2, '0') }}</span>
          </router-link>
        </div>
      </nav>

      <div class="lay-side-foot">
        <span class="lay-mini-av">{{ user.avatar || user.realName?.slice(0, 1) || '用' }}</span>
        <span>{{ user.realName }}</span>
        <span class="muted">· {{ roleName }}</span>
        <button class="lay-icon-btn" style="margin-left:auto" title="退出登录" @click="onLogout">
          <el-icon><SwitchButton /></el-icon>
        </button>
      </div>
    </aside>

    <div class="lay-main">
      <header class="lay-topbar">
        <div class="lay-crumb">
          <span>{{ crumb.split(' / ')[0] }}</span>
          <span v-if="crumb.includes(' / ')">/</span>
          <b>{{ crumb.split(' / ').slice(-1)[0] }}</b>
        </div>

        <!-- 我在整条业务链的第几步：以前只有面包屑，用户不知道这页在流程里的位置 -->
        <button v-if="flowPos" class="lay-step" :title="'查看你的完整业务流程（' + (flowPos.title || '总览') + '）'" @click="openGuide">
          <span class="lay-step-k">流程</span>
          <template v-if="flowPos.step">
            <b>{{ flowPos.from === flowPos.to ? flowPos.from : flowPos.from + '–' + flowPos.to }}/{{ flowPos.total }}</b>
            <span class="lay-step-t">{{ flowPos.title }}</span>
            <span v-if="flowPos.count > 0" class="lay-step-num">{{ flowPos.count }}</span>
          </template>
          <template v-else>
            <b>{{ flowPos.total }}</b><span class="lay-step-t">步流程</span>
          </template>
        </button>

        <div class="lay-spacer"></div>

        <button class="lay-cmdbar" title="打开命令面板" @click="cmdOpen = true">
          <span>⌕</span><span>搜索页面、订单与操作</span>
          <span class="lay-kbd">{{ isMac ? '⌘K' : 'Ctrl K' }}</span>
        </button>

        <!-- 常用入口加文字：图标按钮必须 hover 才知道是什么，是"找不到功能"的常见来源 -->
        <button class="lay-act lay-act-guide" title="怎么用：我的业务流程与上手引导" @click="openGuide">
          <el-icon><QuestionFilled /></el-icon><span>怎么用</span>
        </button>

        <!-- 待办：点开是待办中心（分组清单 → 逐条直达单据），不是跳一个列表页让你自己找 -->
        <button class="lay-act" :title="todoCount > 0 ? todoCount + ' 件待办，点开逐条处理' : '当前没有待办'" @click="goTodo">
          <el-icon><Bell /></el-icon><span>待办</span>
          <span v-if="todoCount > 0" class="lay-act-num">{{ todoCount > 99 ? '99+' : todoCount }}</span>
        </button>

        <!-- 定向通知：只显示「发给我的」消息，不再广播其他角色的写操作 -->
        <div class="lay-notify">
          <button class="lay-act" title="发给我的通知" @click.stop="toggleNotify">
            <el-icon><ChatDotSquare /></el-icon><span>通知</span>
            <span v-if="notif.unread > 0" class="lay-act-num">{{ notif.unread > 99 ? '99+' : notif.unread }}</span>
            <span class="lay-live-dot" :class="{ on: rt.connected }"></span>
          </button>
          <div v-if="notifyOpen" class="lay-notify-panel" @click.stop>
            <div class="lnp-head">
              <b>发给我的通知</b>
              <span class="lnp-state" :class="{ on: rt.connected }">
                {{ rt.connected ? '● 已连接' : '○ 15 秒同步' }}
              </span>
              <button type="button" class="lnp-read" @click="onReadAll">全部已读</button>
            </div>

            <!-- 分组：未读是待处理线索，已读才允许删除 -->
            <div class="lnp-tabs">
              <button
                type="button" :class="{ on: notif.tab === 'unread' }"
                @click="setNotifTab('unread')"
              >未读 <b>{{ notif.unread }}</b></button>
              <button
                type="button" :class="{ on: notif.tab === 'read' }"
                @click="setNotifTab('read')"
              >已读 <b>{{ notif.read }}</b></button>
              <button
                type="button" class="lnp-clear" :class="{ dim: !notif.read }"
                title="删除全部已读通知（未读不受影响）"
                @click="onDeleteRead"
              >删除已读</button>
            </div>

            <div class="lnp-list">
              <div v-for="n in shownNotifications" :key="n.id" class="lnp-item is-link"
                   :class="{ 'is-unread': !n.isRead }"
                   @click="openNotification(n)">
                <div class="lnp-meta">
                  <span v-if="!n.isRead" class="lnp-dot"></span>
                  <span class="lnp-module">{{ n.title }}</span>
                  <span class="lnp-time">{{ shortTime(n.createTime) }}</span>
                  <button
                    v-if="n.isRead" type="button" class="lnp-del"
                    title="删除这条已读通知"
                    @click.stop="onDeleteOne(n)"
                  >✕</button>
                </div>
                <div class="lnp-content">{{ n.content }}</div>
                <div class="lnp-from">
                  <span>来自 {{ n.senderName || '系统' }}<template v-if="n.senderRole"> · {{ roleLabel(n.senderRole) }}</template></span>
                  <span v-if="n.bizNo" class="mono">{{ n.bizNo }}</span>
                  <span class="lnp-go">详情 ›</span>
                </div>
              </div>
              <div v-if="!shownNotifications.length" class="lnp-empty">
                {{ notif.tab === 'unread'
                  ? '没有未读通知'
                  : '暂无已读通知' }}
                <br /><small>{{ notif.tab === 'unread'
                  ? '有与你相关的订单 / 质检 / 配送 / 结算变动时会出现在这里'
                  : '点开未读通知后会自动归到「已读」，可在这里逐条删除或一键清空' }}</small>
              </div>
            </div>

            <div class="lnp-foot">
              未读通知需阅读后才能删除 · 共 {{ notif.items.length }} 条
            </div>
          </div>
        </div>

        <!-- 外观 / 弱动效 / 账号信息收进用户菜单：
             这三项是低频设置，常驻顶栏会把"待办、动态"这些高频入口挤成无字图标 -->
        <div class="lay-user">
          <button class="lay-userbox" :class="{ on: userOpen }" @click.stop="userOpen = !userOpen">
            <span class="lay-mini-av">{{ user.avatar || user.realName?.slice(0, 1) || '用' }}</span>
            <span class="lay-uname">{{ user.realName }}</span>
            <span class="lay-caret">▾</span>
          </button>
          <div v-if="userOpen" class="lay-usermenu" @click.stop>
            <div class="lum-head">
              <b>{{ user.realName }}</b>
              <span>{{ roleName }}</span>
            </div>
            <div class="lum-row">
              <span class="lum-k">外观</span>
              <div class="lum-seg">
                <button
                  v-for="m in MODES" :key="m.value" type="button"
                  :class="{ on: mode === m.value }" @click="mode = m.value"
                >{{ m.label }}</button>
              </div>
            </div>
            <label class="lum-row lum-check">
              <span class="lum-k">弱动效</span>
              <input v-model="reduceMotion" type="checkbox" />
            </label>
            <button class="lum-item" type="button" @click="goProfile(); userOpen = false">账号信息</button>
            <button class="lum-item" type="button" @click="openPwd(false); userOpen = false">修改密码</button>
            <button class="lum-item lum-danger" type="button" @click="onLogout">退出登录</button>
          </div>
        </div>
      </header>

      <main class="lay-content" ref="contentEl">
        <div v-if="pageLoading" class="ui-skeleton" aria-label="页面加载中">
          <div class="ui-sk-line" style="width:32%;height:22px"></div>
          <div class="ui-sk-line" style="width:18%"></div>
          <div class="ui-sk-line" style="width:100%;height:82px"></div>
          <div class="ui-sk-line" style="width:100%;height:190px"></div>
        </div>
        <!-- 未改初始密码：不挂载业务页面。
             否则每页 onMounted 都会打一批接口，全部被后端 428 拒绝，
             控制台刷满 "Request failed with status code 428"，用户也看不到任何数据。 -->
        <div v-else-if="store.needChangePwd" class="pwd-gate">
          <div class="pwd-gate-inner">
            <div class="pwd-gate-title">请先修改初始密码</div>
            <p>为了账号安全，首次登录必须先设置只有你知道的密码，改完即可正常使用系统。</p>
          </div>
        </div>
        <router-view v-else v-slot="{ Component }">
          <transition name="lay-fade" mode="out-in">
            <component :is="Component" />
          </transition>
        </router-view>
      </main>
    </div>

    <CmdPalette v-model="cmdOpen" :commands="commands" />
    <!-- 全局 KPI 明细抽屉：任何页面的 StatCard 点击后都在这里展示真实明细 -->
    <StatDetailDrawer />
    <!-- 全局单据工作台：通知「去处理」/看板待办/KPI 明细行都定位到具体业务对象 -->
    <WorkItemDrawer />
    <!-- 全局「怎么用」引导：角色职责 + 五步主线（真实待办数）+ 全链路位置 + FAQ -->
    <GuideDrawer />
    <!-- 待办中心：顶栏「待办」的数据与清单 -->
    <TodoCenterDrawer />
    <!-- 顶栏「通知」的详情弹窗：点条目先看完整内容，再去处理单据 -->
    <NotificationDetailDialog
      v-model="notifyDetailOpen"
      :item="notifyDetailItem"
      @handle="handleFromNotification"
    />

    <!-- 改密：管理员分配初始密码后，这里强制用户改成自己的密码 -->
    <el-dialog
      v-model="pwdVisible" :title="pwdForce ? '请先修改初始密码' : '修改密码'" width="440px"
      :close-on-click-modal="false" :show-close="!pwdForce" :close-on-press-escape="!pwdForce"
      append-to-body
    >
      <div v-if="pwdForce" class="pwd-tip">
        <b>{{ user.realName || user.username }}</b>，你当前使用的是管理员分配的初始密码。
        为了账号安全，请先改成只有你知道的密码，改完后才能进入系统。
      </div>
      <el-form label-width="86px" @submit.prevent>
        <el-form-item label="当前密码">
          <el-input v-model="pwdForm.current" type="password" show-password placeholder="管理员给你的初始密码" />
        </el-form-item>
        <el-form-item label="新密码">
          <el-input v-model="pwdForm.next" type="password" show-password placeholder="至少 8 位，含字母和数字" />
        </el-form-item>
        <el-form-item label="确认新密码">
          <el-input v-model="pwdForm.confirm" type="password" show-password placeholder="再输入一次新密码" @keyup.enter="submitPwd" />
        </el-form-item>
      </el-form>
      <div class="pwd-hint">
        修改后，其他设备上已登录的会话会立即失效；当前页面会继续有效。
      </div>
      <template #footer>
        <el-button v-if="pwdForce" @click="onLogout">退出登录</el-button>
        <el-button v-else @click="pwdVisible = false">取消</el-button>
        <el-button type="primary" :loading="pwdSaving" @click="submitPwd">确认修改</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, h, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox, ElMessage } from 'element-plus'
import { useUserStore } from '@/store/user'
import { adminApi } from '@/api'
import CmdPalette from '@/components/CmdPalette.vue'
import StatDetailDrawer from '@/components/StatDetailDrawer.vue'
import WorkItemDrawer from '@/components/WorkItemDrawer.vue'
import GuideDrawer from '@/components/GuideDrawer.vue'
import TodoCenterDrawer from '@/components/TodoCenterDrawer.vue'
import NotificationDetailDialog from '@/components/NotificationDetailDialog.vue'
import { enhancePage, installEnhancements, showToast } from '@/utils/ui-enhance'
import { realtimeState, startRealtime, stopRealtime } from '@/utils/realtime'
import {
  notifState, refreshNotifications, readNotification, readAllNotifications,
  deleteNotification, deleteReadNotifications, setNotifTab,
  startNotifications, stopNotifications
} from '@/utils/notification'
import { openWorkItem } from '@/utils/workItem'
import { guideState, openGuide as doOpenGuide, loadGuide, stepAt, maybeAutoOpenGuide } from '@/utils/guide'
import { todoCenterState, openTodoCenter, loadTodoCenter } from '@/utils/todoCenter'
import { installTableHScroll, uninstallTableHScroll } from '@/utils/tableHScroll'

const route = useRoute()
const router = useRouter()
const store = useUserStore()
const contentEl = ref(null)
const cmdOpen = ref(false)
const pageLoading = ref(false)
/* 待办数字只认待办中心的数据源：
   原来农户/采购员读 store.user.pendingOrders / pendingInbound（登录态里根本没有这两个字段），
   质检员连分支都没有 → 徽标永远不显示，用户自然觉得这个功能是摆设。 */
const todoC = todoCenterState()
const todoCount = computed(() => Number(todoC.total || 0))
const mode = ref(['day', 'paper', 'night'].includes(localStorage.getItem('ct_ui_mode'))
  ? localStorage.getItem('ct_ui_mode') : 'day')
const reduceMotion = ref(localStorage.getItem('ct_reduce_motion') === '1')

const rt = realtimeState()
const notif = notifState()
const guide = guideState()
const notifyOpen = ref(false)

const ROLE_LABELS = { ADMIN: '管理员', FARMER: '农户', PURCHASER: '采购员', INSPECTOR: '质检配送员' }
const roleLabel = (r) => ROLE_LABELS[r] || r || '系统'

/** 面板按「未读 / 已读」分组显示；数据仍是一次拉回来的同一份 */
const shownNotifications = computed(() =>
  (notif.items || []).filter(n => (notif.tab === 'read' ? !!Number(n.isRead) : !Number(n.isRead))))

const notifyDetailOpen = ref(false)
const notifyDetailItem = ref(null)

/** 点通知 = 标已读 + 弹出详情。
 *  以前直接 openWorkItem 跳走，用户还没看清是什么事就被换页了；现在先看清再决定是否去处理。 */
const openNotification = async (n) => {
  if (!Number(n.isRead)) await readNotification(n.id)
  notifyOpen.value = false
  notifyDetailItem.value = { ...n, isRead: 1 }
  notifyDetailOpen.value = true
}

/** 详情弹窗里的「去处理这张单」：定位到具体业务对象的工作台 */
const handleFromNotification = async (n) => {
  if (!n) return
  await openWorkItem({
    scope: 'notification',
    bizType: n.bizType,
    bizNo: n.bizNo,
    orderId: n.orderId,
    title: (n.title || '业务通知') + ' · 单据详情'
  })
}

/** 单条删除：未读不给删，后端也带 is_read=1，两层都挡 */
const onDeleteOne = async (n) => {
  try {
    const ok = await deleteNotification(n.id)
    if (ok) ElMessage.success('已删除这条通知')
    else ElMessage.warning('未读通知不能删除，请先点开阅读')
  } catch {
    // 业务错误已由请求拦截器统一提示
  }
}

/** 一键删除已读：不可恢复，先二次确认 */
const onDeleteRead = async () => {
  if (!notif.read) {
    ElMessage.info('当前没有可删除的已读通知')
    return
  }
  try {
    await ElMessageBox.confirm(
      `将删除全部 ${notif.read} 条已读通知，删除后不可恢复；未读通知不受影响。`,
      '删除已读通知',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消', confirmButtonClass: 'el-button--danger' }
    )
  } catch {
    return
  }
  try {
    const n = await deleteReadNotifications()
    ElMessage.success(`已删除 ${n} 条已读通知`)
  } catch {
    // 拦截器已提示
  }
}

const onReadAll = async () => {
  if (!notif.unread) {
    ElMessage.info('当前没有未读通知')
    return
  }
  await readAllNotifications()
  await refreshNotifications()   // 计数以服务端为准
}
const userOpen = ref(false)
// Windows 上没有 ⌘ 键，写 ⌘K 等于给用户一个按不出来的快捷键提示
const isMac = /Mac|iPhone|iPad/i.test(navigator.platform || navigator.userAgent || '')
const toggleNotify = () => {
  notifyOpen.value = !notifyOpen.value
  userOpen.value = false
  if (notifyOpen.value) refreshNotifications()   // 打开面板立即取最新，不等轮询
}
const closePanels = () => { notifyOpen.value = false; userOpen.value = false }
const shortTime = (s) => String(s || '').replace('T', ' ').slice(11, 16) || '—'
const onForceLogout = (e) => {
  stopRealtime()
  store.clear()
  ElMessage.error(e.detail?.message || '账号已被停用，请重新登录')
  router.replace('/login')
}

const user = computed(() => store.user || {})
const roleName = computed(() => store.roleName)
const menus = computed(() => store.menus || [])
const crumb = computed(() => route.meta.crumb || route.meta.title || '工作台')
const flatMenus = computed(() => menus.value.flatMap((g) => g.children || []))
/* 流程定位：读的是 guide 状态里的 steps，所以引导数据到位后 chip 会自动出现 */
const flowPos = computed(() => (guide.data ? stepAt(route.path) : null))
const openGuide = () => doOpenGuide()

const ICONS = {
  DataBoard: 'DataBoard', Odometer: 'Odometer', Folder: 'Folder', Avatar: 'UserFilled',
  Shop: 'Shop', Apple: 'Apple', User: 'User', Monitor: 'Monitor', Tickets: 'Tickets',
  Money: 'Money', Warning: 'Warning', Search: 'Search', Setting: 'Setting',
  Operation: 'Operation', Document: 'Document', Goods: 'Goods', HomeFilled: 'HomeFilled',
  Box: 'Box', List: 'List', Wallet: 'Wallet', Star: 'Star', Bell: 'Bell',
  ShoppingCart: 'ShoppingCart', EditPen: 'EditPen', MagicStick: 'MagicStick',
  ChatDotSquare: 'ChatDotSquare', Van: 'Van', DocumentChecked: 'DocumentChecked',
  Location: 'Location', Refresh: 'Refresh', Download: 'Download', Plus: 'Plus'
}
const iconOf = (name) => ICONS[name] || 'Menu'

const isActive = (path) => {
  if (!path) return false
  if (route.path === path) return true
  return route.path.endsWith('/trace') && path.endsWith('/trace')
}

/* ---------------- 外观：全站唯一明度模式，与路由无关 ----------------
   曾经按 path 自动分配 A/B/C 三套皮肤，翻页时字体、导航、明暗全变，
   观感像换了几个系统。现在用户选一次，html[data-mode] 恒定不变。 */
function applyMode() {
  const v = ['day', 'paper', 'night'].includes(mode.value) ? mode.value : 'day'
  if (document.documentElement.dataset.mode !== v) {
    document.documentElement.dataset.mode = v
  }
}
const MODE_NAME = { day: '浅色', paper: '暖纸', night: '深色' }
const MODES = [
  { value: 'day', label: '浅色' },
  { value: 'paper', label: '暖纸' },
  { value: 'night', label: '深色' }
]
watch(mode, (v) => {
  localStorage.setItem('ct_ui_mode', v)
  applyMode()
  showToast('外观已设为「' + MODE_NAME[v] + '」，全站统一生效')
})
watch(reduceMotion, (v) => {
  localStorage.setItem('ct_reduce_motion', v ? '1' : '0')
  document.documentElement.classList.toggle('reduce-motion', v)
})

/* ---------------- 命令面板 ---------------- */
const commands = computed(() => {
  const pages = flatMenus.value.map((m) => ({
    key: m.path, label: m.name, hint: m.path, icon: '›', path: m.path
  }))
  return [
    { key: 'home', label: '回到我的工作台', hint: store.home, icon: '⌂', path: store.home },
    ...pages,
    { key: 'refresh', label: '刷新当前页面数据', hint: '重新请求接口', icon: '↻', run: () => window.location.reload() },
    { key: 'notifications', label: '查看发给我的通知', hint: '与我相关的订单 / 结算变动', icon: '◉', run: () => { notifyOpen.value = true; setNotifTab('unread'); refreshNotifications() } },
    { key: 'mode-day', label: '外观设为浅色', hint: '全站统一生效', icon: '◐', run: () => { mode.value = 'day' } },
    { key: 'mode-paper', label: '外观设为暖纸', hint: '全站统一生效', icon: '◑', run: () => { mode.value = 'paper' } },
    { key: 'mode-night', label: '外观设为深色', hint: '全站统一生效', icon: '◒', run: () => { mode.value = 'night' } },
    { key: 'logout', label: '退出登录', hint: '注销当前会话', icon: '⇥', run: onLogout }
  ]
})

/* ---------------- 保持原有业务动作 ---------------- */
const goTodo = () => openTodoCenter()

const goProfile = () => {
  // 沿用 VNode 渲染，杜绝后端字段被当作 HTML 拼接引发的存储型 XSS
  const row = (label, value) => h('div', { style: 'line-height:2;font-size: var(--fs-base)' }, [
    h('b', null, label + '：'),
    h('span', null, value == null ? '' : String(value))
  ])
  ElMessageBox.alert(h('div', null, [
    row('账号', user.value.username), row('姓名', user.value.realName),
    row('角色', roleName.value), row('所属', user.value.orgName), row('手机', user.value.phone)
  ]), '账号信息', { confirmButtonText: '知道了' })
}

const onLogout = async () => {
  try {
    await ElMessageBox.confirm('确定退出登录吗？', '提示', { type: 'warning' })
  } catch (e) { return }
  stopRealtime()
  await store.logout()
  router.replace('/login')
}

/* ---------------- 修改密码 ----------------
   两个入口共用同一套表单：
   · self  —— 用户主动从右上角菜单点进来；
   · force —— 账号 needChangePwd=1（新建 / 被重置 / 后端 428），不可关闭。 */
const pwdVisible = ref(false)
const pwdForce = ref(false)
const pwdSaving = ref(false)
const pwdForm = ref({ current: '', next: '', confirm: '' })

const openPwd = (force) => {
  pwdForce.value = !!force
  pwdForm.value = { current: '', next: '', confirm: '' }
  pwdVisible.value = true
}

const submitPwd = async () => {
  const { current, next, confirm } = pwdForm.value
  if (!current) { ElMessage.warning('请输入当前密码'); return }
  if (!next || next.length < 8 || !/[A-Za-z]/.test(next) || !/\d/.test(next)) {
    ElMessage.warning('新密码至少 8 位，且须同时包含字母和数字'); return
  }
  if (next === current) { ElMessage.warning('新密码不能与当前密码相同'); return }
  if (next !== confirm) { ElMessage.warning('两次输入的新密码不一致'); return }
  pwdSaving.value = true
  try {
    await store.changePassword({ currentPassword: current, newPassword: next })
    pwdVisible.value = false
    ElMessage.success('密码已修改')
    if (pwdForce.value) {
      // 之前被 428 挡下的页面数据是空的，改完密码用新凭证整页重取一次
      window.location.reload()
    }
  } catch (e) {
    /* 请求拦截器已提示具体原因 */
  } finally {
    pwdSaving.value = false
  }
}

const onNeedChangePwd = (e) => {
  if (pwdVisible.value) return
  openPwd(true)
  if (e?.detail?.message) ElMessage.warning(e.detail.message)
}

/* ---------------- 页面切换：骨架屏 + 交互层重建 ---------------- */
let enhanceTimers = []
const scheduleEnhance = () => {
  enhanceTimers.forEach(clearTimeout)
  enhanceTimers = []
  const path = route.fullPath
  // 异步表格分批到达：240ms 收骨架屏，720/1500ms 补注入（已存在工具条时不重置筛选）
  ;[240, 720, 1500].forEach((ms, i) => {
    enhanceTimers.push(setTimeout(() => {
      if (route.fullPath !== path) return
      if (i === 0) pageLoading.value = false
      nextTick(() => enhancePage(contentEl.value))
    }, ms))
  })
}
watch(() => route.fullPath, () => {
  // 注意：这里刻意不触碰 data-mode，跨页外观必须恒定。
  pageLoading.value = true
  scheduleEnhance()
  // 换页顺手刷新待办数字：用户可能刚在上一页处理掉一单，
  // 不刷新的话徽标还停在旧数字，等于又在骗人。
  if (['ADMIN', 'FARMER', 'PURCHASER', 'INSPECTOR'].includes(store.role)) loadTodoCenter()
})

onMounted(async () => {
  installEnhancements()
  installTableHScroll()
  document.documentElement.classList.toggle('reduce-motion', reduceMotion.value)
  applyMode()
  window.addEventListener('canteen:force-logout', onForceLogout)
  window.addEventListener('canteen:need-change-pwd', onNeedChangePwd)
  window.addEventListener('click', closePanels)

  if (!store.user) {
    try { await store.refresh() } catch (e) { /* ignore */ }
  }
  // 初始密码未改：进任何页面都先把改密弹窗顶到最前面（后端同时返回 428 兜底）
  if (store.needChangePwd) openPwd(true)
  // 跨角色实时通道：SSE + 轮询兜底，登录态有效时启动
  try { await startRealtime() } catch (e) { /* 不阻断页面 */ }
  startNotifications()
  // 四个角色统一取待办中心，数字与各自列表页同源
  if (['ADMIN', 'FARMER', 'PURCHASER', 'INSPECTOR'].includes(store.role)) {
    loadTodoCenter()
  }

  pageLoading.value = true
  scheduleEnhance()

  // 顶部流程进度与「怎么用」需要角色主线；首次登录自动打开一次（每角色一次）
  loadGuide().then((data) => {
    if (!data) return
    if (!['ADMIN', 'FARMER', 'PURCHASER', 'INSPECTOR'].includes(store.role)) return
    setTimeout(() => { maybeAutoOpenGuide(store.role) }, 900)
  })
})
onBeforeUnmount(() => {
  enhanceTimers.forEach(clearTimeout)
  stopRealtime()
  stopNotifications()
  uninstallTableHScroll()
  window.removeEventListener('canteen:force-logout', onForceLogout)
  window.removeEventListener('canteen:need-change-pwd', onNeedChangePwd)
  window.removeEventListener('click', closePanels)
})
</script>

<style>
/* 页面转场：与 .page 自身的 pageIn 叠加，保持轻快 */
.lay-fade-enter-active, .lay-fade-leave-active { transition: opacity .16s ease; }
.lay-fade-enter-from, .lay-fade-leave-to { opacity: 0; }

/* ---------------- 流程定位 chip / 「怎么用」入口 ----------------
   用户不知道"这页在整条业务里的位置"，所以面包屑后面常驻一个可点的步骤标。 */
.lay-step {
  display: inline-flex; align-items: center; gap: 6px; margin-left: 12px;
  border: 1px solid var(--line); background: var(--surface-2);
  border-radius: 999px; padding: 3px 10px 3px 8px; cursor: pointer;
  font-size: var(--fs-xs); color: var(--ink-2); transition: all .15s var(--ease);
}
.lay-step:hover { border-color: var(--accent); background: var(--accent-soft); color: var(--ink); }
.lay-step-k { color: var(--muted); }
.lay-step b { font-family: var(--font-num); color: var(--accent); }
.lay-step-t { max-width: 132px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.lay-step-num {
  font-family: var(--font-num); font-size: var(--fs-2xs); color: #fff;
  background: var(--warn); border-radius: 999px; padding: 0 5px;
}
.lay-act-guide span { font-weight: 600; }

/* ---------------- 实时动态通知中心 ---------------- */
.lay-notify { position: relative; }
.lay-live-dot {
  position: absolute; right: 5px; bottom: 5px; width: 7px; height: 7px;
  border-radius: 50%; background: var(--muted); border: 1.5px solid var(--surface);
}
.lay-live-dot.on { background: var(--accent); box-shadow: 0 0 0 2px var(--accent-soft); }
.lay-notify-panel {
  position: absolute; right: -8px; top: 42px; z-index: var(--z-pop);
  width: 340px; max-height: 460px; display: flex; flex-direction: column;
  background: var(--surface); color: var(--ink);
  border: 1px solid var(--line); border-radius: var(--radius-lg);
  box-shadow: var(--shadow); overflow: hidden;
}
.lnp-head {
  display: flex; align-items: center; gap: 10px;
  padding: 12px 14px; border-bottom: 1px solid var(--line);
}
.lnp-head b { font-size: var(--fs-base); }
.lnp-state { font-size: var(--fs-xs); color: var(--muted); }
.lnp-state.on { color: var(--accent); }
.lnp-read {
  margin-left: auto; border: 0; background: transparent; cursor: pointer;
  font-size: var(--fs-xs); color: var(--accent);
}
/* flex:1 + min-height:0 才能让长列表在面板内部滚动，
   否则最后一条会被底部说明条压掉半行（看起来像文字被裁切）。
   列表底部留 8px 余量 + 说明条上边阴影：滚动裁切发生在条目间隙附近，不会压字。 */
.lnp-list {
  flex: 1 1 auto; min-height: 0; overflow-y: auto;
  padding: 4px 0 8px; overscroll-behavior: contain;
}
.lnp-item { padding: 9px 14px; border-bottom: 1px solid var(--line); }
.lnp-item:last-child { border-bottom: 0; }
.lnp-meta { display: flex; align-items: center; gap: 8px; }
.lnp-module {
  font-size: var(--fs-2xs); font-weight: 700; color: var(--accent);
  background: var(--accent-soft); padding: 2px 6px; border-radius: var(--radius);
}
.lnp-time { font-size: var(--fs-2xs); color: var(--muted); margin-left: auto; }
.lnp-content { margin-top: 5px; font-size: var(--fs-sm); line-height: 1.55; color: var(--ink-2); }
.lnp-empty { padding: 24px; text-align: center; font-size: var(--fs-sm); color: var(--muted); }
.lnp-empty small { display: block; margin-top: 6px; font-size: var(--fs-2xs); color: var(--muted); }
/* 定向通知：条目本身可点（跳业务页），未读态要看得出来 */
.lnp-item.is-link { cursor: pointer; transition: background .15s var(--ease); }
.lnp-item.is-link:hover { background: var(--surface-2); }
.lnp-item.is-unread { background: color-mix(in srgb, var(--accent) 6%, transparent); }
.lnp-dot { width: 7px; height: 7px; border-radius: 50%; background: var(--accent); flex: 0 0 auto; }
.lnp-from {
  display: flex; align-items: center; flex-wrap: wrap; gap: 8px;
  margin-top: 6px; font-size: var(--fs-2xs); color: var(--muted);
}
.lnp-from .mono { font-family: var(--font-num); }
.lnp-go { margin-left: auto; color: var(--accent); font-weight: 700; }

/* 分组切换：未读 / 已读，右侧是「删除已读」 */
.lnp-tabs {
  display: flex; align-items: center; gap: 4px;
  padding: 7px 12px; border-bottom: 1px solid var(--line); background: var(--surface-2);
}
.lnp-tabs button {
  border: 1px solid transparent; background: transparent; cursor: pointer;
  font-size: var(--fs-xs); color: var(--muted); padding: 3px 10px;
  border-radius: 999px; transition: all .15s var(--ease);
}
.lnp-tabs button b { font-family: var(--font-num); font-weight: 700; }
.lnp-tabs button:hover { color: var(--ink); }
.lnp-tabs button.on {
  color: var(--accent); font-weight: 700;
  background: var(--accent-soft);
  border-color: color-mix(in srgb, var(--accent) 32%, transparent);
}
.lnp-clear { margin-left: auto; border-color: var(--line) !important; }
.lnp-clear:hover { color: var(--danger); border-color: var(--danger) !important; background: var(--danger-soft); }
.lnp-clear.dim { opacity: .6; }

/* 删除单条：常显（hover 才出现的按钮等于没有按钮），未读不给入口 */
.lnp-del {
  flex: 0 0 auto; border: 0; background: transparent; cursor: pointer;
  color: var(--muted); font-size: 12px; line-height: 1; padding: 3px 5px;
  border-radius: 4px; opacity: .6; transition: all .15s var(--ease);
}
.lnp-del:hover { opacity: 1; color: var(--danger); background: var(--danger-soft); }
.lnp-foot {
  position: relative; padding: 7px 14px; border-top: 1px solid var(--line);
  font-size: var(--fs-2xs); color: var(--muted); background: var(--surface-2);
  box-shadow: 0 -6px 10px -8px rgba(0, 0, 0, .28);
}

/* 改密弹窗：强制态要说清楚"为什么必须改"，而不是只给一个表单 */
.pwd-tip {
  padding: 10px 12px; margin-bottom: 14px; border-radius: 10px;
  background: var(--accent-soft); border: 1px solid color-mix(in srgb, var(--accent) 26%, transparent);
  font-size: var(--fs-sm); line-height: 1.65; color: var(--ink-2);
}
.pwd-tip b { color: var(--ink); }
.pwd-hint { margin-top: 4px; font-size: var(--fs-2xs); color: var(--muted); line-height: 1.6; }

/* 未改密时的占位：不挂载业务页，避免整页 428 */
.pwd-gate {
  display: grid; place-items: center; min-height: 52vh; padding: 24px;
}
.pwd-gate-inner {
  max-width: 420px; text-align: center; padding: 26px 28px;
  border: 1px dashed var(--line-strong); border-radius: 14px; background: var(--surface);
}
.pwd-gate-title { font-size: var(--fs-lg); font-weight: 700; color: var(--ink); }
.pwd-gate-inner p { margin-top: 8px; font-size: var(--fs-sm); line-height: 1.7; color: var(--muted); }

/* 「删除已读」的二次确认：危险操作按钮必须是红色。
   绿色主按钮会让人当成"继续"，而这里是不可恢复的删除。 */
.el-message-box__btns .el-button.el-button--danger {
  --el-button-bg-color: var(--danger);
  --el-button-border-color: var(--danger);
  --el-button-hover-bg-color: #A83A33;
  --el-button-hover-border-color: #A83A33;
  --el-button-active-bg-color: #93322C;
  --el-button-active-border-color: #93322C;
  --el-button-text-color: #fff;
  background-color: var(--danger);
  border-color: var(--danger);
  color: #fff;
}
</style>
