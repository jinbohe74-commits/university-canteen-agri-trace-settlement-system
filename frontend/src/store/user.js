import { defineStore } from 'pinia'
import { authApi } from '@/api'
import {
  readSession,
  writeSession,
  readJson,
  writeJson,
  clearSession
} from '@/utils/session'

const HOME = {
  ADMIN: '/admin/dashboard',
  FARMER: '/farmer/workbench',
  PURCHASER: '/purchaser/demands',
  INSPECTOR: '/inspector/tasks'
}

export const useUserStore = defineStore('user', {
  state: () => ({
    // 会话存在 sessionStorage：同一浏览器的多个标签页可以各登录一个角色互不干扰
    token: readSession('ct_token'),
    user: readJson('ct_user'),
    roleName: readSession('ct_role_name'),
    menus: readJson('ct_menus', []),
    perms: readJson('ct_perms', []),
    // 新建账号 / 被管理员重置密码后为 true：没改密前业务接口一律 428
    needChangePwd: readSession('ct_need_pwd') === '1',
    // 修复 P1-13：刷新后必须向后端复核身份，本地缓存里的 role 只作展示用
    verified: false
  }),
  getters: {
    role: (s) => s.user?.role || '',
    isLogin: (s) => !!s.token,
    displayName: (s) => s.user?.realName || '',
    orgName: (s) => s.user?.orgName || '',
    home: (s) => HOME[s.user?.role] || '/login'
  },
  actions: {
    async login(form) {
      const res = await authApi.login(form)
      this.applyLoginData(res.data)
      return res.data
    },
    applyLoginData(data) {
      this.token = data.token
      this.user = data.user
      this.roleName = data.roleName
      this.menus = data.menus
      this.perms = data.perms
      this.needChangePwd = !!data.needChangePwd
      this.verified = true
      writeSession('ct_token', data.token)
      writeJson('ct_user', data.user)
      writeSession('ct_role_name', data.roleName)
      writeJson('ct_menus', data.menus)
      writeJson('ct_perms', data.perms)
      writeSession('ct_need_pwd', this.needChangePwd ? '1' : '0')
    },
    /** 以服务端 /auth/me 的结果为准覆盖本地缓存的角色、菜单与权限 */
    async refresh() {
      const res = await authApi.me()
      this.user = res.data.user
      this.roleName = res.data.roleName
      this.menus = res.data.menus
      this.perms = res.data.perms
      this.needChangePwd = !!res.data.needChangePwd
      this.verified = true
      writeJson('ct_user', this.user)
      writeJson('ct_menus', this.menus)
      writeSession('ct_role_name', this.roleName)
      writeJson('ct_perms', this.perms)
      writeSession('ct_need_pwd', this.needChangePwd ? '1' : '0')
      return this.user
    },
    /**
     * 自助改密。成功后后端会重新签发 token（其余会话被踢下线），
     * 这里同步换掉本地凭证，用户不必重新登录。
     */
    async changePassword(payload) {
      const res = await authApi.changePassword(payload)
      const data = res.data || {}
      if (data.token) {
        this.token = data.token
        writeSession('ct_token', data.token)
      }
      if (data.user) {
        this.user = data.user
        writeJson('ct_user', data.user)
      }
      this.needChangePwd = false
      writeSession('ct_need_pwd', '0')
      return data
    },
    async logout() {
      try {
        await authApi.logout()
      } catch (e) {
        /* ignore */
      }
      this.clear()
    },
    clear() {
      this.token = ''
      this.user = null
      this.menus = []
      this.perms = []
      this.roleName = ''
      this.needChangePwd = false
      this.verified = false
      clearSession()
    }
  }
})
