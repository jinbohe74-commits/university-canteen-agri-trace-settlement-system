<template>
  <div class="page" v-loading="loading">
    <div class="page-head">
      <div>
        <h2 class="page-title">质检录入</h2>
        <p class="page-sub">录入批次抽检结果，合格后自动生成溯源二维码与存证哈希</p>
      </div>
      <div class="page-actions">
        <el-button plain @click="printReport"><el-icon><Printer /></el-icon>打印报告</el-button>
      </div>
    </div>

    <div v-if="!order" class="card card-pad">
      <div class="empty">
        <el-icon style="font-size: 30px; color: #b7c7c1"><DocumentChecked /></el-icon>
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
              <div class="si"><span class="k">抽样数量</span><span class="v">{{ sampleQty }} kg</span></div>
              <div class="si"><span class="k">收货食堂</span><span class="v">{{ order.canteenName }}</span></div>
              <div class="si"><span class="k">订单数量</span><span class="v">{{ fmtNum(order.quantity) }} {{ order.unit }}</span></div>
            </div>

            <div class="sub-block-title" style="margin-top: 18px">检测项目</div>
            <div class="test-list">
              <div v-for="(it, i) in items" :key="i" class="test-row">
                <div class="tr-name">{{ it.name }}</div>
                <div class="tr-std">{{ it.standard }}</div>
                <div class="tr-val">{{ it.value }}</div>
                <el-radio-group v-model="it.ok" size="small" class="tr-radio">
                  <el-radio-button :label="true">达标</el-radio-button>
                  <el-radio-button :label="false">不合格</el-radio-button>
                </el-radio-group>
              </div>
            </div>

            <div class="sub-block-title" style="margin-top: 18px">检测结论</div>
            <div class="conclusion">
              <button
                class="concl-btn ok" :class="{ active: result === '合格' }"
                @click="result = '合格'"
              >
                <el-icon><CircleCheckFilled /></el-icon>
                <span>质检合格</span>
                <em>生成批次二维码，放行配送</em>
              </button>
              <button
                class="concl-btn no" :class="{ active: result === '不合格' }"
                @click="result = '不合格'"
              >
                <el-icon><CircleCloseFilled /></el-icon>
                <span>不通过</span>
                <em>整批驳回，扣减农户信用 -5</em>
              </button>
            </div>

            <div class="sub-block-title" style="margin-top: 18px">检测备注</div>
            <el-input v-model="remark" type="textarea" :rows="3" maxlength="200" show-word-limit />

            <div class="sub-block-title" style="margin-top: 18px">
              检测报告 / 现场照片（已上传 3 张）
            </div>
            <div class="photo-row">
              <div v-for="i in 3" :key="i" class="photo-ph">
                <el-icon><PictureFilled /></el-icon>
                <span>现场照片 {{ i }}</span>
              </div>
              <div class="photo-add" @click="uploadTip">
                <el-icon><Plus /></el-icon>
                <span>上传</span>
              </div>
            </div>

            <div class="form-actions">
              <el-button plain @click="draft">暂存</el-button>
              <el-button type="primary" :loading="submitting" @click="submit">
                提交质检
              </el-button>
              <el-button v-if="qrCode && result === '合格'" plain @click="goDelivery">
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
              <div class="card-title">检测标准</div>
              <div class="card-extra">叶菜类</div>
            </div>
            <div class="card-pad">
              <div class="std-grid">
                <div v-for="(s, i) in standards" :key="i" class="std-item">
                  <span class="std-name">{{ s.name }}</span>
                  <span class="std-val">{{ s.value }} {{ s.unit }}</span>
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
import { inspectorApi } from '@/api'
import { useUserStore } from '@/store/user'
import { fmtNum } from '@/utils/format'

const route = useRoute()
const router = useRouter()
const store = useUserStore()

const loading = ref(false)
const submitting = ref(false)
const order = ref(null)
const batchNo = ref('')
const sampleQty = ref(2)
const items = ref([])
const standards = ref([])
const result = ref('合格')
const remark = ref('整体品质良好，农残快检合格，冷链温度正常，准予生成批次二维码并放行配送。')
const qrCode = ref('')
const pendingCount = ref(0)

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
    sampleQty.value = d.sampleQty
    items.value = (d.items || []).map((x) => ({ ...x, ok: x.ok !== false }))
    standards.value = d.standards || []
    if (d.report && d.report.result === '合格') {
      // 已检订单展示既有结论
      result.value = d.report.result
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
  if (qid) {
    await loadByOrderId(qid)
    return
  }
  loading.value = true
  try {
    const res = await inspectorApi.tasks({ page: 1, size: 20 })
    const records = res.data?.records || []
    pendingCount.value = res.data?.stats?.pending || 0
    const first = records.find((r) => r.status === 30)
    if (first) {
      await loadByOrderId(first.id)
    } else {
      order.value = null
    }
  } finally {
    loading.value = false
  }
}

const uploadTip = () => {
  ElMessage.info('演示环境：现场照片上传后随质检报告一并存档')
}

const draft = () => {
  ElMessage.success('已暂存草稿，可稍后继续提交')
}

const submit = async () => {
  const bad = items.value.some((x) => x.ok === false)
  if (bad && result.value === '合格') {
    try {
      await ElMessageBox.confirm(
        '检测项目中存在「不合格」项，但结论选择了「质检合格」。系统将以结论为准，是否继续提交？',
        '请确认检测结论',
        { type: 'warning', confirmButtonText: '继续提交', cancelButtonText: '返回修改' }
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
      result: result.value,
      items: items.value,
      remark: remark.value
    })
    const d = res.data || {}
    if (d.qrCode) qrCode.value = d.qrCode
    ElMessage.success(
      result.value === '合格'
        ? '质检通过，已生成溯源二维码并放行配送'
        : '已判定不合格，批次驳回并扣减农户信用'
    )
    order.value.status = result.value === '合格' ? 40 : 41
  } finally {
    submitting.value = false
  }
}

const goDelivery = () => router.push('/inspector/delivery')

const printReport = () => {
  if (!order.value) {
    ElMessage.warning('暂无可打印的质检单')
    return
  }
  ElMessage.success('质检报告已生成：' + batchNo.value)
}

watch(() => route.query.orderId, (v) => {
  if (v) loadByOrderId(v)
})

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
  font-size: 12px;
  font-weight: 600;
  color: #0f766e;
  background: #e6f6f4;
  padding: 3px 9px;
  border-radius: 6px;
}

.tb-no {
  font-size: 12.5px;
  color: #1b2a24;
  font-weight: 600;
}

.tb-right {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12.5px;
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
  font-size: 13px;
  font-weight: 600;
  color: #1b2a24;
  margin-bottom: 10px;
  padding-left: 8px;
  border-left: 3px solid var(--c-primary);
  line-height: 1.2;
}

.sample-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px 18px;
}

.si {
  display: flex;
  gap: 10px;
  font-size: 12.5px;
}

.si .k {
  color: var(--c-muted);
  width: 60px;
  flex-shrink: 0;
}

.si .v {
  color: #1b2a24;
  font-weight: 500;
  word-break: break-all;
}

.test-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.test-row {
  display: grid;
  grid-template-columns: 96px minmax(0, 1fr) 118px 120px;
  align-items: center;
  gap: 10px;
  padding: 9px 12px;
  border-radius: 9px;
  background: #f8fbfa;
  border: 1px solid #eef4f1;
}

.tr-name {
  font-size: 13px;
  font-weight: 600;
  color: #1b2a24;
}

.tr-std {
  font-size: 11.5px;
  color: var(--c-muted);
}

.tr-val {
  font-size: 12px;
  color: #374151;
  font-weight: 500;
}

.tr-radio :deep(.el-radio-button__inner) {
  padding: 5px 9px;
  font-size: 11.5px;
}

.conclusion {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.concl-btn {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 4px;
  padding: 13px 15px;
  border-radius: 11px;
  border: 1px solid var(--c-border-strong);
  background: #fff;
  cursor: pointer;
  text-align: left;
  transition: all 0.18s;
}

.concl-btn .el-icon {
  font-size: 20px;
}

.concl-btn span {
  font-size: 13.5px;
  font-weight: 600;
}

.concl-btn em {
  font-size: 11px;
  font-style: normal;
  color: var(--c-muted);
}

.concl-btn.ok .el-icon {
  color: #22c55e;
}

.concl-btn.no .el-icon {
  color: #ef4444;
}

.concl-btn.ok.active {
  border-color: #16a34a;
  background: #f0fdf4;
  box-shadow: 0 6px 16px rgba(22, 163, 74, 0.12);
}

.concl-btn.no.active {
  border-color: #ef4444;
  background: #fef2f2;
  box-shadow: 0 6px 16px rgba(239, 68, 68, 0.12);
}

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
  font-size: 10.5px;
  border: 1px dashed var(--c-border-strong);
  background: #f8fbfa;
  color: var(--c-muted-2);
}

.photo-add {
  cursor: pointer;
  color: var(--c-primary);
  border-color: #b8e3c8;
  background: #f4fdf8;
}

.form-actions {
  display: flex;
  gap: 10px;
  margin-top: 20px;
  padding-top: 16px;
  border-top: 1px solid var(--c-border);
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
  font-size: 12px;
}

.qr-ph .el-icon {
  font-size: 30px;
}

.qr-batch {
  font-size: 12px;
  font-weight: 600;
  color: #14532d;
  background: #f2fbf5;
  border: 1px dashed #bbe6cb;
  border-radius: 8px;
  padding: 7px 9px;
  word-break: break-all;
}

.qr-tip {
  margin-top: 9px;
  font-size: 11.5px;
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
  background: #f8fbfa;
}

.std-name {
  font-size: 11.5px;
  color: var(--c-muted);
}

.std-val {
  font-size: 12.5px;
  font-weight: 600;
  color: #1b2a24;
}

.warn-card {
  display: flex;
  gap: 9px;
  padding: 13px 15px;
  border-radius: 11px;
  background: #fdf6e9;
  border: 1px solid #f6e2bd;
  color: #92400e;
  font-size: 12px;
  line-height: 1.75;
}

.warn-card .el-icon {
  color: #f59e0b;
  font-size: 16px;
  flex-shrink: 0;
  margin-top: 2px;
}
</style>
