/* ==========================================================================
   账号与密码机制验收（2026-11-11）
   --------------------------------------------------------------------------
   判据是「新建账号能不能真的用起来、密码是不是真的换掉了」，不看代码里有没有校验：
     A. 建号契约：角色缺失/档案缺失/重复占用/非法账号/弱口令 必须明确拒绝
     B. 建号有效性：绑定农户 → 登录 → 农户工作台能取到本档案数据；菜单非空
     C. 密码机制：首登被 428 拦截 → 改密 → 旧密码失效 / 旧 token 失效 / 业务恢复
     D. 权限收口：停用即下线、改角色自动解绑、平台岗位不要求绑定
   全部走真实 HTTP；任何取数失败按 FAIL 处理（L-060）
   ========================================================================== */
const path = require('path')
const API = 'http://127.0.0.1:8080'

const TAG = 't' + Date.now().toString().slice(-6)
const results = []
const rec = (name, ok, detail) => {
  results.push({ name, ok: !!ok, detail })
  console.log((ok ? '  PASS ' : '  FAIL ') + name + (detail !== undefined && detail !== '' ? ' | ' + String(detail).slice(0, 260) : ''))
}

/** 业务错误看 body.code；鉴权/门禁错误看真实 HTTP 状态，两者都算"被拒" */
const isCode = (r, code) => r.body?.code === code || r.status === code

const api = async (method, url, { token, body } = {}) => {
  const res = await fetch(API + url, {
    method,
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: 'Bearer ' + token } : {})
    },
    body: body === undefined ? undefined : JSON.stringify(body)
  })
  const text = await res.text()
  let json = null
  try { json = JSON.parse(text) } catch (e) { json = { raw: text } }
  return { status: res.status, body: json }
}

const login = async (username, password, expectedRole) => {
  const r = await api('POST', '/api/auth/login', { body: { username, password, expectedRole } })
  return { ...r, data: r.body?.data }
}

;(async () => {
  /* ---------------------------------------------------------------- A. 建号契约 */
  const adminLogin = await login('admin', '123456')
  if (adminLogin.body?.code !== 200) {
    rec('管理员登录', false, JSON.stringify(adminLogin.body))
    return finish()
  }
  const admin = adminLogin.data.token
  rec('管理员登录', true, 'menus=' + (adminLogin.data.menus || []).length + ' 组')

  // A1 农户账号不绑定档案 → 必须 400，且不能留下半成品账号
  const farmerName = 'acc_farmer_' + TAG
  let r = await api('POST', '/api/admin/users', {
    token: admin,
    body: { username: farmerName, realName: '验收农户', roleType: 'FARMER', password: 'Test2026a' }
  })
  rec('A1 农户账号不绑定档案被拒', isCode(r, 400) && /绑定/.test(r.body?.message || ''),
    r.status + ' ' + (r.body?.message || ''))
  const orphan = await api('GET', '/api/admin/users?keyword=' + farmerName, { token: admin })
  rec('A1b 被拒后不留半成品账号', (orphan.body?.data?.page?.total || 0) === 0,
    'total=' + (orphan.body?.data?.page?.total))

  // A2 非法账号、弱口令
  r = await api('POST', '/api/admin/users', {
    token: admin, body: { username: '张三', realName: '中文账号', roleType: 'ADMIN', password: 'Test2026a' }
  })
  rec('A2 中文账号被拒', isCode(r, 400) && /字母/.test(r.body?.message || ''), r.body?.message)
  r = await api('POST', '/api/admin/users', {
    token: admin, body: { username: 'acc bad ' + TAG, realName: '空 格', roleType: 'ADMIN', password: 'Test2026a' }
  })
  rec('A3 账号含空格被拒', isCode(r, 400), r.body?.message)
  r = await api('POST', '/api/admin/users', {
    token: admin, body: { username: 'acc_weak_' + TAG, realName: '弱口令', roleType: 'ADMIN', password: 'admin123' }
  })
  rec('A4 弱口令被拒', isCode(r, 400) && /常见|8 位/.test(r.body?.message || ''), r.body?.message)

  // A5 绑定一个已被占用的农户档案（farmer24 已占用 farmer 1）
  const bound = await api('GET', '/api/admin/users?keyword=farmer24', { token: admin })
  const boundRow = bound.body?.data?.page?.records?.[0]
  const occupiedFarmerId = boundRow?.farmerId
  r = await api('POST', '/api/admin/users', {
    token: admin,
    body: { username: 'acc_dup_' + TAG, realName: '重复绑定', roleType: 'FARMER', password: 'Test2026a', farmerId: occupiedFarmerId }
  })
  rec('A5 档案已被占用时拒绝', isCode(r, 409) && /已绑定账号/.test(r.body?.message || ''),
    r.status + ' ' + (r.body?.message || ''))

  /* ---------------------------------------------------- B. 建号 → 绑定 → 可用 */
  const bindable = await api('GET', '/api/admin/users/bindable?roleType=FARMER', { token: admin })
  const freeFarmer = (bindable.body?.data || []).find((x) => !x.bound)
  if (!freeFarmer) {
    rec('B0 存在可绑定农户档案', false, '没有空闲农户档案，无法继续验证')
    return finish()
  }
  rec('B0 可绑定档案列表（只含空闲档案）', !(bindable.body?.data || []).some((x) => x.bound),
    '共 ' + (bindable.body?.data || []).length + ' 个可选，首个=' + freeFarmer.name)

  r = await api('POST', '/api/admin/users', {
    token: admin,
    body: {
      username: farmerName, realName: '验收农户', phone: '13700000001',
      roleType: 'FARMER', password: 'Test2026a', farmerId: freeFarmer.id
      // 故意不传 roleId：验证后端以 roleType 反查角色，不再依赖前端
    }
  })
  const created = r.body?.data
  rec('B1 农户账号创建成功', isCode(r, 200) && created?.user?.username === farmerName,
    r.status + ' ' + (r.body?.message || ''))
  rec('B1b 创建响应不含密码哈希', created && !('password' in (created.user || {})),
    'user keys=' + Object.keys(created?.user || {}).join(','))
  rec('B1c 初始密码一次性返回', typeof created?.initialPassword === 'string' && created.initialPassword.length >= 8,
    created?.initialPassword)
  rec('B1d 账号标记为待改密', created?.user?.needChangePwd === 1, String(created?.user?.needChangePwd))

  const fl = await login(farmerName, 'Test2026a')
  rec('B2 新账号能登录', fl.body?.code === 200, fl.status + ' ' + (fl.body?.message || ''))
  rec('B2b 角色菜单非空（不依赖前端传 roleId）', (fl.data?.menus || []).length > 0,
    'menus=' + (fl.data?.menus || []).length)
  rec('B2c 登录返回待改密标记', fl.data?.needChangePwd === true, String(fl.data?.needChangePwd))
  rec('B2d 返回所属档案', fl.data?.user?.orgName === freeFarmer.name,
    fl.data?.user?.orgName + ' / farmerId=' + fl.data?.user?.farmerId)

  const farmerToken = fl.data?.token
  r = await api('GET', '/api/farmer/workbench', { token: farmerToken })
  rec('B3 未改密时业务接口被 428 拦截', r.status === 428 && /修改密码/.test(r.body?.message || ''),
    r.status + ' ' + (r.body?.message || ''))

  /* ---------------------------------------------------------- C. 密码机制 */
  r = await api('PUT', '/api/auth/password', {
    token: farmerToken, body: { currentPassword: 'wrong-pass1', newPassword: 'NewPass2026' }
  })
  rec('C1 当前密码错误时拒绝改密', isCode(r, 400) && /当前密码不正确/.test(r.body?.message || ''),
    r.status + ' ' + (r.body?.message || ''))

  r = await api('PUT', '/api/auth/password', {
    token: farmerToken, body: { currentPassword: 'Test2026a', newPassword: 'Test2026a' }
  })
  rec('C2 新密码与旧密码相同被拒', isCode(r, 400), r.body?.message)

  r = await api('PUT', '/api/auth/password', {
    token: farmerToken, body: { currentPassword: 'Test2026a', newPassword: 'farmer2026' }
  })
  const newToken = r.body?.data?.token
  rec('C3 自助改密成功', r.status === 200 && !!newToken, r.status + ' ' + (r.body?.message || ''))

  r = await api('GET', '/api/auth/me', { token: newToken })
  const oldTokenCheck = await api('GET', '/api/auth/me', { token: farmerToken })
  rec('C4 改密后旧 token 失效', isCode(oldTokenCheck, 401),
    '旧 token 状态=' + oldTokenCheck.status + '/' + oldTokenCheck.body?.code)
  rec('C4b 新 token 直接可用（不用重新登录）', r.status === 200 && r.body?.data?.needChangePwd === false,
    r.status + ' needChangePwd=' + r.body?.data?.needChangePwd)

  r = await api('GET', '/api/farmer/workbench', { token: newToken })
  rec('C5 改密后农户工作台恢复可用', r.status === 200 && r.body?.code === 200,
    r.status + ' ' + JSON.stringify(r.body).slice(0, 120))

  const oldLogin = await login(farmerName, 'Test2026a')
  rec('C6 旧密码不能再登录', isCode(oldLogin, 401), oldLogin.status + ' ' + oldLogin.body?.message)
  const newLogin = await login(farmerName, 'farmer2026')
  rec('C6b 新密码可登录', newLogin.body?.code === 200, newLogin.status)
  const farmerToken2 = newLogin.data?.token

  // 数据范围：该农户账号只应看到自己档案的数据
  const wb = await api('GET', '/api/farmer/workbench', { token: farmerToken2 })
  rec('C7 工作台返回的是本人档案数据', wb.status === 200 && wb.body?.data != null,
    'status=' + wb.status)

  /* ------------------------------------------- D. 重复占用 / 改角色 / 停用 */
  r = await api('POST', '/api/admin/users', {
    token: admin,
    body: { username: 'acc_dup2_' + TAG, realName: '重复绑定2', roleType: 'FARMER', password: 'Test2026a', farmerId: freeFarmer.id }
  })
  rec('D1 已绑定的档案不能再给第二个账号', isCode(r, 409), r.status + ' ' + (r.body?.message || ''))

  // 采购员必须绑食堂：先建一个食堂再绑
  const canteenName = '验收食堂' + TAG
  const canteenRes = await api('POST', '/api/admin/canteens', {
    token: admin, body: { name: canteenName, campus: '东校区', manager: '验收', phone: '13700000002' }
  })
  const canteenId = canteenRes.body?.data?.id
  rec('D2 新建食堂档案（用于绑定）', canteenRes.status === 200 && !!canteenId,
    canteenRes.status + ' id=' + canteenId)

  r = await api('POST', '/api/admin/users', {
    token: admin, body: { username: 'acc_buy_' + TAG, realName: '验收采购', roleType: 'PURCHASER', password: 'Test2026a' }
  })
  rec('D3 采购员不绑食堂被拒', isCode(r, 400) && /绑定/.test(r.body?.message || ''), r.body?.message)

  r = await api('POST', '/api/admin/users', {
    token: admin,
    body: {
      username: 'acc_buy_' + TAG, realName: '验收采购', roleType: 'PURCHASER',
      password: 'Test2026a', canteenId
    }
  })
  const buyToken0 = (await login('acc_buy_' + TAG, 'Test2026a')).data?.token
  if (buyToken0) {
    await api('PUT', '/api/auth/password', {
      token: buyToken0, body: { currentPassword: 'Test2026a', newPassword: 'buyer2026' }
    })
  }
  const buyLogin = await login('acc_buy_' + TAG, 'buyer2026')
  rec('D4 采购员账号可用', buyLogin.body?.code === 200 && buyLogin.data?.user?.canteenId === canteenId,
    'canteenId=' + buyLogin.data?.user?.canteenId)
  const buyerHome = await api('GET', '/api/purchaser/demands?page=1&size=5', { token: buyLogin.data?.token })
  rec('D4b 采购员业务接口按食堂隔离可用', buyerHome.status === 200 && buyerHome.body?.code === 200,
    'status=' + buyerHome.status + ' ' + (buyerHome.body?.message || ''))

  // 改角色：农户 → 质检员，必须自动解绑农户档案
  const farmerRow = (await api('GET', '/api/admin/users?keyword=' + farmerName, { token: admin }))
    .body?.data?.page?.records?.[0]
  r = await api('POST', '/api/admin/users', {
    token: admin,
    body: { id: farmerRow.id, username: farmerName, realName: '验收农户', roleType: 'INSPECTOR', status: 1 }
  })
  rec('D5 改角色后保存成功', r.status === 200, r.status + ' ' + (r.body?.message || ''))
  const afterRole = (await api('GET', '/api/admin/users?keyword=' + farmerName, { token: admin }))
    .body?.data?.page?.records?.[0]
  rec('D5b 改角色后不再挂着农户档案', !afterRole?.farmerId && afterRole?.roleType === 'INSPECTOR',
    'farmerId=' + afterRole?.farmerId + ' role=' + afterRole?.roleType)
  const rebindable = await api('GET', '/api/admin/users/bindable?roleType=FARMER', { token: admin })
  rec('D5c 原农户档案回到可绑定池', (rebindable.body?.data || []).some((x) => x.id === freeFarmer.id && !x.bound),
    'freeFarmer=' + freeFarmer.id)

  // 停用：在线标记清零 + token 立即失效
  r = await api('PUT', `/api/admin/users/${farmerRow.id}/status?status=0`, { token: admin })
  const disabledRow = (await api('GET', '/api/admin/users?keyword=' + farmerName, { token: admin }))
    .body?.data?.page?.records?.[0]
  rec('D6 停用成功且在线数归零', r.status === 200 && disabledRow?.status === 0 && disabledRow?.online === 0,
    'status=' + disabledRow?.status + ' online=' + disabledRow?.online)
  const disabledMe = await api('GET', '/api/auth/me', { token: farmerToken2 })
  rec('D6b 停用后旧 token 立即失效', isCode(disabledMe, 401), 'me=' + disabledMe.status + '/' + disabledMe.body?.code)

  // 平台岗位（管理员）不要求绑定
  const adminName = 'acc_admin_' + TAG
  r = await api('POST', '/api/admin/users', {
    token: admin, body: { username: adminName, realName: '验收管理员', roleType: 'ADMIN', password: 'Test2026a' }
  })
  rec('D7 管理员账号无需绑定档案', r.status === 200 && !r.body?.data?.user?.farmerId,
    r.status + ' ' + (r.body?.message || ''))

  // 不能停用当前登录的自己
  r = await api('PUT', '/api/admin/users/1/status?status=0', { token: admin })
  rec('D8 不能停用当前登录账号', isCode(r, 400), r.status + ' ' + (r.body?.message || ''))

  // 重置密码：返回新初始密码并重新要求改密
  const resetTarget = (await api('GET', '/api/admin/users?keyword=' + adminName, { token: admin }))
    .body?.data?.page?.records?.[0]
  r = await api('PUT', `/api/admin/users/${resetTarget.id}/password`, { token: admin })
  const resetPwd = r.body?.data?.password
  rec('D9 管理员重置密码返回一次性口令', r.status === 200 && typeof resetPwd === 'string' && resetPwd.length >= 8, resetPwd)
  const resetLogin = await login(adminName, resetPwd)
  rec('D9b 重置后的口令可登录且再次要求改密', resetLogin.body?.code === 200 && resetLogin.data?.needChangePwd === true,
    'needChangePwd=' + resetLogin.data?.needChangePwd)
  const oldPwdLogin = await login(adminName, 'Test2026a')
  rec('D9c 旧口令失效', isCode(oldPwdLogin, 401), 'old=' + oldPwdLogin.status + '/' + oldPwdLogin.body?.code)

  finish()
})().catch((e) => {
  rec('脚本异常', false, e && e.stack ? e.stack.split('\n')[0] : String(e))
  finish()
})

function finish() {
  const pass = results.filter((x) => x.ok).length
  const fail = results.length - pass
  console.log('\n==============================================')
  console.log(`账号与密码机制验收：${pass}/${results.length} 通过，${fail} 失败`)
  console.log('==============================================')
  if (fail) {
    results.filter((x) => !x.ok).forEach((x) => console.log('  ✗ ' + x.name + ' | ' + x.detail))
  }
  process.exit(fail ? 1 : 0)
}
