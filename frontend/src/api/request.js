import axios from 'axios'
import { ElMessage } from 'element-plus'
import router from '@/router'
import { useUserStore } from '@/store/user'

const request = axios.create({
  baseURL: '/api',
  timeout: 20000
})

request.interceptors.request.use((config) => {
  const token = localStorage.getItem('ct_token')
  if (token) {
    config.headers.Authorization = 'Bearer ' + token
  }
  return config
})

request.interceptors.response.use(
  (res) => {
    const body = res.data
    if (body && typeof body.code !== 'undefined') {
      if (body.code === 200) {
        return body
      }
      if (body.code === 401) {
        const store = useUserStore()
        store.clear()
        ElMessage.error(body.message || '登录已失效，请重新登录')
        router.replace('/login')
        return Promise.reject(new Error(body.message))
      }
      ElMessage.error(body.message || '请求失败')
      return Promise.reject(new Error(body.message || '请求失败'))
    }
    return body
  },
  (error) => {
    const status = error?.response?.status
    if (status === 401) {
      const store = useUserStore()
      store.clear()
      router.replace('/login')
    }
    const msg = error?.response?.data?.message || error.message || '网络异常，请稍后重试'
    ElMessage.error(msg)
    return Promise.reject(error)
  }
)

export default request
