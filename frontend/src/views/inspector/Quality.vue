<template>
  <div class="page" v-loading="loading">
    <div class="page-head">
      <div>
        <h2 class="page-title">质检录入</h2>
        <p class="page-sub">录入批次抽检结果，合格后自动生成溯源二维码与存证哈希</p>
      </div>
      <div class="page-actions">
        <!-- 待检队列：质检员经常要"先检刚到的那批"，不能只由系统指定一单 -->
        <el-select
          v-if="pendingList.length > 1" v-model="currentOrderId"
          class="queue-select" placeholder="切换待检批次" @change="switchOrder"
        >
          <el-option
            v-for="t in pendingList" :key="t.id" :value="t.id"
            :label="`${t.orderNo} · ${t.productName} · ${t.farmerName}`"
          />
          <template #prefix><el-icon><List /></el-icon></template>
        </el-select>
        <span v-else-if="pendingList.length === 1" class="queue-single">
          待检 1 批 · {{ pendingList[0].orderNo }}
        </span>
        <el-button plain @click="printReport"><el-icon><Download /></el-icon>导出报告</el-button>
      </div>
    </div>

    <div v-if="!order" class="card card-pad">
      <div class="empty">
        <el-icon style="font-size: var(--fs-display); color: #b7c7c1"><DocumentChecked /></el-icon>
        <div style="margin-top: 8px">暂无待质检订单，请先在「我的任务」中等待农户发货</div>
        <div class="muted small" style="margin-top: 6px">
          当前待质检任务 {{ pendingCount }} 笔
        </div>
      </div>
    </div>

    <template v-else>
      <!-- 顶部信息条 -->
      <div class="topbar-card card">
        <div class="tb-left">
          <span class="tb-badge">批次质检单</span>
          <span class="mono tb-no">{{ batchNo }}</span>
        </div>
        <div class="tb-right">
          <span>检测员：{{ store.user?.realName }}</span>
          <span class="dot">·</span>
          <span>{{ nowText }}</span>
        </div>
      </div>

      <div class="grid-q">
        <!-- 左：批次质检单 -->
        <div class="card">
          <div class="card-head">
            <div class="card-title">批次质检单</div>
            <div class="card-extra">订单 {{ order.orderNo }}</div>
          </div>
          <div class="card-pad">
            <div class="sub-block-title">样品信息</div>
            <div class="sample-grid">
              <div class="si"><span class="k">批次号</span><span class="v mono">{{ batchNo }}</span></div>
              <div class="si"><span class="k">产品</span><span class="v">{{ order.productName }}</span></div>
              <div class="si"><span class="k">供货农户</span><span class="v">{{ order.farmerName }}</span></div>
              <div class="si">
                <span class="k">抽样数量</span>
                <span class="v">
                  <el-input-number
                    v-model="sampleQty" :min="0.01" :max="maxSampleQty" :step="0.1"
                    :precision="2" :controls="false" size="small" class="sample-input"
                  />
                  kg
                  <em class="spec-tip">规格 {{ fmtQty(sampleSpec) }} kg/批</em>
                </span>
              </div>
              <div class="si"><span class="k">收货食堂</span><span class="v">{{ order.canteenName }}</span></div>
              <div class="si"><span class="k">订单数量</span><span class="v">{{ fmtQty(order.quantity) }} {{ order.unit }}</span></div>
            </div>

            <div class="sub-block-title with-live" style="margin-top: 18px">
              <span class="sbt-main">
                检测项目
                <span class="sub-note">
                  {{ categoryName || '通用' }}标准 · {{ items.length }} 项
                  <template v-if="standardFallback">（该品类未配标准，暂用叶菜类通用项）</template>
                </span>
              </span>
              <!-- 实时判定徽标：结论区在表格下方，录数据时看不到整体判断，
                   这里跟着表格标题显示已录进度和当前结论，不用滚动就能知道有没有超标 -->
              <span class="q-live" :class="liveState.cls">
                <el-icon><component :is="liveState.icon" /></el-icon>
                {{ liveState.text }}
              </span>
            </div>
            <div class="test-list">
              <div class="test-head">
                <span>检测项</span><span>方法与依据</span><span>实测值</span><span>判定</span>
              </div>
              <div
                v-for="it in items" :key="it.itemName"
                class="test-row" :class="{ 'is-bad': isBad(it) }"
              >
                <div class="tr-name">
                  {{ it.itemName }}
                  <em v-if="it.required" class="req">*</em>
                </div>
                <div class="tr-std">
                  {{ it.method || '—' }}
                  <span class="tr-limit">{{ limitHint(it) }}</span>
                </div>
                <div class="tr-input">
                  <el-input
                    v-if="it.judgeType === 'range'"
                    v-model="it.input" size="small" class="tr-num"
                    :placeholder="it.unit ? `实测值（${it.unit}），可填未检出` : '填入实测值'"
                    @input="recalc"
                  >
                    <template v-if="it.unit" #append>{{ it.unit }}</template>
                  </el-input>
                  <el-radio-group
                    v-else-if="it.judgeType === 'pass'"
                    v-model="it.input" size="small" @change="recalc"
                  >
                    <el-radio-button value="合格">合格</el-radio-button>
                    <el-radio-button value="不合格">不合格</el-radio-button>
                  </el-radio-group>
                  <el-radio-group v-else v-model="it.input" size="small" @change="recalc">
                    <el-radio-button value="相符">相符</el-radio-button>
                    <el-radio-button value="不符">不符</el-radio-button>
                  </el-radio-group>
                </div>
                <div class="tr-verdict">
                  <span v-if="!it.input" class="muted small">待录入</span>
                  <span v-else-if="isBad(it)" class="verdict-bad">{{ verdictText(it) }}</span>
                  <span v-else class="verdict-ok">{{ verdictText(it) }}</span>
                </div>
              </div>
            </div>

            <div class="sub-block-title" style="margin-top: 18px">检测结论（按标准自动判定）</div>
            <!-- 结论不再由人工点选：原来那两个大按钮点一下就能放行，是「随便点点」的根源。
                 现在按 quality_standard 的限值实时算出，不合格项会逐条列出。 -->
            <div class="verdict-box" :class="autoResult === '合格' ? 'is-ok' : 'is-no'">
              <el-icon class="vb-icon">
                <CircleCheckFilled v-if="autoResult === '合格'" />
                <CircleCloseFilled v-else />
              </el-icon>
              <div class="grow">
                <div class="vb-title">
                  {{ autoResult === '合格' ? '质检合格' : '不合格' }}
                  <span class="vb-tag">{{ filledCount }}/{{ items.length }} 项已录入</span>
                </div>
                <div class="vb-desc">{{ verdictDesc }}</div>
              </div>
            </div>

            <div class="sub-block-title" style="margin-top: 18px">检测备注</div>
            <el-input v-model="remark" type="textarea" :rows="3" maxlength="200" show-word-limit />

            <div class="sub-block-title" style="margin-top: 18px">
              检测报告 / 现场照片（已上传 {{ photos.length }} 张）
            </div>
            <div class="photo-row">
              <div v-for="(p, i) in photos" :key="p.url || p.name" class="photo-ph has-photo"
                   title="点击移除" @click="removePhoto(i)">
                <img v-if="p.preview" :src="p.preview" :alt="p.name" />
                <div v-else class="photo-done">
                  <el-icon><DocumentChecked /></el-icon>
                  <em>已上传</em>
                </div>
                <span>{{ p.name }}</span>
              </div>
              <div class="photo-add" @click="uploadTip">
                <el-icon><Plus /></el-icon>
                <span>上传照片</span>
              </div>
            </div>

            <div class="form-actions">
              <el-button plain @click="draft">暂存</el-button>
              <el-button type="primary" :loading="submitting" @click="submit">
                提交质检
              </el-button>
              <el-button v-if="qrCode && autoResult === '合格'" plain @click="goDelivery">
                前往配送任务<el-icon><ArrowRight /></el-icon>
              </el-button>
            </div>
          </div>
        </div>

        <!-- 右：二维码 / 标准 / 警示 -->
        <div class="side-col">
          <div class="card">
            <div class="card-head">
              <div class="card-title">溯源批次码预览</div>
              <div class="card-extra">
                <span class="tag tag-green" v-if="qrCode">已生成</span>
                <span v-else>合格后生成</span>
              </div>
            </div>
            <div class="card-pad qr-wrap">
              <div class="qr-box">
                <img v-if="qrCode" :src="qrCode" class="qr-img" alt="溯源二维码" />
                <div v-else class="qr-ph">
                  <el-icon><Grid /></el-icon>
                  <span>合格后生成</span>
                </div>
              </div>
              <div class="qr-batch mono">{{ batchNo }}</div>
              <div class="qr-tip">扫码查看：产地 → 质检 → 配送 → 入库<br />全链路溯源信息</div>
            </div>
          </div>

          <div class="card">
            <div class="card-head">
              <div class="card-title">判定依据</div>
              <div class="card-extra">{{ categoryName || '通用' }}标准</div>
            </div>
            <div class="card-pad">
              <div class="std-grid">
                <div class="std-item">
                  <span class="std-name">检测项</span>
                  <span class="std-val">{{ items.length }} 项 · 必检 {{ requiredCount }}</span>
                </div>
                <div class="std-item">
                  <span class="std-name">样本规格</span>
                  <span class="std-val">{{ fmtQty(sampleSpec) }} kg / 批</span>
                </div>
                <div class="std-item">
                  <span class="std-name">判定规则</span>
                  <span class="std-val">任一项超限即整批不合格</span>
                </div>
                <div class="std-item">
                  <span class="std-name">标准来源</span>
                  <span class="std-val">分品类质检标准库</span>
                </div>
              </div>
            </div>
          </div>

          <div class="warn-card">
            <el-icon><WarningFilled /></el-icon>
            <div>
              任一项不合格即判定整批不通过，需现场拍照取证并上报，系统自动扣减农户信用
              <b>-5 分</b>。
            </div>
          </div>
        </div>
      </div>
    </template>
  </div>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { commonApi, inspectorApi } from '@/api'
import { useUserStore } from '@/store/user'
import { fmtNum, fmtQty } from '@/utils/format'
import { useAutoRefresh } from '@/utils/useAutoRefresh'
import { exportTextReport, stamped } from '@/utils/export'

const route = useRoute()
const router = useRouter()
const store = useUserStore()

const loading = ref(false)
const submitting = ref(false)
const order = ref(null)
const batchNo = ref('')
const sampleQty = ref(2)
const sampleSpec = ref(0.5)
const maxSampleQty = ref(999)
const items = ref([])
const categoryName = ref('')
const standardFallback = ref(false)
const remark = ref('')
const qrCode = ref('')
const pendingCount = ref(0)
const pendingList = ref([])
const currentOrderId = ref(null)
const photos = ref([])

/* ---------------- 检测项判定（与后端同一套规则，前端实时预演） ---------------- */
const numOf = (v) => {
  const n = Number(String(v ?? '').replace(/,/g, '').trim())
  return Number.isFinite(n) ? n : null
}
/** 单项判定：返回 { ok, text }；未填返回 null */
const judge = (it) => {
  const input = String(it.input ?? '').trim()
  if (!input) return null
  if (it.judgeType === 'pass') {
    const ok = ['合格', '达标', '是', '1', 'true'].includes(input.toLowerCase())
    return { ok, text: ok ? '感官合格' : '感官不合格' }
  }
  if (it.judgeType === 'equal') {
    const ok = ['相符', '一致', '是', '1', 'true'].includes(input.toLowerCase())
    return { ok, text: ok ? '与随货单相符' : '与随货单不符' }
  }
  // 「未检出 / ND / ＜0.01」是检测报告的标准写法，等于低于检出限，按合格处理
  const lower = input.toLowerCase()
  if (lower === '未检出' || lower === 'nd' || lower === 'n.d.'
    || input.startsWith('<') || input.startsWith('＜') || input.startsWith('≤')) {
    return { ok: true, text: '未检出 / 低于检出限' }
  }
  const v = numOf(input)
  if (v === null) return { ok: false, text: '不是有效数值' }
  const min = it.minValue == null ? null : Number(it.minValue)
  const max = it.maxValue == null ? null : Number(it.maxValue)
  if (min !== null && v < min) return { ok: false, text: `低于下限 ${min}` }
  if (max !== null && v > max) return { ok: false, text: `高于上限 ${max}` }
  return { ok: true, text: '在范围内' }
}
const isBad = (it) => judge(it)?.ok === false
const verdictText = (it) => judge(it)?.text || ''
const limitHint = (it) => {
  const unit = it.unit || ''
  if (it.judgeType === 'pass') return '感官合格'
  if (it.judgeType === 'equal') return '需与随货单相符'
  if (it.minValue != null && it.maxValue != null) return `标准 ${it.minValue} ~ ${it.maxValue}${unit}`
  if (it.maxValue != null) return `标准 ≤ ${it.maxValue}${unit}`
  if (it.minValue != null) return `标准 ≥ ${it.minValue}${unit}`
  return '不限'
}
/** 改了实测值就重算，结论区随之变化 */
const recalc = () => { items.value = [...items.value] }

const filledCount = computed(() => items.value.filter((it) => String(it.input ?? '').trim()).length)
const requiredCount = computed(() => items.value.filter((it) => it.required).length)
const failList = computed(() => items.value.filter(isBad))
const autoResult = computed(() => (failList.value.length ? '不合格' : '合格'))
const verdictDesc = computed(() => {
  const unfilled = items.value.length - filledCount.value
  if (!items.value.length) return '该品类还没有配置检测标准，无法录入质检'
  if (failList.value.length) {
    return `${failList.value.length} 项超出标准：` +
      failList.value.map((it) => `${it.itemName}（${it.input}${it.unit || ''}，${limitHint(it)}）`).join('；')
  }
  if (unfilled > 0) return `还有 ${unfilled} 项未录入实测值，录完才能提交`
  return '全部检测项均在标准范围内，可生成批次二维码并放行配送'
})

/** 标题行的实时状态：录用进度 + 是否已经触到不合格 */
const liveState = computed(() => {
  const total = items.value.length
  const filled = filledCount.value
  if (!total) return { cls: 'is-pending', icon: 'Warning', text: '未配检测标准' }
  if (failList.value.length) {
    return { cls: 'is-no', icon: 'CircleCloseFilled', text: `不合格 · ${failList.value.length} 项超限` }
  }
  if (filled < total) {
    return { cls: 'is-pending', icon: 'EditPen', text: `待录入 ${total - filled} 项` }
  }
  return { cls: 'is-ok', icon: 'CircleCheckFilled', text: '全部达标' }
})

const p = (n) => String(n).padStart(2, '0')
const nowText = computed(() => {
  const d = new Date()
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
})

const loadByOrderId = async (orderId) => {
  if (!orderId) return
  loading.value = true
  try {
    const res = await inspectorApi.qualityDetail(orderId)
    const d = res.data || {}
    order.value = d.order
    batchNo.value = d.batchNo
    sampleQty.value = Number(d.sampleQty ?? 0.5)
    sampleSpec.value = Number(d.sampleSpec ?? 0.5)
    maxSampleQty.value = Number(d.maxSampleQty ?? d.order?.quantity ?? 999)
    categoryName.value = d.categoryName || ''
    standardFallback.value = !!d.standardFallback
    // 检测项来自该品类的质量标准；input 一律清空，逼着质检员逐项实测
    items.value = (d.items || []).map((x) => ({ ...x, input: '', note: '' }))
    // 已检订单：把上次的实测值回填出来，便于核对，但不允许重复提交
    if (d.report && d.report.items) {
      try {
        const prev = JSON.parse(d.report.items)
        if (Array.isArray(prev)) {
          prev.forEach((p) => {
            const hit = items.value.find((x) => x.itemName === p.itemName)
            if (hit && p.input != null) hit.input = String(p.input)
          })
        }
      } catch (e) { /* 旧报告格式不兼容时忽略 */ }
      if (d.report.remark) remark.value = d.report.remark
    }
  } catch (e) {
    order.value = null
    ElMessage.warning('该订单当前不可录入质检（仅待质检订单可录入）')
  } finally {
    loading.value = false
  }
}

const boot = async () => {
  const qid = route.query.orderId
  // 待办「去质检」带的是 ?focus=<订单号>（业务编号），老入口可能给 ?orderId=<主键>，两种都认
  const qno = String(route.query.focus || '').trim()
  loading.value = true
  try {
    const res = await inspectorApi.tasks({ page: 1, size: 20 })
    const records = res.data?.records || []
    // 待检队列：只保留待质检(status=30)，按到货先后倒序——最新发货的排最前
    const queue = records
      .filter((r) => r.status === 30)
      .sort((a, b) => Number(b.id) - Number(a.id))
    pendingList.value = queue
    pendingCount.value = queue.length || res.data?.stats?.pending || 0

    // 已选中的那一单只要还在队列里就保持不动：实时刷新（别人操作触发）不能把
    // 用户正在录入的质检单切走，那是比"不刷新"严重得多的打断。
    const kept = currentOrderId.value
      ? queue.find((r) => String(r.id) === String(currentOrderId.value))
      : null
    const byNo = qno ? queue.find((r) => String(r.orderNo || '').includes(qno)) : null
    const byId = qid ? queue.find((r) => String(r.id) === String(qid)) : null
    const target = byNo || byId || kept || queue[0]

    if (target) {
      const same = order.value && String(order.value.id) === String(target.id)
      currentOrderId.value = target.id
      if (!same) await loadByOrderId(target.id)
    } else if (qid) {
      // 从「我的任务」点进来但该单已不在队列（可能已被检）
      currentOrderId.value = qid
      await loadByOrderId(qid)
    } else {
      order.value = null
    }
  } finally {
    loading.value = false
  }
  restoreDraft()
}

/** 在待检队列里换一单：地址栏同步，刷新后仍停在这单 */
const switchOrder = async (id) => {
  if (!id || String(id) === String(order.value?.id)) return
  // 有未保存的实测值时才需要确认，避免空表单也弹窗
  if (filledCount.value > 0 || remark.value) {
    try {
      await ElMessageBox.confirm('切换批次会放弃当前未提交的结论与备注，确定切换？', '切换待检批次', {
        confirmButtonText: '切换', cancelButtonText: '留下', type: 'warning'
      })
    } catch {
      // 取消：把下拉退回原来那单
      currentOrderId.value = order.value?.id ?? null
      return
    }
  }
  router.replace({ path: '/inspector/quality', query: { orderId: id } })
  await loadByOrderId(id)
}

/* 现场照片：真实上传到服务端，提交时带服务端地址做存证；
   本地 blob 只用于当场预览（下载接口要登录态，<img> 直连会 401） */
const uploading = ref(false)
const uploadTip = () => {
  const input = document.createElement('input')
  input.type = 'file'
  input.accept = 'image/*'
  input.multiple = true
  input.onchange = async () => {
    const files = Array.from(input.files || [])
    if (!files.length) return
    uploading.value = true
    try {
      for (const f of files) {
        const res = await commonApi.upload(f, 'quality')
        photos.value.push({
          name: res.data?.name || f.name,
          url: res.data?.url || '',
          preview: URL.createObjectURL(f)
        })
      }
      ElMessage.success(`已上传 ${files.length} 张现场照片`)
    } catch (e) {
      ElMessage.error('照片上传失败：' + (e?.message || '请重试'))
    } finally {
      uploading.value = false
    }
  }
  input.click()
}
const removePhoto = (i) => {
  const p = photos.value[i]
  if (p?.preview) URL.revokeObjectURL(p.preview)
  photos.value.splice(i, 1)
}

/* 草稿：真实写入 localStorage，刷新后自动恢复，提交成功后清除 */
const draftKey = () => 'ct_quality_draft_' + (order.value?.id || 'none')
const draft = () => {
  localStorage.setItem(draftKey(), JSON.stringify({
    batchNo: batchNo.value, sampleQty: sampleQty.value,
    items: items.value, remark: remark.value,
    photoNames: photos.value.map((p) => p.name), at: Date.now()
  }))
  ElMessage.success('草稿已暂存到本机，刷新页面后会自动恢复')
}
let draftRestored = false
const restoreDraft = () => {
  if (draftRestored) return   // 实时刷新会再次调用 boot，不能覆盖用户当前填写
  draftRestored = true
  try {
    const d = JSON.parse(localStorage.getItem(draftKey()) || 'null')
    if (!d) return
    if (d.batchNo) batchNo.value = d.batchNo
    if (d.sampleQty) sampleQty.value = d.sampleQty
    if (Array.isArray(d.items) && d.items.length) {
      // 只回填同一个检测项，防止标准更新后串项
      d.items.forEach((p) => {
        const hit = items.value.find((x) => x.itemName === p.itemName)
        if (hit) hit.input = p.input || ''
      })
    }
    if (d.remark) remark.value = d.remark
    ElMessage.info('已恢复上次暂存的质检草稿')
  } catch (e) { /* 草稿损坏不影响正常填写 */ }
}

const submit = async () => {
  if (!items.value.length) {
    ElMessage.warning('该品类未配置质检标准，无法提交，请联系管理员补充')
    return
  }
  const missing = items.value.filter((it) => it.required && !String(it.input ?? '').trim())
  if (missing.length) {
    ElMessage.warning(`还有 ${missing.length} 项必检项没录实测值：${missing.map((x) => x.itemName).join('、')}`)
    return
  }
  const urls = photos.value.map((p) => p.url).filter(Boolean)
  if (autoResult.value === '不合格') {
    if (remark.value.trim().length < 10) {
      ElMessage.warning('判定不合格必须填写不少于 10 字的处置说明（问题描述与处理方式）')
      return
    }
    if (!urls.length) {
      ElMessage.warning('判定不合格必须至少上传一张现场照片作为存证')
      return
    }
    try {
      await ElMessageBox.confirm(
        `检测数据判定为不合格（${failList.value.map((x) => x.itemName).join('、')}），` +
        '提交后整批驳回、扣减农户信用 -5，且不可撤销。确认提交？',
        '提交不合格结论',
        { type: 'warning', confirmButtonText: '确认驳回', cancelButtonText: '返回核对' }
      )
    } catch (e) {
      return
    }
  }
  submitting.value = true
  try {
    const res = await inspectorApi.submitQuality({
      orderId: order.value.id,
      batchNo: batchNo.value,
      sampleQty: sampleQty.value,
      result: autoResult.value,
      items: items.value.map((it) => ({
        itemName: it.itemName,
        input: String(it.input ?? '').trim(),
        note: it.note || ''
      })),
      remark: remark.value,
      photos: urls
    })
    const d = res.data || {}
    if (d.qrCode) qrCode.value = d.qrCode
    localStorage.removeItem(draftKey())
    ElMessage.success(
      autoResult.value === '合格'
        ? '质检通过，已生成溯源二维码并放行配送'
        : `已判定不合格（${d.failCount || failList.value.length} 项超限），批次驳回并扣减农户信用`
    )
    order.value.status = autoResult.value === '合格' ? 40 : 41
  } finally {
    submitting.value = false
  }
}

/* 质检合格后去发车：带上刚刚这一单的订单号，落地页直接定位到它，
   不用在配送任务里再找一遍（"跳过去自己找"是今天反馈里最集中的一条） */
const goDelivery = () => {
  const no = order.value?.orderNo
  router.push(no ? '/inspector/delivery?focus=' + encodeURIComponent(no) : '/inspector/delivery')
}

const printReport = () => {
  if (!order.value) {
    ElMessage.warning('暂无可导出的质检单')
    return
  }
  const lines = [
    '关联订单：' + (order.value.orderNo || order.value.id || '—'),
    '批次号：' + (batchNo.value || '—'),
    '产品 / 品类：' + (order.value.productName || '—') + ' / ' + (categoryName.value || '通用'),
    '抽样数量：' + sampleQty.value + ' kg（规格 ' + fmtQty(sampleSpec.value) + ' kg/批）',
    '质检结论：' + autoResult.value,
    '检测备注：' + (remark.value || '—'),
    '',
    '— 检测项目（标准 / 实测 / 判定）—',
    ...items.value.map((it) => {
      const j = judge(it)
      return `  ${it.itemName}：${limitHint(it)} | 实测 ${it.input || '未填'}${it.unit || ''} | ` +
        (j ? (j.ok ? '合格' : '不合格') : '未录入')
    }),
    '',
    '现场照片：' + (photos.value.length ? photos.value.map((p) => p.name).join('、') : '无'),
    '质检员：' + (store.displayName || '—'),
    '生成时间：' + nowText.value
  ]
  exportTextReport(stamped('质检报告_' + (batchNo.value || 'order'), 'txt'), '生鲜质检报告', lines)
  ElMessage.success('质检报告已下载：' + (batchNo.value || ''))
}

watch(() => route.query.orderId, (v) => {
  if (v) loadByOrderId(v)
})

watch(() => route.query.focus, (v) => {
  if (v) boot()
})

useAutoRefresh(boot)
onMounted(boot)
</script>

<style scoped>
.topbar-card {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 18px;
  margin-bottom: 14px;
}

.tb-left {
  display: flex;
  align-items: center;
  gap: 10px;
}

.tb-badge {
  font-size: var(--fs-sm);
  font-weight: 600;
  color: var(--accent);
  background: var(--accent-soft);
  padding: 3px 9px;
  border-radius: 6px;
}

.tb-no {
  font-size: var(--fs-sm);
  color: var(--ink);
  font-weight: 600;
}

.tb-right {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: var(--fs-sm);
  color: var(--c-muted);
}

.dot {
  color: var(--c-muted-2);
}

.grid-q {
  display: grid;
  grid-template-columns: minmax(0, 1.28fr) minmax(0, 1fr);
  gap: 14px;
  align-items: start;
}

.side-col {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.sub-block-title {
  font-size: var(--fs-base);
  font-weight: 600;
  color: var(--ink);
  margin-bottom: 10px;
  padding-left: 8px;
  border-left: 3px solid var(--c-primary);
  line-height: 1.2;
}

/* 带实时徽标的标题行：标题在左、判定状态贴右 */
.sub-block-title.with-live {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  flex-wrap: wrap;
  min-height: 26px;
}

.sbt-main {
  display: inline-flex;
  align-items: baseline;
  gap: 8px;
  flex-wrap: wrap;
}

.q-live {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 3px 10px;
  border-radius: 999px;
  border: 1px solid var(--line);
  font-size: var(--fs-xs);
  font-weight: 700;
  line-height: 1.5;
  white-space: nowrap;
  background: var(--surface-2);
  color: var(--muted);
}

.q-live.is-pending { color: var(--ink-2); background: var(--surface-2); border-color: var(--line); }

.q-live.is-ok {
  color: var(--accent);
  border-color: var(--accent);
  background: color-mix(in srgb, var(--accent) 9%, transparent);
}

.q-live.is-no {
  color: var(--danger);
  border-color: var(--danger);
  background: color-mix(in srgb, var(--danger) 9%, transparent);
}

.sample-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px 18px;
}

.si {
  display: flex;
  gap: 10px;
  font-size: var(--fs-sm);
}

.si .k {
  color: var(--c-muted);
  width: 60px;
  flex-shrink: 0;
}

.si .v {
  color: var(--ink);
  font-weight: 500;
  word-break: break-all;
}

.test-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

/* 四列：检测项 / 方法与限值 / 实测值 / 判定 */
.test-head {
  display: grid;
  grid-template-columns: 108px minmax(0, 1fr) 190px 132px;
  gap: 10px;
  padding: 0 12px;
  font-size: var(--fs-xs);
  color: var(--muted);
}

.test-row {
  display: grid;
  grid-template-columns: 108px minmax(0, 1fr) 190px 132px;
  align-items: center;
  gap: 10px;
  padding: 9px 12px;
  border-radius: 9px;
  background: var(--surface-2);
  border: 1px solid var(--line);
}

/* 超限项：整行描红，一眼能看出是哪一项拖垮了结论 */
.test-row.is-bad {
  border-color: var(--danger);
  background: color-mix(in srgb, var(--danger) 7%, transparent);
}

.tr-name {
  font-size: var(--fs-base);
  font-weight: 600;
  color: var(--ink);
}

.tr-name .req {
  color: var(--danger);
  font-style: normal;
  margin-left: 2px;
}

.tr-std {
  font-size: var(--fs-xs);
  color: var(--muted);
  line-height: 1.45;
}

.tr-limit {
  display: block;
  margin-top: 2px;
  color: var(--ink-2);
  font-weight: 600;
}

.tr-input :deep(.el-radio-button__inner) {
  padding: 5px 9px;
  font-size: var(--fs-xs);
}

.tr-num { width: 100%; }

.tr-verdict {
  font-size: var(--fs-xs);
  text-align: right;
}

.verdict-ok { color: var(--ok); font-weight: 600; }
.verdict-bad { color: var(--danger); font-weight: 600; }

.sub-note {
  margin-left: 8px;
  font-size: var(--fs-xs);
  font-weight: 400;
  color: var(--muted);
}

.sample-input { width: 74px; margin-right: 4px; }

.spec-tip {
  margin-left: 6px;
  font-style: normal;
  font-size: var(--fs-xs);
  color: var(--muted);
}

/* 结论区：只读展示系统判定，不再是可以随手点的两个按钮 */
.verdict-box {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 14px 16px;
  border-radius: 11px;
  border: 1px solid var(--line);
  background: var(--surface-2);
}

.verdict-box.is-ok {
  border-color: var(--accent);
  background: color-mix(in srgb, var(--accent) 8%, transparent);
}

.verdict-box.is-no {
  border-color: var(--danger);
  background: color-mix(in srgb, var(--danger) 8%, transparent);
}

.vb-icon { font-size: var(--fs-xl); }
.verdict-box.is-ok .vb-icon { color: var(--accent); }
.verdict-box.is-no .vb-icon { color: var(--danger); }

.vb-title {
  font-size: var(--fs-base);
  font-weight: 700;
  color: var(--ink);
  display: flex;
  align-items: center;
  gap: 8px;
}

.vb-tag {
  font-size: var(--fs-2xs);
  font-weight: 500;
  color: var(--muted);
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: 5px;
  padding: 1px 6px;
}

.vb-desc {
  margin-top: 3px;
  font-size: var(--fs-xs);
  color: var(--ink-2);
  line-height: 1.55;
}

.photo-done {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
  color: var(--accent);
  font-size: var(--fs-2xs);
}

.photo-done em { font-style: normal; }

.photo-row {
  display: flex;
  gap: 10px;
}

.photo-ph,
.photo-add {
  width: 84px;
  height: 66px;
  border-radius: 9px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 3px;
  font-size: var(--fs-2xs);
  border: 1px dashed var(--c-border-strong);
  background: var(--surface-2);
  color: var(--c-muted-2);
}

.photo-add {
  cursor: pointer;
  color: var(--c-primary);
  border-color: #b8e3c8;
  background: #f4fdf8;
}

.photo-ph.has-photo {
  position: relative;
  overflow: hidden;
  cursor: pointer;
  padding: 0;
}
.photo-ph.has-photo img {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.photo-ph.has-photo span {
  position: absolute;
  bottom: 0;
  left: 0;
  right: 0;
  padding: 2px 4px;
  background: rgba(8, 18, 30, .72);
  color: #fff;
  font-size: var(--fs-2xs);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.form-actions {
  display: flex;
  gap: 10px;
  margin-top: 20px;
  padding: 14px 0 12px;
  border-top: 1px solid var(--c-border);
  /* 结论选择在页面上半部分、提交按钮原本在 900px 视口之外：
     用户点了「质检合格」以为完成，实际只是选了单选。
     这里把操作条吸在视口底部，选择与提交始终同屏。
     注意：吸底条会盖住滚动中的内容，所以父容器底部要留出等高的空隙。 */
  position: sticky;
  bottom: 0;
  background: var(--surface);
  z-index: 5;
  box-shadow: 0 -8px 16px -12px rgba(16, 40, 31, 0.28);
}

.queue-select {
  width: 260px;
}

.queue-single {
  font-size: var(--fs-sm);
  color: var(--muted);
  padding: 0 4px;
}

.qr-wrap {
  text-align: center;
}

.qr-box {
  width: 158px;
  height: 158px;
  margin: 4px auto 12px;
  border-radius: 12px;
  border: 1px solid var(--c-border);
  display: grid;
  place-items: center;
  background: #fff;
  padding: 6px;
}

.qr-img {
  width: 100%;
  height: 100%;
  object-fit: contain;
  border-radius: 8px;
}

.qr-ph {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  color: var(--c-muted-2);
  font-size: var(--fs-sm);
}

.qr-ph .el-icon {
  font-size: var(--fs-display);
}

.qr-batch {
  font-size: var(--fs-sm);
  font-weight: 600;
  color: var(--ink);
  background: var(--surface-2);
  border: 1px dashed var(--accent-soft);
  border-radius: 8px;
  padding: 7px 9px;
  word-break: break-all;
}

.qr-tip {
  margin-top: 9px;
  font-size: var(--fs-xs);
  color: var(--c-muted-2);
  line-height: 1.7;
}

.std-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
}

.std-item {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 8px 10px;
  border-radius: 8px;
  background: var(--surface-2);
}

.std-name {
  font-size: var(--fs-xs);
  color: var(--c-muted);
}

.std-val {
  font-size: var(--fs-sm);
  font-weight: 600;
  color: var(--ink);
}

.warn-card {
  display: flex;
  gap: 9px;
  padding: 13px 15px;
  border-radius: 11px;
  background: #fdf6e9;
  border: 1px solid #f6e2bd;
  color: var(--warn);
  font-size: var(--fs-sm);
  line-height: 1.75;
}

.warn-card .el-icon {
  color: var(--warn);
  font-size: var(--fs-md);
  flex-shrink: 0;
  margin-top: 2px;
}
</style>
