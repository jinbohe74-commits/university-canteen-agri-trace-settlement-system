<template>
  <div class="layout">
    <aside class="sidebar">
      <div class="brand">
        <div class="brand-mark">农</div>
        <div class="brand-text">
          <div class="brand-title">农校直供</div>
          <div class="brand-sub">溯源·结算管理平台</div>
        </div>
      </div>

      <nav class="menu">
        <div v-for="group in menus" :key="group.id" class="menu-group">
          <div class="menu-group-label">{{ group.name }}</div>
          <router-link
            v-for="item in group.children"
            :key="item.id"
            :to="item.path || ''"
            class="menu-item"
            :class="{ active: isActive(item.path) }"
          >
            <el-icon class="menu-icon"><component :is="iconOf(item.icon)" /></el-icon>
            <span class="menu-text">{{ item.name }}</span>
          </router-link>
        </div>
      </nav>

      <div class="sidebar-foot">
        <div class="foot-avatar">{{ user.avatar || '用' }}</div>
        <div class="foot-text">
          <div class="foot-name">{{ user.realName }}</div>
          <div class="foot-role">{{ roleName }}</div>
        </div>
        <el-popover placement="top-end" :width="200" trigger="click">
          <template #reference>
            <el-icon class="foot-more"><Setting /></el-icon>
          </template>
          <div class="pop-item" @click="goProfile">
            <el-icon><User /></el-icon><span>账号信息</span>
          </div>
          <div class="pop-item" @click="onLogout">
            <el-icon><SwitchButton /></el-icon><span>退出登录</span>
          </div>
        </el-popover>
      </div>
    </aside>

    <div class="main">
      <header class="topbar">
        <div class="crumb">
          <el-icon class="crumb-icon"><Location /></el-icon>
          <span>{{ crumb }}</span>
        </div>

        <div class="topbar-right">
          <div class="global-search">
            <el-icon><Search /></el-icon>
            <input
              v-model="keyword"
              placeholder="搜索订单、货源、农户、批次号…"
              @keyup.enter="doSearch"
            />
          </div>

          <el-tooltip content="待办与预警" placement="bottom">
            <button class="icon-btn" @click="goTodo">
              <el-icon><Bell /></el-icon>
              <span v-if="todoCount > 0" class="dot-badge">{{ todoCount > 99 ? '99+' : todoCount }}</span>
            </button>
          </el-tooltip>

          <div class="user-chip">
            <div class="avatar-badge">{{ user.avatar || '用' }}</div>
            <div class="user-meta">
              <div class="user-name">{{ user.realName }}</div>
              <div class="user-role">{{ roleName }}</div>
            </div>
          </div>
        </div>
      </header>

      <main class="content">
        <router-view v-slot="{ Component }">
          <transition name="fade" mode="out-in">
            <component :is="Component" />
          </transition>
        </router-view>
      </main>
    </div>
  </div>
</template>

<script setup>
import { computed, ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox, ElMessage } from 'element-plus'
import { useUserStore } from '@/store/user'
import { adminApi } from '@/api'

const route = useRoute()
const router = useRouter()
const store = useUserStore()
const keyword = ref('')
const todoCount = ref(0)

const user = computed(() => store.user || {})
const roleName = computed(() => store.roleName)
const menus = computed(() => store.menus || [])
const crumb = computed(() => route.meta.crumb || route.meta.title || '')

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
  // 溯源页面在三个角色共用
  return route.path.endsWith('/trace') && path.endsWith('/trace')
}

const doSearch = () => {
  if (!keyword.value.trim()) return
  const q = keyword.value.trim()
  if (store.role === 'ADMIN') router.push({ path: '/admin/orders', query: { keyword: q } })
  else if (store.role === 'FARMER') router.push({ path: '/farmer/orders', query: { keyword: q } })
  else if (store.role === 'PURCHASER') router.push({ path: '/purchaser/orders', query: { keyword: q } })
  else router.push({ path: '/inspector/tasks', query: { keyword: q } })
  ElMessage.success('已按关键词「' + q + '」检索')
}

const goTodo = () => {
  if (store.role === 'ADMIN') router.push('/admin/warnings')
  else if (store.role === 'FARMER') router.push('/farmer/messages')
  else if (store.role === 'PURCHASER') router.push('/purchaser/inbound')
  else router.push('/inspector/tasks')
}

const goProfile = () => {
  ElMessageBox.alert(
    `<div style="line-height:2;font-size:13px">
      <div><b>账号：</b>${user.value.username || ''}</div>
      <div><b>姓名：</b>${user.value.realName || ''}</div>
      <div><b>角色：</b>${roleName.value}</div>
      <div><b>所属：</b>${user.value.orgName || ''}</div>
      <div><b>手机：</b>${user.value.phone || ''}</div>
    </div>`,
    '账号信息',
    { dangerouslyUseHTMLString: true, confirmButtonText: '知道了' }
  )
}

const onLogout = async () => {
  await ElMessageBox.confirm('确定退出登录吗？', '提示', { type: 'warning' })
  await store.logout()
  router.replace('/login')
}

onMounted(async () => {
  if (!store.user) {
    try {
      await store.refresh()
    } catch (e) {
      /* ignore */
    }
  }
  if (store.role === 'ADMIN') {
    try {
      const res = await adminApi.dashboard()
      const todos = res.data.todos || []
      todoCount.value = todos.length
    } catch (e) {
      todoCount.value = 0
    }
  } else if (store.role === 'FARMER') {
    todoCount.value = store.user?.pendingOrders || 0
  } else if (store.role === 'PURCHASER') {
    todoCount.value = store.user?.pendingInbound || 0
  }
})
</script>

<style scoped>
.layout {
  display: flex;
  min-height: 100vh;
  background: var(--c-bg);
}

/* ---------------- 侧边栏 ---------------- */
.sidebar {
  width: var(--sidebar-w);
  flex-shrink: 0;
  background: linear-gradient(180deg, var(--c-sidebar-1) 0%, #123c2b 46%, var(--c-sidebar-2) 100%);
  display: flex;
  flex-direction: column;
  position: fixed;
  left: 0;
  top: 0;
  bottom: 0;
  z-index: 20;
}

.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  height: 68px;
  padding: 0 18px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.07);
  flex-shrink: 0;
}

.brand-mark {
  width: 34px;
  height: 34px;
  border-radius: 10px;
  background: linear-gradient(135deg, #34d399, #15803d);
  color: #fff;
  font-size: 16px;
  font-weight: 700;
  display: grid;
  place-items: center;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.22);
}

.brand-title {
  color: #fff;
  font-size: 15px;
  font-weight: 700;
  letter-spacing: 1px;
}

.brand-sub {
  color: rgba(255, 255, 255, 0.45);
  font-size: 10.5px;
  margin-top: 2px;
  letter-spacing: 0.4px;
}

.menu {
  flex: 1;
  overflow-y: auto;
  padding: 10px 10px 16px;
}

.menu::-webkit-scrollbar {
  width: 4px;
}

.menu::-webkit-scrollbar-thumb {
  background: rgba(255, 255, 255, 0.16);
}

.menu-group {
  margin-top: 12px;
}

.menu-group-label {
  padding: 0 10px 6px;
  color: rgba(255, 255, 255, 0.34);
  font-size: 10.5px;
  letter-spacing: 1.5px;
}

.menu-item {
  display: flex;
  align-items: center;
  gap: 9px;
  height: 36px;
  padding: 0 10px;
  margin-bottom: 2px;
  border-radius: 8px;
  color: var(--c-sidebar-text);
  font-size: 13px;
  transition: all 0.18s ease;
  position: relative;
}

.menu-item:hover {
  background: rgba(255, 255, 255, 0.06);
  color: #fff;
}

.menu-item.active {
  background: linear-gradient(90deg, rgba(34, 197, 94, 0.26), rgba(34, 197, 94, 0.08));
  color: #fff;
  box-shadow: inset 0 0 0 1px rgba(74, 222, 128, 0.24);
}

.menu-item.active::before {
  content: '';
  position: absolute;
  left: -10px;
  top: 9px;
  bottom: 9px;
  width: 3px;
  border-radius: 0 3px 3px 0;
  background: #34d399;
}

.menu-icon {
  font-size: 15px;
  opacity: 0.9;
}

.menu-text {
  font-size: 13px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.sidebar-foot {
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 12px 14px;
  border-top: 1px solid rgba(255, 255, 255, 0.07);
  background: rgba(0, 0, 0, 0.14);
  flex-shrink: 0;
}

.foot-avatar {
  width: 30px;
  height: 30px;
  border-radius: 9px;
  display: grid;
  place-items: center;
  background: rgba(255, 255, 255, 0.14);
  color: #fff;
  font-size: 13px;
  font-weight: 600;
}

.foot-name {
  color: #fff;
  font-size: 12.5px;
  font-weight: 600;
}

.foot-role {
  color: rgba(255, 255, 255, 0.42);
  font-size: 11px;
  margin-top: 1px;
}

.foot-more {
  margin-left: auto;
  color: rgba(255, 255, 255, 0.5);
  cursor: pointer;
  font-size: 15px;
}

.pop-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 10px;
  border-radius: 7px;
  font-size: 13px;
  cursor: pointer;
  color: #374151;
}

.pop-item:hover {
  background: #f2fbf5;
  color: var(--c-primary-dark);
}

/* ---------------- 主区域 ---------------- */
.main {
  flex: 1;
  margin-left: var(--sidebar-w);
  min-width: 0;
  display: flex;
  flex-direction: column;
}

.topbar {
  height: var(--header-h);
  background: #fff;
  border-bottom: 1px solid var(--c-border);
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 22px;
  position: sticky;
  top: 0;
  z-index: 15;
}

.crumb {
  display: flex;
  align-items: center;
  gap: 7px;
  font-size: 12.5px;
  color: var(--c-muted);
}

.crumb-icon {
  color: var(--c-primary);
  font-size: 14px;
}

.topbar-right {
  display: flex;
  align-items: center;
  gap: 16px;
}

.global-search {
  display: flex;
  align-items: center;
  gap: 7px;
  height: 32px;
  width: 268px;
  padding: 0 12px;
  border-radius: 16px;
  background: #f3f6f5;
  border: 1px solid transparent;
  color: var(--c-muted-2);
  transition: all 0.2s;
}

.global-search:focus-within {
  background: #fff;
  border-color: var(--c-primary-light);
  box-shadow: 0 0 0 3px rgba(34, 197, 94, 0.1);
}

.global-search input {
  border: none;
  outline: none;
  background: transparent;
  font-size: 12.5px;
  width: 100%;
  color: var(--c-text-2);
}

.icon-btn {
  position: relative;
  width: 32px;
  height: 32px;
  border-radius: 9px;
  border: 1px solid var(--c-border);
  background: #fff;
  color: var(--c-text-2);
  cursor: pointer;
  display: grid;
  place-items: center;
  font-size: 15px;
  transition: all 0.18s;
}

.icon-btn:hover {
  border-color: var(--c-primary-light);
  color: var(--c-primary);
}

.dot-badge {
  position: absolute;
  top: -5px;
  right: -5px;
  min-width: 16px;
  height: 16px;
  padding: 0 4px;
  border-radius: 8px;
  background: var(--c-red);
  color: #fff;
  font-size: 10px;
  line-height: 16px;
  text-align: center;
  font-weight: 600;
}

.user-chip {
  display: flex;
  align-items: center;
  gap: 9px;
  padding-left: 10px;
  border-left: 1px solid var(--c-border);
}

.user-name {
  font-size: 13px;
  font-weight: 600;
  color: #1b2a24;
}

.user-role {
  font-size: 10.5px;
  color: var(--c-muted-2);
  margin-top: 1px;
}

.content {
  flex: 1;
  min-width: 0;
}

.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.16s ease;
}

.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}
</style>
