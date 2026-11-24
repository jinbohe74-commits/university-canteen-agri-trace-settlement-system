<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2 class="page-title">采购评价</h2>
        <p class="page-sub">对已完成订单进行多维评分，评价结果纳入农户信用与智能匹配权重</p>
      </div>
      <div class="page-actions">
        <el-button plain @click="load"><el-icon><Refresh /></el-icon>刷新</el-button>
      </div>
    </div>

    <div class="eval-layout" v-loading="loading">
      <!-- 左：待评价 + 农户信用 -->
      <div class="left-col">
        <div class="card">
          <div class="card-head">
            <div class="card-title">待评价订单</div>
            <div class="card-extra"><span class="tag tag-orange">{{ pendingRows.length }} / {{ pendingTotal }}</span></div>
          </div>
          <div class="card-pad pending-list">
            <div
              v-for="o in pendingRows"
              :key="o.orderId"
              class="pending-item"
              :class="{ active: form.orderId === o.orderId }"
              @click="selectOrder(o)"
            >
              <div class="pi-main">
                <div class="pi-top">
                  <span class="mono">{{ o.orderNo }}</span>
                  <span class="muted small">{{ fmtDate(o.updateTime) }}</span>
                </div>
                <div class="pi-sub">
                  {{ o.productName }} · {{ o.farmerName }} · {{ fmtNum(o.quantity) }}{{ o.unit }} · 金额
                  ¥{{ fmtMoney(o.amount) }}
                </div>
              </div>
              <el-button
                size="small"
                :type="form.orderId === o.orderId ? 'primary' : ''"
                :plain="form.orderId === o.orderId"
                @click.stop="selectOrder(o)"
              >
                去评价
              </el-button>
            </div>
            <div v-if="!pendingRows.length" class="empty">暂无待评价订单，已完成订单均已评价</div>
          </div>
        </div>

        <div class="card">
          <div class="card-head">
            <div class="card-title">供货农户信用</div>
            <div class="card-extra">最近交易农户</div>
          </div>
          <div class="card-pad">
            <template v-if="farmer">
              <div class="fc-head">
                <div class="fc-info">
                  <div class="fc-name">{{ farmer.name }}</div>
                  <div class="fc-meta">
                    <span class="tag tag-gray">{{ farmer.type }}</span>
                    <span>累计交易 {{ fmtNum(farmer.dealCount || 0) }} 次 · 好评率 {{ fmtNum(farmer.goodRate, 1) }}%</span>
                  </div>
                </div>
                <div class="fc-score">
                  <span class="fs-num">{{ fmtNum(farmer.creditScore, 0) }}</span>
                  <span class="fs-label">信用分</span>
                </div>
              </div>
              <div class="fc-dims">
                <div v-for="d in farmer.dimensions || []" :key="d.name" class="fc-dim">
                  <span class="fd-name">{{ d.name }}</span>
                  <el-progress
                    class="grow"
                    :percentage="Number(d.score || 0)"
                    :stroke-width="6"
                    :show-text="false"
                    color="#16a34a"
                  />
                  <span class="fd-val">{{ fmtNum(d.score, 0) }}</span>
                </div>
                <div v-if="!(farmer.dimensions || []).length" class="empty">暂无维度评分</div>
              </div>
              <div class="fc-foot">评价结果提交后实时计入农户信用分与好评率</div>
            </template>
            <div v-else class="empty">暂无农户信用数据</div>
          </div>
        </div>
      </div>

      <!-- 右：评价表单 -->
      <div class="card">
        <div class="card-head">
          <div class="card-title">评价表单</div>
          <div class="card-extra row gap-8">
            <span v-if="currentOrder" class="muted">
              评价订单 <span class="mono">{{ currentOrder.orderNo }}</span>（{{ currentOrder.productName }}）
            </span>
            <span v-else class="muted">未选择待评价订单</span>
            <span class="tag tag-green">交易完成</span>
          </div>
        </div>

        <div class="card-pad">
          <div class="ev-section">
            <div class="ev-label">总体评分</div>
            <div class="ev-overall">
              <el-rate v-model="form.overallScore" allow-half />
              <span class="ev-text" :class="{ strong: form.overallScore >= 4 }">{{ overallText }}</span>
              <span class="muted small">{{ Number(form.overallScore || 0).toFixed(1) }} 星</span>
            </div>
          </div>

          <div class="ev-section">
            <div class="ev-label">多维评分</div>
            <div class="dim-list">
              <div v-for="d in DIMENSIONS" :key="d.key" class="dim-row">
                <span class="dim-name">{{ d.label }}</span>
                <el-rate v-model="form[d.key]" allow-half />
                <span class="dim-val">{{ Number(form[d.key] || 0).toFixed(1) }}</span>
              </div>
            </div>
          </div>

          <div class="ev-section">
            <div class="ev-label">评价标签</div>
            <div class="tag-wrap">
              <span
                v-for="t in tags"
                :key="t"
                class="pick-tag"
                :class="{ on: form.tags.includes(t) }"
                @click="toggleTag(t)"
              >
                {{ t }}
              </span>
              <span v-if="!tags.length" class="muted small">暂无可选标签</span>
            </div>
          </div>

          <div class="ev-section">
            <div class="ev-label">文字评价</div>
            <el-input
              v-model="form.content"
              type="textarea"
              :rows="3"
              maxlength="200"
              show-word-limit
              placeholder="黄瓜新鲜、品相好，实收数量与订单基本一致，配送准时，合作愉快！"
            />
          </div>

          <div class="ev-actions">
            <el-button plain @click="clearSelection">暂不评价</el-button>
            <el-button type="primary" :loading="submitting" @click="submit">提交评价</el-button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { purchaserApi } from '@/api'
import { fmtDate, fmtMoney, fmtNum } from '@/utils/format'
import { useAutoRefresh } from '@/utils/useAutoRefresh'

const DIMENSIONS = [
  { key: 'qualityScore', label: '产品品质' },
  { key: 'freshScore', label: '新鲜程度' },
  { key: 'deliveryScore', label: '配送时效' },
  { key: 'serviceScore', label: '服务态度' },
  { key: 'packageScore', label: '包装与分量' }
]

const RATE_TEXT = { 5: '非常满意', 4: '满意', 3: '一般', 2: '不满意', 1: '很差' }

const route = useRoute()
const loading = ref(false)
const submitting = ref(false)
const pendingRows = ref([])
const pendingTotal = ref(0)
const farmer = ref(null)
const tags = ref([])

const form = reactive({
  orderId: null,
  overallScore: 5,
  qualityScore: 5,
  freshScore: 5,
  deliveryScore: 5,
  serviceScore: 5,
  packageScore: 5,
  tags: [],
  content: ''
})

const currentOrder = computed(() => pendingRows.value.find((o) => o.orderId === form.orderId) || null)

const overallText = computed(() => RATE_TEXT[Math.round(Number(form.overallScore || 0))] || '请评分')

const load = async () => {
  loading.value = true
  try {
    const res = await purchaserApi.evaluations({ page: 1, size: 20 })
    const payload = res.data || {}
    pendingRows.value = payload.pending?.records || []
    pendingTotal.value = Number(payload.pending?.total || 0)
    farmer.value = payload.pending?.farmer || null
    tags.value = payload.tags || []
  } catch (e) {
    /* 错误提示由请求拦截器统一处理 */
  } finally {
    loading.value = false
  }
}

const selectOrder = (o) => {
  form.orderId = o.orderId
  form.overallScore = 5
  form.qualityScore = 5
  form.freshScore = 5
  form.deliveryScore = 5
  form.serviceScore = 5
  form.packageScore = 5
  form.tags = []
  form.content = ''
}

const clearSelection = () => {
  form.orderId = null
  form.tags = []
  form.content = ''
  form.overallScore = 5
  DIMENSIONS.forEach((d) => {
    form[d.key] = 5
  })
}

const toggleTag = (t) => {
  const i = form.tags.indexOf(t)
  if (i >= 0) {
    form.tags.splice(i, 1)
  } else {
    form.tags.push(t)
  }
}

const submit = async () => {
  if (!form.orderId) return ElMessage.warning('请先选择待评价订单')
  submitting.value = true
  try {
    await purchaserApi.submitEvaluation({
      orderId: form.orderId,
      overallScore: form.overallScore,
      qualityScore: form.qualityScore,
      freshScore: form.freshScore,
      deliveryScore: form.deliveryScore,
      serviceScore: form.serviceScore,
      packageScore: form.packageScore,
      tags: form.tags,
      content: form.content
    })
    ElMessage.success('评价已提交，结果已纳入农户信用')
    clearSelection()
    await load()
  } catch (e) {
    /* 错误提示由请求拦截器统一处理 */
  } finally {
    submitting.value = false
  }
}

useAutoRefresh(load)
/* 待办「去评价」带 ?focus=<订单号>：直接选中那一单，省得在待评价列表里翻 */
onMounted(async () => {
  await load()
  const no = String(route.query.focus || '')
  if (!no) return
  const hit = pendingRows.value.find((r) => r.orderNo && String(r.orderNo).includes(no))
  if (hit) selectOrder(hit)
})
</script>

<style scoped>
.eval-layout {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1.06fr);
  gap: 14px;
  align-items: start;
}

.left-col {
  display: flex;
  flex-direction: column;
  gap: 14px;
  min-width: 0;
}

.pending-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  max-height: 420px;
  overflow-y: auto;
}

.pending-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 11px 12px;
  border: 1px solid var(--c-border);
  border-radius: 10px;
  cursor: pointer;
  transition: border-color 0.18s, background 0.18s, box-shadow 0.18s;
}

.pending-item:hover {
  border-color: var(--accent-soft);
  background: #f8fdfa;
}

.pending-item.active {
  border-color: var(--c-primary);
  background: var(--surface-2);
  box-shadow: 0 2px 10px rgba(22, 163, 74, 0.1);
}

.pi-main {
  flex: 1;
  min-width: 0;
}

.pi-top {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 10px;
}

.pi-sub {
  margin-top: 3px;
  font-size: var(--fs-sm);
  color: var(--c-muted);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.fc-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  padding: 12px 14px;
  border-radius: 10px;
  background: linear-gradient(135deg, var(--surface-2), #e8f8ee);
  border: 1px solid var(--accent-soft);
}

.fc-name {
  font-size: var(--fs-md);
  font-weight: 700;
  color: var(--ink);
}

.fc-meta {
  display: flex;
  align-items: center;
  gap: 7px;
  margin-top: 6px;
  font-size: var(--fs-xs);
  color: var(--muted);
  flex-wrap: wrap;
}

.fc-score {
  display: flex;
  flex-direction: column;
  align-items: center;
  line-height: 1.1;
  flex-shrink: 0;
}

.fs-num {
  font-family: var(--font-num);
  font-size: var(--fs-display);
  font-weight: 700;
  color: var(--ink);
  letter-spacing: -0.6px;
}

.fs-label {
  font-size: var(--fs-2xs);
  color: var(--muted);
  margin-top: 2px;
}

.fc-dims {
  display: flex;
  flex-direction: column;
  gap: 10px;
  margin-top: 14px;
}

.fc-dim {
  display: flex;
  align-items: center;
  gap: 10px;
}

.fd-name {
  width: 62px;
  flex-shrink: 0;
  font-size: var(--fs-sm);
  color: var(--c-muted);
}

.fd-val {
  width: 30px;
  text-align: right;
  flex-shrink: 0;
  font-family: var(--font-num);
  font-size: var(--fs-sm);
  font-weight: 600;
  color: var(--accent);
}

.fc-foot {
  margin-top: 13px;
  padding-top: 10px;
  border-top: 1px dashed var(--c-border);
  font-size: var(--fs-xs);
  color: var(--c-muted-2);
}

.ev-section {
  padding-bottom: 15px;
  margin-bottom: 15px;
  border-bottom: 1px dashed var(--c-border);
}

.ev-section:last-of-type {
  border-bottom: none;
  margin-bottom: 4px;
}

.ev-label {
  font-size: var(--fs-sm);
  font-weight: 600;
  color: var(--ink);
  margin-bottom: 10px;
}

.ev-overall {
  display: flex;
  align-items: center;
  gap: 12px;
}

.ev-overall :deep(.el-rate) {
  height: 26px;
}

.ev-overall :deep(.el-rate__icon) {
  font-size: var(--fs-xl);
  margin-right: 4px;
}

.ev-text {
  font-size: var(--fs-base);
  font-weight: 600;
  color: var(--muted);
}

.ev-text.strong {
  color: var(--accent);
}

.dim-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.dim-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 5px 10px;
  border-radius: 8px;
}

.dim-row:nth-child(odd) {
  background: var(--surface-2);
}

.dim-name {
  width: 78px;
  flex-shrink: 0;
  font-size: var(--fs-sm);
  color: var(--c-text-2);
}

.dim-row :deep(.el-rate) {
  height: 22px;
}

.dim-row :deep(.el-rate__icon) {
  font-size: var(--fs-lg);
}

.dim-val {
  margin-left: auto;
  font-family: var(--font-num);
  font-size: var(--fs-sm);
  font-weight: 600;
  color: var(--warn);
}

.tag-wrap {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.pick-tag {
  display: inline-flex;
  align-items: center;
  height: 26px;
  padding: 0 12px;
  border-radius: 13px;
  border: 1px solid var(--c-border-strong);
  background: #fff;
  font-size: var(--fs-sm);
  color: var(--c-text-2);
  cursor: pointer;
  transition: all 0.16s;
  user-select: none;
}

.pick-tag:hover {
  border-color: #86d9a6;
  color: var(--accent);
}

.pick-tag.on {
  border-color: var(--c-primary);
  background: var(--surface-2);
  color: var(--accent);
  font-weight: 600;
  box-shadow: 0 1px 6px rgba(22, 163, 74, 0.15);
}

.ev-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 10px;
  margin-top: 6px;
}

.ev-actions .el-button {
  min-width: 108px;
}

@media (max-width: 1200px) {
  .eval-layout {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
