<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2 class="page-title">用户与权限</h2>
        <p class="page-sub">建号即绑定业务档案：农户账号绑农户、采购员账号绑食堂，账号才有数据可见</p>
      </div>
      <div class="page-actions">
        <el-button plain @click="roleVisible = true"><el-icon><Management /></el-icon>角色管理</el-button>
        <el-button type="primary" @click="openCreate"><el-icon><Plus /></el-icon>新增用户</el-button>
      </div>
    </div>

    <div class="user-grid">
      <!-- 左栏：用户列表 -->
      <div class="card table-card">
        <div class="card-head">
          <div class="card-title">用户列表</div>
          <div class="card-extra">
            共 {{ fmtNum(stats.total) }} 个账号 · 在线 {{ fmtNum(stats.online) }}
            <span v-if="stats.pendingPwd" class="pwd-pending">· {{ fmtNum(stats.pendingPwd) }} 个待改初始密码</span>
          </div>
        </div>

        <div class="filter-bar">
          <el-input
            v-model="query.keyword" placeholder="搜索账号 / 姓名" clearable
            style="width: 186px" @keyup.enter="search" @clear="search"
          >
            <template #prefix><el-icon><Search /></el-icon></template>
          </el-input>
          <el-select v-model="query.role" placeholder="角色" clearable style="width: 150px" @change="search">
            <el-option label="全部角色" value="" />
            <el-option v-for="r in ROLE_OPTIONS" :key="r.value" :label="r.label" :value="r.value" />
          </el-select>
          <div class="filter-spacer"></div>
          <el-button plain @click="reset"><el-icon><Refresh /></el-icon>重置</el-button>
          <el-button type="primary" @click="search"><el-icon><Search /></el-icon>查询</el-button>
        </div>

        <div class="table-wrap">
          <el-table v-loading="loading" :data="rows" style="width: 100%" empty-text="暂无符合条件的用户">
            <el-table-column label="用户" min-width="176">
              <template #default="{ row }">
                <div class="user-cell">
                  <div class="u-avatar">{{ avatarText(row) }}</div>
                  <div class="grow">
                    <div class="cell-main">{{ row.realName || '—' }}</div>
                    <div class="cell-sub mono">{{ row.username }}</div>
                  </div>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="角色" width="104">
              <template #default="{ row }">
                <span class="tag" :class="roleTag(row.roleType)">{{ row.roleName }}</span>
              </template>
            </el-table-column>
            <el-table-column label="所属单位" min-width="168">
              <template #default="{ row }">
                <template v-if="row.orgNo">
                  <div class="cell-main-2">{{ row.orgName }}</div>
                  <div class="cell-sub mono">{{ row.orgNo }}</div>
                </template>
                <span v-else-if="row.orgName === '平台'" class="small muted">平台账号 · 不绑定档案</span>
                <span v-else class="tag tag-red">未绑定档案</span>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="128">
              <template #default="{ row }">
                <div class="state-cell">
                  <span class="tag tag-dot" :class="row.status === 1 ? 'tag-green' : 'tag-gray'">
                    {{ row.status === 1 ? '已启用' : '已停用' }}
                  </span>
                  <span v-if="row.locked" class="tag tag-red mini-tag">已锁定</span>
                  <span v-else-if="row.needChangePwd === 1" class="tag tag-orange mini-tag">待改密</span>
                </div>
                <div class="cell-sub">{{ row.lastLoginText || '从未登录' }}</div>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="172" fixed="right">
              <template #default="{ row }">
                <div class="op-links">
                  <span class="link" @click="openEdit(row)">编辑</span>
                  <span class="link" @click="resetPwd(row)">重置密码</span>
                  <span
                    class="link" :class="{ 'link-danger': row.status === 1 }"
                    @click="toggleUser(row)"
                  >
                    {{ row.status === 1 ? '停用' : '启用' }}
                  </span>
                </div>
              </template>
            </el-table-column>
          </el-table>
        </div>

        <div class="pager">
          <span>共 {{ total }} 条记录</span>
          <el-pagination
            :current-page="query.page" :page-size="query.size" :total="total"
            :page-sizes="[10, 20, 50]" layout="sizes, prev, pager, next, jumper" background
            @size-change="onSizeChange" @current-change="onPageChange"
          />
        </div>
      </div>

      <!-- 右栏：角色权限配置 -->
      <div class="card perm-card">
        <div class="card-head">
          <div class="card-title">角色权限配置</div>
          <div class="card-extra">
            当前角色：<b class="role-current">{{ currentRole?.roleName || '—' }}</b>
          </div>
        </div>

        <div class="perm-body">
          <div class="rbac-note">
            <el-icon class="rn-icon"><InfoFilled /></el-icon>
            <span>系统采用 RBAC 权限模型：<b>用户 → 角色 → 菜单/按钮权限</b>，数据权限按所属食堂、农户隔离。</span>
          </div>

          <div class="field-label">选择角色</div>
          <el-select v-model="currentRoleId" placeholder="请选择角色" style="width: 100%" @change="onRoleChange">
            <el-option
              v-for="r in roles" :key="r.id" :value="r.id"
              :label="r.roleName + '（' + r.userCount + ' 个账号）'"
            />
          </el-select>
          <div v-if="currentRole" class="role-meta">
            <span class="tag tag-teal">{{ currentRole.roleCode }}</span>
            <span>{{ currentRole.description || '—' }}</span>
          </div>

          <div class="field-row">
            <span class="field-label">菜单与按钮权限</span>
            <span class="muted small">已勾选 {{ checkedCount }} 项</span>
          </div>
          <div class="tree-box">
            <el-tree
              ref="treeRef" :data="menus" show-checkbox node-key="id" default-expand-all
              :props="{ label: 'name', children: 'children' }" :expand-on-click-node="false"
              @check="syncCount"
            >
              <template #default="{ data }">
                <span class="tree-node">
                  <span class="tn-name">{{ data.name }}</span>
                  <span v-if="data.perms" class="tn-perm mono">{{ data.perms }}</span>
                </span>
              </template>
            </el-tree>
          </div>

          <div class="field-label">数据权限范围</div>
          <el-radio-group v-model="dataScope" class="scope-group">
            <el-radio v-for="s in dataScopes" :key="s.value" :value="s.value">{{ s.label }}</el-radio>
          </el-radio-group>

          <div class="perm-foot">
            <el-button @click="cancelPerms">取消</el-button>
            <el-button type="primary" :loading="savingPerm" @click="savePerms">保存权限</el-button>
          </div>
        </div>
      </div>
    </div>

    <!-- 角色管理 -->
    <el-dialog v-model="roleVisible" title="角色管理" width="700px">
      <div class="role-tip">
        角色决定菜单与按钮权限，数据权限范围在右侧「角色权限配置」面板中维护，保存后立即生效。
      </div>
      <el-table :data="roles" size="small" style="width: 100%">
        <el-table-column prop="roleName" label="角色名称" width="118" />
        <el-table-column label="角色编码" width="104">
          <template #default="{ row }"><span class="mono">{{ row.roleCode }}</span></template>
        </el-table-column>
        <el-table-column prop="description" label="职责说明" min-width="180" show-overflow-tooltip />
        <el-table-column label="数据范围" width="132">
          <template #default="{ row }">{{ scopeLabel(row.dataScope) }}</template>
        </el-table-column>
        <el-table-column label="账号数" width="82" align="right">
          <template #default="{ row }"><span class="money">{{ row.userCount }}</span></template>
        </el-table-column>
      </el-table>
      <template #footer>
        <el-button type="primary" @click="roleVisible = false">知道了</el-button>
      </template>
    </el-dialog>

    <!-- 新增 / 编辑用户 -->
    <el-dialog
      v-model="userVisible" :title="userForm.id ? '编辑用户' : '新增用户'" width="680px"
      :close-on-click-modal="false"
    >
      <el-form ref="userFormRef" :model="userForm" :rules="userRules" label-width="116px">
        <div class="form-grid">
          <el-form-item label="账号" prop="username">
            <el-input
              v-model="userForm.username" :disabled="!!userForm.id"
              placeholder="字母开头，如 farmer25" maxlength="24"
            />
          </el-form-item>
          <el-form-item label="姓名" prop="realName">
            <el-input v-model="userForm.realName" placeholder="请输入真实姓名" maxlength="20" />
          </el-form-item>
          <el-form-item label="手机号">
            <el-input v-model="userForm.phone" placeholder="请输入手机号" maxlength="20" />
          </el-form-item>
          <el-form-item label="角色" prop="roleType">
            <el-select v-model="userForm.roleType" placeholder="请选择角色" style="width: 100%">
              <el-option v-for="r in ROLE_OPTIONS" :key="r.value" :label="r.label" :value="r.value" />
            </el-select>
          </el-form-item>

          <!-- 账号必须落到业务档案上，否则登录后什么都看不到 -->
          <el-form-item
            v-if="roleNeedsBind" :label="userForm.roleType === 'FARMER' ? '绑定农户档案' : '绑定食堂'"
            class="span-2" :required="true"
          >
            <el-select
              v-model="userForm.bindId" filterable clearable
              :placeholder="bindPlaceholder" style="width: 100%"
              :loading="bindLoading" no-data-text="没有可绑定的档案，请先在基础档案中新增"
            >
              <el-option
                v-for="b in bindOptions" :key="b.id" :value="b.id"
                :label="b.name + '（' + b.no + '）'"
              >
                <div class="bind-opt">
                  <span class="bind-name">{{ b.name }}</span>
                  <span class="bind-no mono">{{ b.no }}</span>
                  <span class="bind-extra">{{ b.extra }}</span>
                </div>
              </el-option>
            </el-select>
            <div class="bind-hint">
              {{ userForm.roleType === 'FARMER'
                ? '农户账号只能看到自己档案下的需求、订单与结算；一个档案只能绑定一个账号。'
                : '采购员账号的数据按食堂隔离；一个食堂只能绑定一个采购员账号。' }}
              <span v-if="!bindOptions.length && !bindLoading" class="bind-warn">当前没有空闲档案</span>
            </div>
          </el-form-item>
          <div v-else class="span-2 bind-none">
            <el-icon><InfoFilled /></el-icon>
            <span>管理员 / 质检员是平台岗位，不绑定具体农户或食堂，登录后看到的是全局或本人负责的业务。</span>
          </div>

          <el-form-item v-if="!userForm.id" label="初始密码" prop="password" class="span-2">
            <div class="pwd-row">
              <el-input
                v-model="userForm.password" type="password" show-password
                placeholder="至少 8 位，须含字母和数字" maxlength="24"
              />
              <el-button plain @click="genPwd" :loading="pwdLoading">随机生成</el-button>
              <el-button plain @click="copyText(userForm.password, '初始密码')">复制</el-button>
            </div>
            <div class="pwd-hint-line">
              用户首次登录会被要求修改这个密码，改完才能进入系统。
            </div>
          </el-form-item>
          <el-form-item label="账号状态" class="span-2">
            <el-radio-group v-model="userForm.status">
              <el-radio :value="1">启用</el-radio>
              <el-radio :value="0">停用</el-radio>
            </el-radio-group>
            <span class="muted small form-hint">停用后该账号将无法登录系统</span>
          </el-form-item>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="userVisible = false">取消</el-button>
        <el-button type="primary" :loading="savingUser" @click="submitUser">
          {{ userForm.id ? '保存' : '创建账号' }}
        </el-button>
      </template>
    </el-dialog>

    <!-- 账号凭证：建号 / 重置后一次性展示，可直接复制转交 -->
    <el-dialog v-model="credVisible" title="账号凭证" width="470px" :close-on-click-modal="false">
      <div class="cred-tip">
        以下信息只展示这一次，请复制后交给本人，并提醒他首次登录时修改密码。
      </div>
      <div class="cred-card">
        <div class="cred-row"><span>账号</span><b class="mono">{{ cred.username }}</b></div>
        <div class="cred-row"><span>姓名</span><b>{{ cred.realName || '—' }}</b></div>
        <div class="cred-row"><span>角色</span><b>{{ cred.roleName }}</b></div>
        <div class="cred-row"><span>所属</span><b>{{ cred.orgName || '平台' }}</b></div>
        <div class="cred-row cred-pwd"><span>初始密码</span><b class="mono">{{ cred.password }}</b></div>
      </div>
      <div class="cred-next">
        <el-icon><WarningFilled /></el-icon>
        <span>对方首次登录后必须先修改密码，否则业务页面会被拦截。</span>
      </div>
      <template #footer>
        <el-button @click="credVisible = false">关闭</el-button>
        <el-button type="primary" @click="copyCred">复制全部信息</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { adminApi } from '@/api'
import { fmtNum } from '@/utils/format'
import { useAutoRefresh } from '@/utils/useAutoRefresh'

const ROLE_OPTIONS = [
  { value: 'ADMIN', label: '系统管理员' },
  { value: 'FARMER', label: '农户/合作社' },
  { value: 'PURCHASER', label: '食堂采购员' },
  { value: 'INSPECTOR', label: '质检配送员' }
]
const ROLE_LABEL = Object.fromEntries(ROLE_OPTIONS.map((r) => [r.value, r.label]))

/* ---------------- 用户列表 ---------------- */
const loading = ref(false)
const rows = ref([])
const total = ref(0)
const stats = ref({})
const query = reactive({ page: 1, size: 10, keyword: '', role: '' })
let seq = 0

const buildParams = () => {
  const p = { page: query.page, size: query.size }
  if (query.keyword) p.keyword = query.keyword.trim()
  if (query.role) p.role = query.role
  return p
}

const loadUsers = async () => {
  const my = ++seq
  loading.value = true
  try {
    const res = await adminApi.users(buildParams())
    if (my !== seq) return
    const data = res.data || {}
    rows.value = data.page?.records || []
    total.value = Number(data.page?.total || 0)
    stats.value = data.stats || {}
  } finally {
    if (my === seq) loading.value = false
  }
}

const search = () => {
  query.page = 1
  loadUsers()
}

const reset = () => {
  query.keyword = ''
  query.role = ''
  query.page = 1
  loadUsers()
}

const onSizeChange = (size) => {
  query.size = size
  query.page = 1
  loadUsers()
}

const onPageChange = (page) => {
  query.page = page
  loadUsers()
}

const avatarText = (row) => row.avatar || (row.realName || row.username || '用').slice(0, 1)

const roleTag = (roleType) =>
  roleType === 'ADMIN'
    ? 'tag-green'
    : roleType === 'FARMER'
      ? 'tag-teal'
      : roleType === 'PURCHASER'
        ? 'tag-blue'
        : 'tag-purple'

/* ---------------- 角色与权限 ---------------- */
const roles = ref([])
const menus = ref([])
const dataScopes = ref([])
const currentRoleId = ref(null)
const dataScope = ref('ALL')
const checkedCount = ref(0)
const savingPerm = ref(false)
const treeRef = ref(null)
const roleVisible = ref(false)

const currentRole = computed(
  () => roles.value.find((r) => r.id === currentRoleId.value) || null
)

const scopeLabel = (value) => {
  const hit = dataScopes.value.find((s) => s.value === value)
  return hit?.label || value || '—'
}

const syncCount = () => {
  checkedCount.value = (treeRef.value?.getCheckedKeys() || []).length
}

const applyRole = () => {
  dataScope.value = currentRole.value?.dataScope || 'ALL'
  nextTick(() => {
    treeRef.value?.setCheckedKeys(currentRole.value?.menuIds || [], false)
    syncCount()
  })
}

const onRoleChange = () => {
  applyRole()
}

const loadRoles = async () => {
  try {
    const res = await adminApi.roles()
    const data = res.data || {}
    roles.value = data.roles || []
    menus.value = data.menus || []
    dataScopes.value = data.dataScopes || []
    if (!roles.value.some((r) => r.id === currentRoleId.value)) {
      currentRoleId.value = roles.value[0]?.id ?? null
    }
    applyRole()
  } catch (e) {
    /* 请求拦截器已提示 */
  }
}

const savePerms = async () => {
  if (!currentRoleId.value) {
    ElMessage.warning('请先选择角色')
    return
  }
  const checked = treeRef.value?.getCheckedKeys(false) || []
  const half = treeRef.value?.getHalfCheckedKeys() || []
  const menuIds = [...new Set([...checked, ...half])]
  savingPerm.value = true
  try {
    await adminApi.saveRoleMenus(currentRoleId.value, { menuIds, dataScope: dataScope.value })
    ElMessage.success(`已保存「${currentRole.value?.roleName}」的菜单权限与数据范围`)
    await loadRoles()
  } catch (e) {
    /* 请求拦截器已提示 */
  } finally {
    savingPerm.value = false
  }
}

const cancelPerms = () => {
  applyRole()
  ElMessage.info('已还原为保存前的权限配置')
}

/* ---------------- 用户新增 / 编辑 ---------------- */
const userVisible = ref(false)
const savingUser = ref(false)
const pwdLoading = ref(false)
const userFormRef = ref(null)
const bindOptions = ref([])
const bindLoading = ref(false)

const roleNeedsBind = computed(
  () => userForm.roleType === 'FARMER' || userForm.roleType === 'PURCHASER'
)
const bindPlaceholder = computed(() =>
  userForm.roleType === 'FARMER' ? '选择要绑定的农户/合作社档案' : '选择要绑定的食堂'
)

const blankUser = () => ({
  id: null,
  username: '',
  realName: '',
  phone: '',
  roleType: 'FARMER',
  status: 1,
  password: '',
  bindId: null
})
const userForm = reactive(blankUser())

const pwdValidator = (rule, value, callback) => {
  if (!value) {
    // 新建时必须显式设置初始密码；编辑留空表示不修改密码
    return userForm.id ? callback() : callback(new Error('请设置初始密码'))
  }
  if (value.length >= 8 && /[A-Za-z]/.test(value) && /\d/.test(value)) callback()
  else callback(new Error('密码至少 8 位，且须同时包含字母和数字'))
}

const userRules = {
  username: [
    { required: true, message: '请输入登录账号', trigger: 'blur' },
    {
      pattern: /^[A-Za-z][A-Za-z0-9_]{2,23}$/,
      message: '字母开头，3-24 位，只能含字母、数字、下划线',
      trigger: 'blur'
    }
  ],
  realName: [{ required: true, message: '请输入真实姓名', trigger: 'blur' }],
  roleType: [{ required: true, message: '请选择角色', trigger: 'change' }],
  password: [{ validator: pwdValidator, trigger: 'blur' }]
}

const loadBindable = async () => {
  bindOptions.value = []
  if (!roleNeedsBind.value) return
  bindLoading.value = true
  try {
    const res = await adminApi.bindable(userForm.roleType, userForm.id || undefined)
    bindOptions.value = res.data || []
  } catch (e) {
    bindOptions.value = []
  } finally {
    bindLoading.value = false
  }
}

/* 切角色就重新取可绑定档案：农户与食堂是两套档案，不能串用 */
watch(
  () => userForm.roleType,
  () => {
    if (!userVisible.value) return
    userForm.bindId = null
    loadBindable()
  }
)

const openCreate = () => {
  Object.assign(userForm, blankUser())
  userVisible.value = true
  nextTick(() => {
    userFormRef.value?.clearValidate()
    loadBindable()
  })
}

const openEdit = (row) => {
  if (!row) return
  Object.assign(userForm, blankUser(), {
    id: row.id,
    username: row.username || '',
    realName: row.realName || '',
    phone: row.phone || '',
    roleType: row.roleType || 'ADMIN',
    status: row.status === 0 ? 0 : 1,
    password: '',
    bindId: row.farmerId || row.canteenId || null
  })
  userVisible.value = true
  nextTick(() => {
    userFormRef.value?.clearValidate()
    loadBindable()
  })
}

const genPwd = async () => {
  pwdLoading.value = true
  try {
    const res = await adminApi.passwordSuggestion()
    userForm.password = res.data?.password || ''
  } catch (e) {
    /* 请求拦截器已提示 */
  } finally {
    pwdLoading.value = false
  }
}

const submitUser = () => {
  userFormRef.value.validate(async (valid) => {
    if (!valid) return
    if (roleNeedsBind.value && !userForm.bindId) {
      ElMessage.warning(
        userForm.roleType === 'FARMER' ? '请选择要绑定的农户档案' : '请选择要绑定的食堂'
      )
      return
    }
    savingUser.value = true
    try {
      const payload = {
        username: userForm.username,
        realName: userForm.realName,
        phone: userForm.phone,
        roleType: userForm.roleType,
        status: Number(userForm.status)
      }
      if (userForm.id) payload.id = userForm.id
      const role = roles.value.find((r) => r.roleCode === userForm.roleType)
      if (role) payload.roleId = role.id
      if (userForm.roleType === 'FARMER') payload.farmerId = userForm.bindId
      if (userForm.roleType === 'PURCHASER') payload.canteenId = userForm.bindId
      if (!userForm.id) payload.password = userForm.password

      const res = await adminApi.saveUser(payload)
      userVisible.value = false
      if (userForm.id) {
        ElMessage.success('用户信息已更新')
      } else {
        showCredential({
          username: userForm.username,
          realName: userForm.realName,
          roleName: ROLE_LABEL[userForm.roleType],
          orgName: bindOptions.value.find((b) => b.id === userForm.bindId)?.name || '平台',
          password: res.data?.initialPassword || userForm.password
        })
      }
      await loadUsers()
      await loadRoles()
    } catch (e) {
      /* 请求拦截器已提示 */
    } finally {
      savingUser.value = false
    }
  })
}

/* ---------------- 账号凭证 ---------------- */
const credVisible = ref(false)
const cred = reactive({ username: '', realName: '', roleName: '', orgName: '', password: '' })

const showCredential = (data) => {
  Object.assign(cred, { username: '', realName: '', roleName: '', orgName: '', password: '' }, data)
  credVisible.value = true
}

const credText = () =>
  [
    `账号：${cred.username}`,
    `姓名：${cred.realName || '—'}`,
    `角色：${cred.roleName || '—'}`,
    `所属：${cred.orgName || '平台'}`,
    `初始密码：${cred.password}`,
    '首次登录后请立即修改密码。'
  ].join('\n')

const copyText = async (text, label = '内容') => {
  if (!text) {
    ElMessage.warning(`暂无可复制的${label}`)
    return
  }
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success(`${label}已复制`)
  } catch (e) {
    // 非 HTTPS / 旧浏览器没有 clipboard API，退回 textarea 方案
    const ta = document.createElement('textarea')
    ta.value = text
    ta.style.position = 'fixed'
    ta.style.opacity = '0'
    document.body.appendChild(ta)
    ta.select()
    try {
      document.execCommand('copy')
      ElMessage.success(`${label}已复制`)
    } catch (err) {
      ElMessage.warning('复制失败，请手动选中复制')
    }
    document.body.removeChild(ta)
  }
}

const copyCred = () => copyText(credText(), '账号凭证')

const resetPwd = async (row) => {
  try {
    await ElMessageBox.confirm(
      `确定重置「${row.realName || row.username}」的登录密码吗？重置后原密码立即失效，`
      + '该用户下次登录必须重新设置密码。',
      '重置密码',
      { type: 'warning', confirmButtonText: '确定重置', cancelButtonText: '取消' }
    )
  } catch (e) {
    return
  }
  try {
    const res = await adminApi.resetPassword(row.id)
    showCredential({
      username: row.username,
      realName: row.realName,
      roleName: row.roleName,
      orgName: row.orgName,
      password: res.data?.password || ''
    })
    await loadUsers()
  } catch (e) {
    /* 请求拦截器已提示 */
  }
}

const toggleUser = async (row) => {
  const next = row.status === 1 ? 0 : 1
  const label = next === 0 ? '停用' : '启用'
  try {
    await ElMessageBox.confirm(
      `确定${label}账号「${row.username}」吗？${next === 0 ? '停用后该账号将立即下线且无法登录。' : ''}`,
      `${label}账号`,
      { type: 'warning', confirmButtonText: '确定', cancelButtonText: '取消' }
    )
  } catch (e) {
    return
  }
  try {
    await adminApi.userStatus(row.id, next)
    ElMessage.success(`账号「${row.username}」已${label}`)
    await loadUsers()
  } catch (e) {
    /* 请求拦截器已提示 */
  }
}

useAutoRefresh(loadUsers)
onMounted(() => {
  loadUsers()
  loadRoles()
})
</script>

<style scoped>
.user-grid {
  display: grid;
  grid-template-columns: minmax(0, 2.12fr) minmax(0, 1fr);
  gap: 14px;
  align-items: stretch;
}

.table-card {
  /* 不裁切表格：固定列阴影与横向滚动条必须可见（L-040） */
  overflow: visible;
  display: flex;
  flex-direction: column;
}

.table-card .table-wrap {
  flex: 1;
}

.cell-main {
  font-size: var(--fs-base);
  font-weight: 600;
  color: var(--ink);
  line-height: 1.35;
}

.cell-main-2 {
  font-size: var(--fs-sm);
  color: var(--ink);
  line-height: 1.35;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.cell-sub {
  font-size: var(--fs-xs);
  color: var(--c-muted-2);
  margin-top: 2px;
}

.user-cell {
  display: flex;
  align-items: center;
  gap: 9px;
}

.u-avatar {
  width: 30px;
  height: 30px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  color: #fff;
  font-size: var(--fs-sm);
  font-weight: 600;
  background: linear-gradient(135deg, #34d399, var(--accent));
  flex-shrink: 0;
}

.mini-tag {
  margin-left: 4px;
  font-size: var(--fs-2xs);
  padding: 0 5px;
}

.state-cell {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 3px;
}

.state-cell {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 3px;
}

.pwd-pending {
  color: var(--warn);
}

.op-links {
  display: flex;
  align-items: center;
}

.link-danger {
  color: var(--danger);
}

.link-danger:hover {
  color: var(--danger);
}

/* ---------- 权限面板 ---------- */
.perm-card {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.role-current {
  color: var(--accent);
  font-weight: 600;
}

.perm-body {
  display: flex;
  flex-direction: column;
  gap: 9px;
  flex: 1;
  min-height: 0;
  padding: 14px 16px 16px;
}

.rbac-note {
  display: flex;
  align-items: flex-start;
  gap: 7px;
  padding: 9px 11px;
  border-radius: 9px;
  background: var(--surface-2);
  border: 1px solid #d9f0e2;
  color: var(--muted);
  font-size: var(--fs-xs);
  line-height: 1.6;
}

.rbac-note b {
  color: var(--accent);
  font-weight: 600;
}

.rn-icon {
  color: var(--c-primary);
  font-size: var(--fs-base);
  margin-top: 3px;
  flex-shrink: 0;
}

.field-label {
  font-size: var(--fs-sm);
  font-weight: 600;
  color: var(--c-text-2);
}

.role-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: -3px;
  font-size: var(--fs-xs);
  color: var(--c-muted);
  min-width: 0;
}

.role-meta > span:last-child {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.field-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-top: 2px;
}

.tree-box {
  flex: 1;
  min-height: 206px;
  max-height: 330px;
  overflow: auto;
  border: 1px solid var(--c-border);
  border-radius: 10px;
  background: #fbfdfc;
  padding: 5px 2px;
}

.tree-box :deep(.el-tree) {
  background: transparent;
  font-size: var(--fs-sm);
}

.tree-box :deep(.el-tree-node__content) {
  height: 28px;
}

.tree-node {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  min-width: 0;
  padding-right: 8px;
}

.tn-name {
  font-size: var(--fs-sm);
  color: var(--ink);
  white-space: nowrap;
}

.tn-perm {
  margin-left: auto;
  font-size: var(--fs-2xs);
  color: var(--c-muted-2);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.scope-group {
  display: flex;
  flex-direction: column;
  gap: 1px;
  width: 100%;
}

.scope-group :deep(.el-radio) {
  margin-right: 0;
  height: 26px;
}

.scope-group :deep(.el-radio__label) {
  font-size: var(--fs-sm);
}

.perm-foot {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  margin-top: auto;
  padding-top: 10px;
  border-top: 1px dashed var(--c-border);
}

.role-tip {
  margin-bottom: 10px;
  padding: 9px 11px;
  border-radius: 9px;
  background: var(--surface-2);
  border: 1px solid var(--c-border);
  font-size: var(--fs-sm);
  color: var(--c-muted);
}

.form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0 14px;
}

.form-grid .span-2 {
  grid-column: span 2;
}

.form-hint {
  margin-left: 10px;
}

/* ---------- 绑定档案 ---------- */
.bind-opt {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
}

.bind-name {
  font-size: var(--fs-sm);
  color: var(--ink);
}

.bind-no {
  font-size: var(--fs-2xs);
  color: var(--c-muted-2);
}

.bind-extra {
  margin-left: auto;
  font-size: var(--fs-2xs);
  color: var(--c-muted);
}

.bind-hint {
  margin-top: 2px;
  font-size: var(--fs-2xs);
  line-height: 1.6;
  color: var(--c-muted);
}

.bind-warn {
  color: var(--danger);
  font-weight: 600;
}

.bind-none {
  display: flex;
  align-items: flex-start;
  gap: 7px;
  margin: 0 0 14px;
  padding: 9px 11px;
  border-radius: 9px;
  background: var(--surface-2);
  border: 1px solid var(--c-border);
  font-size: var(--fs-xs);
  line-height: 1.6;
  color: var(--c-muted);
}

/* ---------- 初始密码 ---------- */
.pwd-row {
  display: flex;
  gap: 8px;
  width: 100%;
}

.pwd-row .el-input {
  flex: 1;
}

.pwd-hint-line {
  margin-top: 2px;
  font-size: var(--fs-2xs);
  color: var(--c-muted);
}

/* ---------- 账号凭证 ---------- */
.cred-tip {
  padding: 9px 11px;
  margin-bottom: 12px;
  border-radius: 9px;
  background: var(--accent-soft);
  font-size: var(--fs-sm);
  line-height: 1.6;
  color: var(--ink-2);
}

.cred-card {
  border: 1px solid var(--c-border);
  border-radius: 12px;
  overflow: hidden;
}

.cred-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 9px 14px;
  font-size: var(--fs-sm);
  border-bottom: 1px dashed var(--c-border);
}

.cred-row:last-child {
  border-bottom: none;
}

.cred-row > span {
  width: 72px;
  flex: 0 0 72px;
  color: var(--c-muted);
  font-size: var(--fs-xs);
}

.cred-row > b {
  color: var(--ink);
  font-weight: 600;
}

.cred-pwd {
  background: var(--surface-2);
}

.cred-pwd > b {
  font-size: var(--fs-lg);
  letter-spacing: 1px;
  color: var(--accent);
}

.cred-next {
  display: flex;
  align-items: flex-start;
  gap: 7px;
  margin-top: 10px;
  font-size: var(--fs-2xs);
  line-height: 1.6;
  color: var(--warn);
}
</style>
