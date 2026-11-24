/* ==========================================================================
   会话存储：按浏览器标签页隔离
   --------------------------------------------------------------------------
   为什么不用 localStorage：
   localStorage 在同一个域下是**全标签页共享**的。用户同时登录多个角色
   （管理员开一个标签、农户开另一个标签）时，后登录的 token 会覆盖先登录的，
   于是先登录的标签页拿着别人的 token 去请求自己的接口，后端按 token 里的
   角色判权，直接返回 403「无权访问该资源」；更糟的是刷新时路由守卫会用这个
   串过来的身份复核，发现当前路由不在该角色菜单里，就把用户重定向到**另一个
   角色的首页**——表现就是"提示无权访问，然后所有数据都没了"。

   sessionStorage 每个标签页独立、同标签页刷新仍在，正好对应"一标签一身份"。
   本模块是全站唯一的会话读写入口，任何地方不要再直接碰 localStorage 的会话键。
   ========================================================================== */

export const SESSION_KEYS = ['ct_token', 'ct_user', 'ct_role_name', 'ct_menus', 'ct_perms', 'ct_need_pwd']

export function readSession(key) {
  try {
    return sessionStorage.getItem(key) || ''
  } catch (e) {
    return ''
  }
}

export function writeSession(key, value) {
  try {
    sessionStorage.setItem(key, value == null ? '' : String(value))
  } catch (e) {
    /* 隐私模式下 sessionStorage 可能不可写，静默降级为内存态 */
  }
}

export function removeSession(key) {
  try {
    sessionStorage.removeItem(key)
  } catch (e) {
    /* ignore */
  }
}

export function readJson(key, fallback = null) {
  const raw = readSession(key)
  if (!raw) return fallback
  try {
    return JSON.parse(raw)
  } catch (e) {
    // 缓存损坏时不能抛异常（会让整个应用白屏），按"没有缓存"处理
    removeSession(key)
    return fallback
  }
}

export function writeJson(key, value) {
  writeSession(key, JSON.stringify(value))
}

export function clearSession() {
  SESSION_KEYS.forEach(removeSession)
}

/**
 * 从旧的 localStorage 会话迁移到当前标签页，并清掉污染源。
 *
 * 只做一次：本模块被 store / axios 同时 import，但 ESM 是单例，只会执行一次。
 * 迁移后 localStorage 里的会话键必须删除——留着它，其他标签页下次仍可能读到
 * 不属于自己的身份。
 */
export function migrateLegacySession() {
  const hasSession = !!sessionStorage.getItem('ct_token')
  const hasLegacy = !!localStorage.getItem('ct_token')
  if (!hasSession && hasLegacy) {
    SESSION_KEYS.forEach((k) => {
      const v = localStorage.getItem(k)
      if (v !== null) sessionStorage.setItem(k, v)
    })
  }
  if (hasLegacy) {
    SESSION_KEYS.forEach((k) => localStorage.removeItem(k))
  }
}

migrateLegacySession()
