import request from './request'

const get = (url, params) => request.get(url, { params })
const post = (url, data) => request.post(url, data)
const put = (url, data) => request.put(url, data)
const del = (url, params) => request.delete(url, { params })

/* ---------------- 认证 ---------------- */
export const authApi = {
  login: (data) => post('/auth/login', data),
  me: () => get('/auth/me'),
  logout: () => post('/auth/logout'),
  roles: () => get('/auth/roles'),
  /** 用户自助改密：首次登录被要求改密时也走它 */
  changePassword: (data) => put('/auth/password', data)
}

/* ---------------- 公共 ---------------- */
export const commonApi = {
  health: () => get('/common/health'),
  products: () => get('/common/products'),
  categories: () => get('/common/categories'),
  canteens: () => get('/common/canteens'),
  farmers: () => get('/common/farmers'),
  orderStatus: () => get('/common/order-status'),
  trace: (batchNo) => get('/common/trace', { batchNo }),
  /** 按订单看实时轨迹（四角色共用，数据权限由后端校验） */
  orderTrack: (orderId) => get(`/common/orders/${orderId}/track`),
  /** 单据工作台：通知 / 待办 / KPI 明细行统一定位到具体业务对象 */
  workItem: (params) => get('/common/work-item', params),
  /** 我的业务流程：当前角色的职责、五步主线（带真实待办数）、全链路位置、FAQ */
  myFlow: () => get('/common/my-flow'),
  /** 待办中心：顶栏待办徽标与清单（按角色分组，每条指向真实单据） */
  todoCenter: () => get('/common/todo-center'),
  /** 真实上传：质检现场照片、证照等都走这里拿服务端地址，不再用本地 blob 冒充已上传 */
  upload: (file, type = 'quality') => {
    const fd = new FormData()
    fd.append('file', file)
    return request.post('/files/upload', fd, {
      params: { type },
      headers: { 'Content-Type': 'multipart/form-data' }
    })
  }
}

/* ---------------- 管理员 ---------------- */
export const adminApi = {
  dashboard: (params) => get('/admin/dashboard', params),
  farmers: (params) => get('/admin/farmers', params),
  farmer: (id) => get(`/admin/farmers/${id}`),
  saveFarmer: (data) => (data.id ? put(`/admin/farmers/${data.id}`, data) : post('/admin/farmers', data)),
  farmerStatus: (id, status) => put(`/admin/farmers/${id}/status?status=${encodeURIComponent(status)}`),
  canteens: (params) => get('/admin/canteens', params),
  saveCanteen: (data) => (data.id ? put(`/admin/canteens/${data.id}`, data) : post('/admin/canteens', data)),
  products: (params) => get('/admin/products', params),
  saveProduct: (data) => (data.id ? put(`/admin/products/${data.id}`, data) : post('/admin/products', data)),
  priceHistory: (id) => get(`/admin/products/${id}/price-history`),
  categories: () => get('/admin/categories'),
  users: (params) => get('/admin/users', params),
  roles: () => get('/admin/users/roles'),
  /** 建号时可选业务档案（农户/食堂），只返回未被其他账号占用的 */
  bindable: (roleType, userId) => get('/admin/users/bindable', userId ? { roleType, userId } : { roleType }),
  passwordSuggestion: () => get('/admin/users/password-suggestion'),
  saveUser: (data) => post('/admin/users', data),
  userStatus: (id, status) => put(`/admin/users/${id}/status?status=${status}`),
  resetPassword: (id) => put(`/admin/users/${id}/password`),
  saveRoleMenus: (roleId, data) => post(`/admin/roles/${roleId}/menus`, data),
  orders: (params) => get('/admin/orders', params),
  /** 看板质检合格率下钻：真实质检报告流水 */
  qualityReports: (params) => get('/admin/quality-reports', params),
  orderDetail: (id) => get(`/admin/orders/${id}`),
  urgeOrder: (id) => post(`/admin/orders/${id}/urge`),
  cancelOrder: (id, reason) => post(`/admin/orders/${id}/cancel?reason=${encodeURIComponent(reason || '')}`),
  settlements: (params) => get('/admin/settlements', params),
  settlementDetail: (id) => get(`/admin/settlements/${id}`),
  auditSettlement: (data) => post('/admin/settlements/audit', data),
  warnings: (params) => get('/admin/warnings', params),
  warningDetail: (id) => get(`/admin/warnings/${id}`),
  handleWarning: (id, data) => post(`/admin/warnings/${id}/handle`, data),
  batchHandleWarning: (data) => post('/admin/warnings/batch-handle', data),
  trace: (batchNo) => get('/admin/trace', { batchNo }),
  config: () => get('/admin/config'),
  saveConfig: (data) => put('/admin/config', data),
  resetConfig: () => post('/admin/config/reset'),
  logs: (params) => get('/admin/logs', params),
  auditPolicy: () => post('/admin/logs/audit-policy')
}

/* ---------------- 实时事件（跨角色操作反馈） ---------------- */
export const realtimeApi = {
  ticket: () => post('/common/events/ticket'),
  events: (since, limit = 50) => get('/common/events', { since, limit }),
  recent: (limit = 20) => get('/common/events/recent', { limit })
}

/* ---------------- 定向通知（只返回发给当前登录人的消息） ---------------- */
export const notificationApi = {
  recent: (limit = 50) => get('/notifications/recent', { limit }),
  unreadCount: () => get('/notifications/unread-count'),
  markRead: (id) => post(`/notifications/${id}/read`),
  markAllRead: () => post('/notifications/read-all'),
  /** 删除单条：后端只允许删自己的、且已读的通知 */
  remove: (id) => del(`/notifications/${id}`),
  /** 一键删除全部已读（未读一条都不会动） */
  removeRead: () => del('/notifications/read')
}

/* ---------------- 农户 ---------------- */
export const farmerApi = {
  workbench: () => get('/farmer/workbench'),
  sources: (params) => get('/farmer/sources', params),
  saveSource: (data) => (data.id ? put(`/farmer/sources/${data.id}`, data) : post('/farmer/sources', data)),
  sourceStatus: (id, status) => put(`/farmer/sources/${id}/status?status=${encodeURIComponent(status)}`),
  orders: (params) => get('/farmer/orders', params),
  orderDetail: (id) => get(`/farmer/orders/${id}`),
  accept: (id, version) => post(`/farmer/orders/${id}/accept?version=${version}`),
  reject: (id, reason, version) => post(`/farmer/orders/${id}/reject?reason=${encodeURIComponent(reason || '')}&version=${version}`),
  ship: (id, version) => post(`/farmer/orders/${id}/ship?version=${version}`),
  settlements: (params) => get('/farmer/settlements', params),
  income: () => get('/farmer/income'),
  credit: () => get('/farmer/credit'),
  messages: () => get('/farmer/messages'),
  demandHall: (params) => get('/farmer/demand-hall', params),
  myQuotes: () => get('/farmer/quotes'),
  submitQuote: (data) => post('/farmer/quotes', data),
  trace: (batchNo) => get('/farmer/trace', { batchNo })
}

/* ---------------- 采购员 ---------------- */
export const purchaserApi = {
  demands: (params) => get('/purchaser/demands', params),
  demand: (id) => get(`/purchaser/demands/${id}`),
  saveDemand: (data) => (data.id ? put(`/purchaser/demands/${data.id}`, data) : post('/purchaser/demands', data)),
  withdrawDemand: (id) => put(`/purchaser/demands/${id}/withdraw`),
  sources: (params) => get('/purchaser/sources', params),
  match: (demandId) => get(`/purchaser/match/${demandId}`),
  orders: (params) => get('/purchaser/orders', params),
  orderDetail: (id) => get(`/purchaser/orders/${id}`),
  createOrder: (data) => post('/purchaser/orders', data),
  purchaseOrders: (params) => get('/purchaser/purchase-orders', params),
  purchaseOrder: (id) => get(`/purchaser/purchase-orders/${id}`),
  createPurchaseOrder: (data) => post('/purchaser/purchase-orders', data),
  demandQuotes: (id) => get(`/purchaser/demands/${id}/quotes`),
  awardQuote: (demandId, quoteId) => post(`/purchaser/demands/${demandId}/award/${quoteId}`),
  stock: () => get('/purchaser/stock'),
  stockOut: (data) => post('/purchaser/stock/out', data),
  stockSuggestions: () => get('/purchaser/stock/suggestions'),
  stockCreateDemands: (productIds) => post('/purchaser/stock/demands', { productIds }),
  stockSetSafety: (productId, safetyQty) => put(`/purchaser/stock/safety?productId=${productId}&safetyQty=${safetyQty}`),
  inboundPreview: (orderNo) => get('/purchaser/inbound', { orderNo }),
  inbound: (data) => post('/purchaser/inbound', data),
  /** 待入库清单：本食堂全部已送达订单，入库页左侧逐笔处理 */
  inboundPending: (limit = 50) => get('/purchaser/inbound/pending', { limit }),
  inboundRecent: (limit = 5) => get('/purchaser/inbound/recent', { limit }),
  evaluations: (params) => get('/purchaser/evaluations', params),
  submitEvaluation: (data) => post('/purchaser/evaluations', data),
  settlements: (params) => get('/purchaser/settlements', params),
  settlementDetail: (id) => get(`/purchaser/settlements/${id}`),
  trace: (batchNo) => get('/purchaser/trace', { batchNo })
}

/* ---------------- 质检配送员 ---------------- */
export const inspectorApi = {
  tasks: (params) => get('/inspector/tasks', params),
  /** 质检报告逐批明细：today=1 今日已检，unqualified=1 全部不合格批次 */
  qualityReports: (params) => get('/inspector/quality-reports', params),
  qualityDetail: (orderId) => get(`/inspector/quality/${orderId}`),
  submitQuality: (data) => post('/inspector/quality', data),
  deliveries: () => get('/inspector/deliveries'),
  deliveryDetail: (id) => get(`/inspector/deliveries/${id}`),
  /** 发车：传 { vehicle, driver, temperature }，起始温度必须 2~8℃ */
  startDelivery: (id, data) => post(`/inspector/deliveries/${id}/start`, data || {}),
  /** 交付：传 { batchCode }，必须是批次号后 6 位 */
  arrive: (id, data) => post(`/inspector/deliveries/${id}/arrive`, data || {}),
  /** 实时轨迹：位置 / 进度 / ETA / 温度曲线 */
  trackDelivery: (id) => get(`/inspector/deliveries/${id}/track`),
  updateTemperature: (id, value) => put(`/inspector/deliveries/${id}/temperature?value=${value}`),
  exceptions: () => get('/inspector/exceptions'),
  reportException: (data) => post('/inspector/exceptions', data),
  trace: (batchNo) => get('/inspector/trace', { batchNo })
}

export const uploadUrl = '/api/files/upload'
