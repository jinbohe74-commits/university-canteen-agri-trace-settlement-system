import { defineStore } from 'pinia'
import { authApi } from '@/api'

const HOME = {
  ADMIN: '/admin/dashboard',
  FARMER: '/farmer/workbench',
  PURCHASER: '/purchaser/demands',
  INSPECTOR: '/inspector/tasks'
}

export const useUserStore = defineStore('user', {
  state: () => ({
    token: localStorage.getItem('ct_token') || '',
    user: JSON.parse(localStorage.getItem('ct_user') || 'null'),
    roleName: localStorage.getItem('ct_role_name') || '',
    menus: JSON.parse(localStorage.getItem('ct_menus') || '[]'),
    perms: JSON.parse(localStorage.getItem('ct_perms') || '[]')
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
      const data = res.data
      this.token = data.token
      this.user = data.user
      this.roleName = data.roleName
      this.menus = data.menus
      this.perms = data.perms
      localStorage.setItem('ct_token', data.token)
      localStorage.setItem('ct_user', JSON.stringify(data.user))
      localStorage.setItem('ct_role_name', data.roleName)
      localStorage.setItem('ct_menus', JSON.stringify(data.menus))
      localStorage.setItem('ct_perms', JSON.stringify(data.perms))
      return data
    },
    async refresh() {
      const res = await authApi.me()
      this.user = res.data.user
      this.roleName = res.data.roleName
      this.menus = res.data.menus
      this.perms = res.data.perms
      localStorage.setItem('ct_user', JSON.stringify(this.user))
      localStorage.setItem('ct_menus', JSON.stringify(this.menus))
      localStorage.setItem('ct_role_name', this.roleName)
      localStorage.setItem('ct_perms', JSON.stringify(this.perms))
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
      localStorage.removeItem('ct_token')
      localStorage.removeItem('ct_user')
      localStorage.removeItem('ct_menus')
      localStorage.removeItem('ct_perms')
      localStorage.removeItem('ct_role_name')
    }
  }
})
