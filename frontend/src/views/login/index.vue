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
          <p>选择角色将校验账号身份；也可直接登录由系统自动识别</p>
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
            <el-checkbox v-model="form.remember">记住账号（下次自动填写）</el-checkbox>
            <span class="forgot" @click="forgot">忘记密码？</span>
          </div>

          <el-button type="primary" size="large" class="submit" :loading="loading" @click="submit">
            登 录
          </el-button>
        </el-form>

        <div class="hint-row">
          <span>可在多个标签页各登录一个角色，互不影响</span>
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
  // 修复 P1：不再预填演示账号与弱口令，避免任何访客一键登录管理员
  username: localStorage.getItem('ct_last_username') || '',
  password: '',
  remember: !!localStorage.getItem('ct_last_username'),
  // 默认不选角色：避免「默认管理员高亮」误导，且不影响直接登录
  role: ''
})

const capabilities = [
  { title: '五因子智能匹配', desc: '权重后台可配置', icon: 'MagicStick' },
  { title: '批次溯源存证', desc: '二维码 + SHA-256', icon: 'Search' },
  { title: '生鲜损耗结算', desc: '部分收货自动扣款', icon: 'Money' },
  { title: '食安阈值预警', desc: '证照 / 价格 / 逾期', icon: 'Warning' }
]

const roleOptions = [
  { code: 'ADMIN', label: '管理员', icon: 'UserFilled' },
  { code: 'FARMER', label: '农户', icon: 'Apple' },
  { code: 'PURCHASER', label: '采购员', icon: 'ShoppingCart' },
  { code: 'INSPECTOR', label: '质检员', icon: 'Van' }
]

// 角色选择真实参与登录校验：选中后 expectedRole 随请求提交，服务端比对账号实际角色；
// 再次点击已选角色可取消选择（回到自动识别）。前端不缓存任何演示账号。
const pickRole = (r) => {
  form.role = form.role === r.code ? '' : r.code
}

const forgot = () => {
  ElMessage.info('请联系系统管理员重置密码')
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
      remember: form.remember,
      expectedRole: form.role || undefined
    })
    ElMessage.success('欢迎回来，' + data.user.realName)
    // 「记住账号」只记账号名，不把令牌写进 localStorage——
    // 令牌是标签页级会话，写进共享存储会让多个角色的身份互相覆盖。
    if (form.remember) localStorage.setItem('ct_last_username', form.username.trim())
    else localStorage.removeItem('ct_last_username')
    /* 真·首次登录标记：只有从登录页进来的会话才自动打开「怎么用」引导。
       验收脚本/直接带 token 进入的调试不会被打扰，也不会挡住页面点击。 */
    sessionStorage.setItem('ct_guide_fresh', '1')
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
/* ==========================================================================
   登录页（2026-11-10 二次收敛）
   与全站共用同一套设计系统：同一字体栈、同一圆角、同一组件语言。
   左侧摄影是固定的**内容资产**，不随明度模式切换；右侧表单用设计 token
   跟随 day/paper/night，因此登录 → 业务页不会再像换了一个系统。
   template 与 script 完全保留（含空表单、角色按钮只改视觉、密码显隐）。
   ========================================================================== */
.login-page {
  display: grid;
  grid-template-columns: 1.05fr .95fr;
  min-height: 100vh;
  background: var(--surface);
  color: var(--ink);
  font-family: var(--font-body);
}

/* ---------------- 左：纪实摄影品牌区 ---------------- */
.brand-side {
  position: relative;
  min-height: 100vh;
  padding: 54px 56px 38px;
  color: #fff;
  overflow: hidden;
  background-color: #1B3A2E;
  background-image: var(--hero);
  background-size: cover;
  background-position: center;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
}
.brand-side::after {
  content: '';
  position: absolute;
  inset: 0;
  z-index: 1;
  background: linear-gradient(100deg, rgba(16, 40, 31, .72), rgba(16, 40, 31, .86));
}
/* 旧版无信息装饰（光斑 / 叶片）全部退场 */
.blob, .leaf { display: none; }

.brand-inner { position: relative; z-index: 2; max-width: 600px; }
.brand-tag {
  display: inline-flex; align-items: center; gap: 8px;
  padding: 6px 0; border: 0; border-bottom: 1px solid rgba(255, 255, 255, .55);
  border-radius: 0; background: transparent;
  font-size: var(--fs-xs); letter-spacing: .22em; color: #F3EDE0;
  text-transform: uppercase;
}
.brand-title {
  margin: 40px 0 14px;
  font-family: var(--font-display);
  font-size: clamp(30px, 3.3vw, 44px);
  line-height: 1.3; font-weight: 700; letter-spacing: -.01em;
  text-shadow: 0 2px 16px rgba(0, 0, 0, .32);
}
.brand-en {
  font-family: var(--font-num);
  font-size: var(--fs-xs); letter-spacing: .2em; color: rgba(243, 237, 224, .76);
}
.brand-desc {
  margin: 30px 0 36px; max-width: 540px;
  font-size: var(--fs-base); line-height: 2.05; color: rgba(250, 246, 237, .88);
}
.cap-grid {
  display: grid; grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px; max-width: 540px;
}
.cap-card {
  display: flex; align-items: center; gap: 12px;
  padding: 14px 15px; border-radius: var(--radius);
  background: rgba(246, 242, 233, .09);
  border: 1px solid rgba(246, 242, 233, .24);
  backdrop-filter: none;
}
.cap-icon {
  width: 32px; height: 32px; border-radius: var(--radius);
  display: grid; place-items: center;
  background: rgba(246, 242, 233, .14); color: #F3EDE0;
  font-size: var(--fs-md); flex-shrink: 0;
}
.cap-title { font-size: var(--fs-base); font-weight: 700; color: #fff; font-family: var(--font-display); }
.cap-sub { font-size: var(--fs-xs); color: rgba(243, 237, 224, .7); margin-top: 3px; }
.brand-foot {
  position: relative; z-index: 2;
  font-size: var(--fs-xs); color: rgba(243, 237, 224, .6); letter-spacing: .04em;
}

/* ---------------- 右：纸色表单 ---------------- */
.form-side {
  display: flex; align-items: center; justify-content: center;
  padding: 40px 44px; background: var(--surface);
}
.login-card {
  width: 100%; max-width: 372px;
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--radius-lg);
  padding: 8px 4px 18px;
  box-shadow: var(--shadow);
}
/* 全局 .card-head 是 flex（供业务卡使用），登录页标题与副标题必须回到块级 */
.card-head { display: block; }
.card-head h2 {
  margin: 0; font-family: var(--font-display);
  font-size: var(--fs-xl); font-weight: 700; color: var(--ink); letter-spacing: -.01em;
}
.card-head p { margin: 9px 0 26px; font-size: var(--fs-sm); color: var(--muted); line-height: 1.7; }

/* 角色选择沿用全站 chip 组件语言（圆角 + 边框 + 软底），不再有方案专属形态 */
.role-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 6px; margin-bottom: 22px; }
.role-item {
  display: flex; flex-direction: column; align-items: center; gap: 7px;
  padding: 11px 3px 10px; border-radius: var(--radius);
  border: 1px solid var(--line);
  background: var(--surface); color: var(--muted);
  font-size: var(--fs-xs); cursor: pointer;
  transition: color .18s, border-color .18s, background .18s;
}
.role-item:hover { color: var(--accent); border-color: var(--accent); background: var(--accent-soft); }
.role-item.active {
  border-color: var(--accent); background: var(--accent-soft);
  color: var(--accent); font-weight: 700;
}
.role-icon { font-size: var(--fs-md); }

.login-form :deep(.el-input__wrapper) {
  height: 43px; border-radius: var(--radius); font-size: var(--fs-base);
  box-shadow: 0 0 0 1px var(--line-strong) inset !important;
  background: var(--bg) !important;
}
.login-form :deep(.el-input__wrapper.is-focus) {
  box-shadow: 0 0 0 3px var(--accent-soft), 0 0 0 1px var(--accent) inset !important;
}
.field-label { display: block; font-size: var(--fs-xs); font-weight: 700; color: var(--ink-2); margin: 0 0 7px; }
.login-form :deep(.el-input) { margin-bottom: 15px; }
.form-row { display: flex; align-items: center; justify-content: space-between; margin: 2px 0 20px; }
.form-row :deep(.el-checkbox__label) { font-size: var(--fs-xs); color: var(--muted); }
.forgot { font-size: var(--fs-xs); color: var(--accent); cursor: pointer; }
.forgot:hover { text-decoration: underline; }
.submit {
  width: 100%; height: 43px; font-size: var(--fs-base); font-weight: 700;
  letter-spacing: .14em; border-radius: var(--radius);
  box-shadow: none; background: var(--accent); border-color: var(--accent);
  color: var(--accent-ink);
}
.submit:hover { filter: brightness(1.1); }
.hint-row {
  display: flex; flex-direction: column; gap: 7px;
  margin-top: 18px; padding: 11px 13px; border-radius: var(--radius);
  background: var(--surface-2); border: 1px dashed var(--line-strong);
  font-size: var(--fs-xs); color: var(--muted); line-height: 1.7;
}
.lock-hint { display: inline-flex; align-items: center; gap: 4px; color: var(--warn); }
.card-foot {
  display: flex; justify-content: space-between;
  margin-top: 18px; padding-top: 13px; border-top: 1px solid var(--line);
  font-size: var(--fs-2xs); color: var(--muted); font-family: var(--font-num);
}

/* ---------------- 外观模式只影响右侧表单（走设计 token） ----------------
   左侧摄影区是固定内容资产：三种明度下都用同一张图、同一遮罩、同一白字，
   因此登录页与业务页不会因切换模式或跳转而变成「另一套系统」。 */

@media (max-width: 1080px) {
  .login-page { grid-template-columns: 1fr; }
  .brand-side { display: none; }
}
</style>
