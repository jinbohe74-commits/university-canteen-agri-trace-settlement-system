<template>
  <div class="login-page">
    <!-- 左侧品牌区 -->
    <section class="brand-side">
      <div class="brand-inner">
        <div class="brand-tag">
          <el-icon><Sunny /></el-icon>
          <span>农校直供平台</span>
        </div>

        <h1 class="brand-title">
          农产品直供溯源<br />
          与结算管理系统
        </h1>
        <div class="brand-en">FARM-TO-CANTEEN DIRECT SUPPLY · TRACE &amp; SETTLEMENT</div>

        <p class="brand-desc">
          连接农户 / 合作社、高校食堂、质检配送与管理部门，打造从田间到餐桌的
          阳光采购闭环，让每一批农产品来源可溯、损耗可算、结算可信。
        </p>

        <div class="cap-grid">
          <div v-for="cap in capabilities" :key="cap.title" class="cap-card">
            <div class="cap-icon"><el-icon><component :is="cap.icon" /></el-icon></div>
            <div>
              <div class="cap-title">{{ cap.title }}</div>
              <div class="cap-sub">{{ cap.desc }}</div>
            </div>
          </div>
        </div>
      </div>
      <div class="brand-foot">
        © 2026 面向高校食堂的农产品直供溯源与结算管理系统 · Spring Boot + Vue3 + MySQL
      </div>
      <div class="blob blob-1"></div>
      <div class="blob blob-2"></div>
      <div class="leaf leaf-1"></div>
      <div class="leaf leaf-2"></div>
    </section>

    <!-- 右侧登录区 -->
    <section class="form-side">
      <div class="login-card">
        <div class="card-head">
          <h2>欢迎回来</h2>
          <p>请选择角色并登录您的工作台</p>
        </div>

        <div class="role-grid">
          <button
            v-for="r in roleOptions"
            :key="r.code"
            class="role-item"
            :class="{ active: form.role === r.code }"
            @click="pickRole(r)"
          >
            <el-icon class="role-icon"><component :is="r.icon" /></el-icon>
            <span>{{ r.label }}</span>
          </button>
        </div>

        <el-form :model="form" @submit.prevent="submit" class="login-form">
          <label class="field-label">用户名</label>
          <el-input v-model="form.username" size="large" placeholder="请输入账号" @keyup.enter="submit">
            <template #prefix><el-icon><User /></el-icon></template>
          </el-input>

          <label class="field-label">密码</label>
          <el-input
            v-model="form.password"
            size="large"
            type="password"
            show-password
            placeholder="请输入密码"
            @keyup.enter="submit"
          >
            <template #prefix><el-icon><Lock /></el-icon></template>
          </el-input>

          <div class="form-row">
            <el-checkbox v-model="form.remember">记住我（7 天免登录）</el-checkbox>
            <span class="forgot" @click="forgot">忘记密码？</span>
          </div>

          <el-button type="primary" size="large" class="submit" :loading="loading" @click="submit">
            登 录
          </el-button>
        </el-form>

        <div class="hint-row">
          <span>默认管理员账号 <b>admin</b> / 密码 <b>123456</b></span>
          <span class="lock-hint"><el-icon><Lock /></el-icon>连续输错 5 次将锁定 10 分钟</span>
        </div>

        <div class="card-foot">
          <span>V1.0.0</span>
          <span>建议使用 Chrome / Edge 浏览器访问</span>
        </div>
      </div>
    </section>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/store/user'

const router = useRouter()
const route = useRoute()
const store = useUserStore()
const loading = ref(false)

const form = reactive({
  username: 'admin',
  password: '123456',
  remember: true,
  role: 'ADMIN'
})

const capabilities = [
  { title: '五因子智能匹配', desc: '权重后台可配置', icon: 'MagicStick' },
  { title: '批次溯源存证', desc: '二维码 + SHA-256', icon: 'Search' },
  { title: '生鲜损耗结算', desc: '部分收货自动扣款', icon: 'Money' },
  { title: '食安阈值预警', desc: '证照 / 价格 / 逾期', icon: 'Warning' }
]

const roleOptions = [
  { code: 'ADMIN', label: '管理员', icon: 'UserFilled', account: 'admin' },
  { code: 'FARMER', label: '农户', icon: 'Apple', account: 'farmer24' },
  { code: 'PURCHASER', label: '采购员', icon: 'ShoppingCart', account: 'purchaser01' },
  { code: 'INSPECTOR', label: '质检员', icon: 'Van', account: 'inspector01' }
]

const pickRole = (r) => {
  form.role = r.code
  form.username = r.account
  form.password = '123456'
}

const forgot = () => {
  ElMessage.info('请联系系统管理员（admin）重置密码')
}

const submit = async () => {
  if (!form.username || !form.password) {
    ElMessage.warning('请输入用户名和密码')
    return
  }
  loading.value = true
  try {
    const data = await store.login({
      username: form.username.trim(),
      password: form.password,
      remember: form.remember
    })
    ElMessage.success('欢迎回来，' + data.user.realName)
    const redirect = route.query.redirect
    router.replace(redirect || store.home)
  } catch (e) {
    /* 错误提示已由拦截器统一处理 */
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-page {
  display: flex;
  min-height: 100vh;
  background: #f4f8f6;
}

/* ---------------- 左：品牌区 ---------------- */
.brand-side {
  position: relative;
  width: 54%;
  min-height: 100vh;
  padding: 56px 64px 40px;
  color: #fff;
  overflow: hidden;
  background: radial-gradient(120% 90% at 12% 8%, #1f7a4d 0%, #145c3a 42%, #0b3a26 100%);
  display: flex;
  flex-direction: column;
  justify-content: space-between;
}

.brand-inner {
  position: relative;
  z-index: 2;
  max-width: 640px;
}

.brand-tag {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  padding: 6px 14px;
  border-radius: 20px;
  background: rgba(255, 255, 255, 0.13);
  border: 1px solid rgba(255, 255, 255, 0.18);
  font-size: 12.5px;
  letter-spacing: 1px;
  color: #d9f7e5;
}

.brand-title {
  margin: 34px 0 16px;
  font-size: 44px;
  line-height: 1.28;
  font-weight: 700;
  letter-spacing: 1.5px;
  text-shadow: 0 6px 24px rgba(0, 0, 0, 0.18);
}

.brand-en {
  font-size: 11.5px;
  letter-spacing: 2.4px;
  color: rgba(214, 246, 227, 0.62);
}

.brand-desc {
  margin: 26px 0 38px;
  max-width: 560px;
  font-size: 14px;
  line-height: 2;
  color: rgba(226, 246, 235, 0.82);
}

.cap-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px;
  max-width: 560px;
}

.cap-card {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 15px 16px;
  border-radius: 14px;
  background: rgba(255, 255, 255, 0.08);
  border: 1px solid rgba(255, 255, 255, 0.13);
  backdrop-filter: blur(7px);
}

.cap-icon {
  width: 34px;
  height: 34px;
  border-radius: 10px;
  display: grid;
  place-items: center;
  background: rgba(74, 222, 128, 0.18);
  color: #86efac;
  font-size: 17px;
  flex-shrink: 0;
}

.cap-title {
  font-size: 13.5px;
  font-weight: 600;
  color: #fff;
}

.cap-sub {
  font-size: 11.5px;
  color: rgba(214, 246, 227, 0.58);
  margin-top: 3px;
}

.brand-foot {
  position: relative;
  z-index: 2;
  font-size: 11.5px;
  color: rgba(214, 246, 227, 0.42);
  letter-spacing: 0.3px;
}

.blob {
  position: absolute;
  border-radius: 50%;
  filter: blur(2px);
  opacity: 0.6;
}

.blob-1 {
  width: 420px;
  height: 420px;
  right: -160px;
  top: -120px;
  background: radial-gradient(circle, rgba(74, 222, 128, 0.22), transparent 68%);
}

.blob-2 {
  width: 520px;
  height: 520px;
  left: -220px;
  bottom: -260px;
  background: radial-gradient(circle, rgba(16, 185, 129, 0.18), transparent 68%);
}

.leaf {
  position: absolute;
  border: 1px solid rgba(255, 255, 255, 0.07);
  border-radius: 60% 0 60% 0;
}

.leaf-1 {
  width: 210px;
  height: 210px;
  right: 40px;
  bottom: 80px;
  transform: rotate(18deg);
}

.leaf-2 {
  width: 130px;
  height: 130px;
  right: 180px;
  bottom: 190px;
  transform: rotate(-24deg);
}

/* ---------------- 右：登录表单 ---------------- */
.form-side {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 40px;
}

.login-card {
  width: 100%;
  max-width: 432px;
  background: #fff;
  border-radius: 18px;
  padding: 34px 34px 22px;
  box-shadow: 0 24px 60px rgba(16, 58, 40, 0.12);
  border: 1px solid #eaf1ee;
}

.card-head h2 {
  margin: 0;
  font-size: 23px;
  font-weight: 700;
  color: #0f172a;
  letter-spacing: 0.5px;
}

.card-head p {
  margin: 8px 0 22px;
  font-size: 13px;
  color: var(--c-muted);
}

.role-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 9px;
  margin-bottom: 22px;
}

.role-item {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  padding: 11px 4px 9px;
  border-radius: 11px;
  border: 1px solid var(--c-border-strong);
  background: #fff;
  color: #5b6b64;
  font-size: 12px;
  cursor: pointer;
  transition: all 0.18s ease;
}

.role-item:hover {
  border-color: #a7e3bf;
  color: var(--c-primary-dark);
  background: #f6fdf9;
}

.role-item.active {
  border-color: var(--c-primary);
  background: linear-gradient(180deg, #f0fdf4, #e6f8ed);
  color: var(--c-primary-dark);
  font-weight: 600;
  box-shadow: 0 4px 12px rgba(22, 163, 74, 0.14);
}

.role-icon {
  font-size: 17px;
}

.login-form :deep(.el-input__wrapper) {
  height: 44px;
  border-radius: 10px;
  font-size: 13.5px;
}

.field-label {
  display: block;
  font-size: 12.5px;
  color: #4b5563;
  margin: 0 0 7px;
  font-weight: 500;
}

.login-form :deep(.el-input) {
  margin-bottom: 16px;
}

.form-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin: 2px 0 20px;
}

.form-row :deep(.el-checkbox__label) {
  font-size: 12.5px;
  color: var(--c-muted);
}

.forgot {
  font-size: 12.5px;
  color: var(--c-primary);
  cursor: pointer;
}

.forgot:hover {
  text-decoration: underline;
}

.submit {
  width: 100%;
  height: 44px;
  font-size: 15px;
  font-weight: 600;
  letter-spacing: 4px;
  border-radius: 10px;
  box-shadow: 0 10px 22px rgba(22, 163, 74, 0.24);
}

.hint-row {
  display: flex;
  flex-direction: column;
  gap: 7px;
  margin-top: 18px;
  padding: 12px 14px;
  border-radius: 10px;
  background: #f7faf9;
  border: 1px dashed var(--c-border-strong);
  font-size: 11.5px;
  color: var(--c-muted);
}

.lock-hint {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  color: #b45309;
}

.card-foot {
  display: flex;
  justify-content: space-between;
  margin-top: 18px;
  padding-top: 14px;
  border-top: 1px solid var(--c-border);
  font-size: 11px;
  color: var(--c-muted-2);
}

@media (max-width: 1180px) {
  .brand-side {
    display: none;
  }
}
</style>
