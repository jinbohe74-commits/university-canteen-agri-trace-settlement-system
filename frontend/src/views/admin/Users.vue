<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2 class="page-title">用户与权限</h2>
        <p class="page-sub">管理系统账号、分配角色，基于 RBAC 模型配置菜单与数据权限</p>
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
          <div class="card-extra">共 {{ fmtNum(stats.total) }} 个账号 · 在线 {{ fmtNum(stats.online) }}</div>
        </div>

        <div class="filter-bar">
          <el-input
            v-model="query.keyword" placeholder="搜索账号 / 姓名" clearable
            style="width: 196px" @keyup.enter="search" @clear="search"
          >
            <template #prefix><el-icon><Search /></el-icon></template>
          </el-input>
          <el-select v-model="query.role" placeholder="角色" clearable style="width: 156px" @change="search">
            <el-option label="全部角色" value="" />
            <el-option v-for="r in ROLE_OPTIONS" :key="r.value" :label="r.label" :value="r.value" />
          </el-select>
          <div class="filter-spacer"></div>
          <el-button plain @click="reset"><el-icon><Refresh /></el-icon>重置</el-button>
          <el-button type="primary" @click="search"><el-icon><Search /></el-icon>查询</el-button>
        </div>

        <div class="table-wrap">
          <el-table v-loading="loading" :data="rows" style="width: 100%" empty-text="暂无符合条件的用户">
            <el-table-column label="用户" min-width="140">
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
            <el-table-column label="角色" width="110">
              <template #default="{ row }">
                <span class="tag" :class="roleTag(row.roleType)">{{ row.roleName }}</span>
              </template>
            </el-table-column>
            <el-table-column label="手机号" width="106">
              <template #default="{ row }"><span class="mono">{{ row.phone || '—' }}</span></template>
            </el-table-column>
            <el-table-column label="最近登录" width="92">
              <template #default="{ row }">
                <span class="small muted">{{ row.lastLoginText || '从未登录' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="88">
              <template #default="{ row }">
                <span class="tag tag-dot" :class="row.status === 1 ? 'tag-green' : 'tag-gray'">
                  {{ row.status === 1 ? '已启用' : '已停用' }}
                </span>
              </template>
            </el-table-column>
            <el-table-column label="在线" width="74">
              <template #default="{ row }">
                <span class="tag tag-dot" :class="row.online === 1 ? 'tag-green' : 'tag-gray'">
                  {{ row.online === 1 ? '在线' : '离线' }}
                </span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="146" fixed="right">
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
      v-model="userVisible" :title="userForm.id ? '编辑用户' : '新增用户'" width="580px"
      :close-on-click-modal="false"
    >
      <el-form ref="userFormRef" :model="userForm" :rules="userRules" label-width="88px">
        <div class="form-grid">
          <el-form-item label="账号" prop="username">
            <el-input
              v-model="userForm.username" :disabled="!!userForm.id"
              placeholder="登录账号，如 farmer25" maxlength="24"
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
          <el-form-item v-if="!userForm.id" label="初始密码" class="span-2">
            <el-input
              v-model="userForm.password" type="password" show-password
              placeholder="留空则默认为 123456" maxlength="24"
            />
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
        <el-button type="primary" :loading="savingUser" @click="submitUser">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { adminApi } from '@/api'
import { fmtNum } from '@/utils/format'

const ROLE_OPTIONS = [
  { value: 'ADMIN', label: '系统管理员' },
  { value: 'FARMER', label: '农户/合作社' },
  { value: 'PURCHASER', label: '食堂采购员' },
  { value: 'INSPECTOR', label: '质检配送员' }
]

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
const userFormRef = ref(null)

const blankUser = () => ({
  id: null,
  username: '',
  realName: '',
  phone: '',
  roleType: 'ADMIN',
  status: 1,
  password: ''
})
const userForm = reactive(blankUser())

const pwdValidator = (rule, value, callback) => {
  if (!value || value.length >= 6) callback()
  else callback(new Error('初始密码至少 6 位'))
}

const userRules = {
  username: [{ required: true, message: '请输入登录账号', trigger: 'blur' }],
  realName: [{ required: true, message: '请输入真实姓名', trigger: 'blur' }],
  roleType: [{ required: true, message: '请选择角色', trigger: 'change' }],
  password: [{ validator: pwdValidator, trigger: 'blur' }]
}

const openCreate = () => {
  Object.assign(userForm, blankUser())
  userVisible.value = true
  userFormRef.value?.clearValidate()
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
    password: ''
  })
  userVisible.value = true
  userFormRef.value?.clearValidate()
}

const submitUser = () => {
  userFormRef.value.validate(async (valid) => {
    if (!valid) return
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
      if (!userForm.id && userForm.password) payload.password = userForm.password
      await adminApi.saveUser(payload)
      ElMessage.success(userForm.id ? '用户信息已更新' : '用户已创建，初始密码为 ' + (userForm.password || '123456'))
      userVisible.value = false
      await loadUsers()
      await loadRoles()
    } catch (e) {
      /* 请求拦截器已提示 */
    } finally {
      savingUser.value = false
    }
  })
}

const resetPwd = async (row) => {
  try {
    await ElMessageBox.confirm(
      `确定重置「${row.realName || row.username}」的登录密码吗？重置后原密码立即失效。`,
      '重置密码',
      { type: 'warning', confirmButtonText: '确定重置', cancelButtonText: '取消' }
    )
  } catch (e) {
    return
  }
  try {
    await adminApi.resetPassword(row.id)
    ElMessage.success('密码已重置为 123456')
  } catch (e) {
    /* 请求拦截器已提示 */
  }
}

const toggleUser = async (row) => {
  const next = row.status === 1 ? 0 : 1
  const label = next === 0 ? '停用' : '启用'
  try {
    await ElMessageBox.confirm(
      `确定${label}账号「${row.username}」吗？${next === 0 ? '停用后该账号将无法登录系统。' : ''}`,
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
  overflow: hidden;
  display: flex;
  flex-direction: column;
}

.table-card .table-wrap {
  flex: 1;
}

.cell-main {
  font-size: 13px;
  font-weight: 600;
  color: #1b2a24;
  line-height: 1.35;
}

.cell-sub {
  font-size: 11.5px;
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
  font-size: 12.5px;
  font-weight: 600;
  background: linear-gradient(135deg, #34d399, #15803d);
  flex-shrink: 0;
}

.op-links {
  display: flex;
  align-items: center;
}

.link-danger {
  color: #dc2626;
}

.link-danger:hover {
  color: #b91c1c;
}

/* ---------- 权限面板 ---------- */
.perm-card {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.role-current {
  color: #15803d;
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
  background: #f2fbf5;
  border: 1px solid #d9f0e2;
  color: #4b7a60;
  font-size: 11.5px;
  line-height: 1.6;
}

.rbac-note b {
  color: #15803d;
  font-weight: 600;
}

.rn-icon {
  color: var(--c-primary);
  font-size: 13px;
  margin-top: 3px;
  flex-shrink: 0;
}

.field-label {
  font-size: 12px;
  font-weight: 600;
  color: var(--c-text-2);
}

.role-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: -3px;
  font-size: 11.5px;
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
  font-size: 12.5px;
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
  font-size: 12.5px;
  color: #27362f;
  white-space: nowrap;
}

.tn-perm {
  margin-left: auto;
  font-size: 10.5px;
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
  font-size: 12.5px;
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
  background: #f7faf9;
  border: 1px solid var(--c-border);
  font-size: 12px;
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
</style>
