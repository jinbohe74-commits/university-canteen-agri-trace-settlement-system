import axios from 'axios'
import { ElMessage } from 'element-plus'
import router from '@/router'
import { useUserStore } from '@/store/user'
import { readSession } from '@/utils/session'

const request = axios.create({
  // 修复 P2：支持通过 .env 的 VITE_API_BASE_URL 覆盖，避免地址硬编码
  baseURL: import.meta.env.VITE_API_BASE_URL || '/api',
  timeout: 20000
})

const CODE_MESSAGE = {
  401: '登录已失效，请重新登录',
  403: '没有权限访问该资源',
  409: '数据已存在或状态冲突，请刷新后重试',
  423: '账号已临时锁定，请稍后再试',
  428: '当前为初始密码，请先修改密码'
}

// 并发请求同时 401 时只提示一次，避免连弹多条
let lastAuthErrorAt = 0
const notifyAuthError = (msg) => {
  if (Date.now() - lastAuthErrorAt < 800) return
  lastAuthErrorAt = Date.now()
  ElMessage.error(msg)
}

const handleUnauthorized = (message) => {
  const store = useUserStore()
  const msg = message || CODE_MESSAGE[401]
  // 本来就没有登录态（登录页输错账号、角色不符等）：只提示，不能清状态也不能跳路由
  if (!store.isLogin) {
    notifyAuthError(msg)
    return
  }
  store.clear()
  notifyAuthError(msg)
  const cur = router.currentRoute.value
  if (cur.path !== '/login') {
    router.replace({ path: '/login', query: cur.fullPath && cur.fullPath !== '/' ? { redirect: cur.fullPath } : {} })
  }
}

const handleBusinessCode = (body) => {
  if (!body || typeof body.code === 'undefined' || body.code === 200) {
    return body
  }
  if (body.code === 401) {
    handleUnauthorized(body.message)
    throw new Error(body.message || CODE_MESSAGE[401])
  }
  const message = body.message || CODE_MESSAGE[body.code] || '请求失败'
  ElMessage.error(message)
  throw new Error(message)
}

/* 428：后端要求先改初始密码。这里只负责通知布局层弹强制改密窗，
   不在这里跳路由，避免用户正在编辑的内容被清空。 */
const notifyNeedChangePwd = (message) => {
  window.dispatchEvent(
    new CustomEvent('canteen:need-change-pwd', { detail: { message: message || CODE_MESSAGE[428] } })
  )
}

request.interceptors.request.use((config) => {
  // 只读当前标签页的会话，绝不去 localStorage 取别人的 token
  const token = readSession('ct_token')
  if (token) {
    config.headers.Authorization = 'Bearer ' + token
  }
  return config
})

request.interceptors.response.use(
  (res) => handleBusinessCode(res.data),
  (error) => {
    const status = error?.response?.status
    const body = error?.response?.data
    if (status === 401) {
      handleUnauthorized(body?.message)
      return Promise.reject(error)
    }
    if (status === 403) {
      // 带上当前身份，万一出现越权，一眼能看出"用的是哪个角色的身份"
      const store = useUserStore()
      const who = store.roleName ? `（当前身份：${store.roleName}）` : ''
      ElMessage.error((body?.message || CODE_MESSAGE[403]) + who)
      return Promise.reject(error)
    }
    if (status === 409 || status === 423) {
      ElMessage.error(body?.message || CODE_MESSAGE[status])
      return Promise.reject(error)
    }
    if (status === 428) {
      notifyNeedChangePwd(body?.message)
      return Promise.reject(error)
    }
    const msg = body?.message || error.message || '网络异常，请稍后重试'
    ElMessage.error(msg)
    return Promise.reject(error)
  }
)

export default request
