import request from './request'

const get = (url, params) => request.get(url, { params })
const post = (url, data) => request.post(url, data)
const put = (url, data) => request.put(url, data)

/* ---------------- 认证 ---------------- */
export const authApi = {
  login: (data) => post('/auth/login', data),
  me: () => get('/auth/me'),
  logout: () => post('/auth/logout'),
  roles: () => get('/auth/roles')
}

/* ---------------- 公共 ---------------- */
export const commonApi = {
  health: () => get('/common/health'),
  products: () => get('/common/products'),
  categories: () => get('/common/categories'),
  canteens: () => get('/common/canteens'),
  farmers: () => get('/common/farmers'),
  orderStatus: () => get('/common/order-status'),
  trace: (batchNo) => get('/common/trace', { batchNo })
}

/* ---------------- 管理员 ---------------- */
export const adminApi = {
  dashboard: () => get('/admin/dashboard'),
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
  saveUser: (data) => post('/admin/users', data),
  userStatus: (id, status) => put(`/admin/users/${id}/status?status=${status}`),
  resetPassword: (id) => put(`/admin/users/${id}/password`),
  saveRoleMenus: (roleId, data) => post(`/admin/roles/${roleId}/menus`, data),
  orders: (params) => get('/admin/orders', params),
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
  logs: (params) => get('/admin/logs', params)
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
  inboundPreview: (orderNo) => get('/purchaser/inbound', { orderNo }),
  inbound: (data) => post('/purchaser/inbound', data),
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
  qualityDetail: (orderId) => get(`/inspector/quality/${orderId}`),
  submitQuality: (data) => post('/inspector/quality', data),
  deliveries: () => get('/inspector/deliveries'),
  deliveryDetail: (id) => get(`/inspector/deliveries/${id}`),
  startDelivery: (id) => post(`/inspector/deliveries/${id}/start`),
  arrive: (id) => post(`/inspector/deliveries/${id}/arrive`),
  updateTemperature: (id, value) => put(`/inspector/deliveries/${id}/temperature?value=${value}`),
  exceptions: () => get('/inspector/exceptions'),
  reportException: (data) => post('/inspector/exceptions', data),
  trace: (batchNo) => get('/inspector/trace', { batchNo })
}

export const uploadUrl = '/api/files/upload'
