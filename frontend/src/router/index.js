import { createRouter, createWebHistory } from 'vue-router'
import { useUserStore } from '@/store/user'

const routes = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('@/views/login/index.vue'),
    meta: { public: true, title: '登录' }
  },
  {
    path: '/',
    component: () => import('@/layout/AppLayout.vue'),
    redirect: '/login',
    children: [
      /* ---------------- 系统管理员 ---------------- */
      { path: 'admin/dashboard', component: () => import('@/views/admin/Dashboard.vue'), meta: { roles: ['ADMIN'], title: '数据看板', crumb: '概览 / 数据看板' } },
      { path: 'admin/farmers', component: () => import('@/views/admin/Farmers.vue'), meta: { roles: ['ADMIN'], title: '农户/合作社管理', crumb: '基础档案 / 农户·合作社管理' } },
      { path: 'admin/canteens', component: () => import('@/views/admin/Canteens.vue'), meta: { roles: ['ADMIN'], title: '食堂档案管理', crumb: '基础档案 / 食堂档案管理' } },
      { path: 'admin/products', component: () => import('@/views/admin/Products.vue'), meta: { roles: ['ADMIN'], title: '农产品档案', crumb: '基础档案 / 农产品档案' } },
      { path: 'admin/users', component: () => import('@/views/admin/Users.vue'), meta: { roles: ['ADMIN'], title: '用户与权限', crumb: '系统管理 / 用户与权限' } },
      { path: 'admin/orders', component: () => import('@/views/admin/Orders.vue'), meta: { roles: ['ADMIN'], title: '订单监管', crumb: '业务监管 / 订单监管' } },
      { path: 'admin/settlements', component: () => import('@/views/admin/Settlements.vue'), meta: { roles: ['ADMIN'], title: '结算审核', crumb: '业务监管 / 结算审核' } },
      { path: 'admin/warnings', component: () => import('@/views/admin/Warnings.vue'), meta: { roles: ['ADMIN'], title: '食安预警管理', crumb: '业务监管 / 食安预警管理' } },
      { path: 'admin/trace', component: () => import('@/views/admin/Trace.vue'), meta: { roles: ['ADMIN'], title: '批次溯源查询', crumb: '业务监管 / 批次溯源查询' } },
      { path: 'admin/config', component: () => import('@/views/admin/Config.vue'), meta: { roles: ['ADMIN'], title: '系统参数配置', crumb: '系统管理 / 系统参数配置' } },
      { path: 'admin/logs', component: () => import('@/views/admin/Logs.vue'), meta: { roles: ['ADMIN'], title: '操作日志', crumb: '系统管理 / 操作日志' } },

      /* ---------------- 农户/合作社 ---------------- */
      { path: 'farmer/workbench', component: () => import('@/views/farmer/Workbench.vue'), meta: { roles: ['FARMER'], title: '我的工作台', crumb: '生产经营 / 我的工作台' } },
      { path: 'farmer/sources', component: () => import('@/views/farmer/Sources.vue'), meta: { roles: ['FARMER'], title: '货源管理', crumb: '生产经营 / 货源管理' } },
      { path: 'farmer/orders', component: () => import('@/views/farmer/Orders.vue'), meta: { roles: ['FARMER'], title: '订单管理', crumb: '生产经营 / 订单管理' } },
      { path: 'farmer/settlement', component: () => import('@/views/farmer/Settlement.vue'), meta: { roles: ['FARMER'], title: '结算与收入', crumb: '生产经营 / 结算与收入' } },
      { path: 'farmer/credit', component: () => import('@/views/farmer/Credit.vue'), meta: { roles: ['FARMER'], title: '信用评价', crumb: '生产经营 / 信用评价' } },
      { path: 'farmer/messages', component: () => import('@/views/farmer/Messages.vue'), meta: { roles: ['FARMER'], title: '消息提醒', crumb: '生产经营 / 消息提醒' } },

      /* ---------------- 食堂采购员 ---------------- */
      { path: 'purchaser/demands', component: () => import('@/views/purchaser/Demands.vue'), meta: { roles: ['PURCHASER'], title: '采购需求管理', crumb: '采购协同 / 采购需求' } },
      { path: 'purchaser/sources', component: () => import('@/views/purchaser/Sources.vue'), meta: { roles: ['PURCHASER'], title: '货源大厅', crumb: '采购协同 / 货源大厅' } },
      { path: 'purchaser/match', component: () => import('@/views/purchaser/Match.vue'), meta: { roles: ['PURCHASER'], title: '智能匹配', crumb: '采购协同 / 智能匹配' } },
      { path: 'purchaser/orders', component: () => import('@/views/purchaser/Orders.vue'), meta: { roles: ['PURCHASER'], title: '我的订单', crumb: '采购协同 / 我的订单' } },
      { path: 'purchaser/inbound', component: () => import('@/views/purchaser/Inbound.vue'), meta: { roles: ['PURCHASER'], title: '入库确认', crumb: '采购协同 / 入库确认' } },
      { path: 'purchaser/evaluation', component: () => import('@/views/purchaser/Evaluation.vue'), meta: { roles: ['PURCHASER'], title: '采购评价', crumb: '采购协同 / 采购评价' } },
      { path: 'purchaser/trace', component: () => import('@/views/admin/Trace.vue'), meta: { roles: ['PURCHASER'], title: '批次溯源', crumb: '采购协同 / 批次溯源' } },

      /* ---------------- 质检配送员 ---------------- */
      { path: 'inspector/tasks', component: () => import('@/views/inspector/Tasks.vue'), meta: { roles: ['INSPECTOR'], title: '我的任务', crumb: '质检配送 / 我的任务' } },
      { path: 'inspector/quality', component: () => import('@/views/inspector/Quality.vue'), meta: { roles: ['INSPECTOR'], title: '质检录入', crumb: '质检配送 / 质检录入' } },
      { path: 'inspector/delivery', component: () => import('@/views/inspector/Delivery.vue'), meta: { roles: ['INSPECTOR'], title: '配送任务', crumb: '质检配送 / 配送任务' } },
      { path: 'inspector/exception', component: () => import('@/views/inspector/Exception.vue'), meta: { roles: ['INSPECTOR'], title: '异常上报', crumb: '质检配送 / 异常上报' } },
      { path: 'inspector/trace', component: () => import('@/views/admin/Trace.vue'), meta: { roles: ['INSPECTOR'], title: '批次溯源', crumb: '质检配送 / 批次溯源' } },

      { path: 'trace', component: () => import('@/views/admin/Trace.vue'), meta: { roles: ['ADMIN', 'FARMER', 'PURCHASER', 'INSPECTOR'], title: '批次溯源', crumb: '批次溯源' } }
    ]
  },
  { path: '/:pathMatch(.*)*', redirect: '/login' }
]

const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior: () => ({ top: 0 })
})

router.beforeEach((to) => {
  const store = useUserStore()
  if (to.meta.public) {
    return true
  }
  if (!store.isLogin) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  if (to.meta.roles && !to.meta.roles.includes(store.role)) {
    return store.home
  }
  if (to.path === '/') {
    return store.home
  }
  return true
})

router.afterEach((to) => {
  document.title = (to.meta.title ? to.meta.title + ' · ' : '') + '农校直供 · 溯源与结算管理平台'
})

export default router
