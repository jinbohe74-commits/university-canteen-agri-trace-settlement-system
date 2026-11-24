<template>
  <el-drawer v-model="visible" :size="620" :with-header="true" class="order-drawer">
    <template #header>
      <div class="dh">
        <div>
          <div class="dh-title">订单详情</div>
          <div class="dh-no mono">{{ order?.orderNo || '—' }}</div>
        </div>
        <StatusTag v-if="order" type="order" :value="order.status" />
      </div>
    </template>

    <div v-loading="loading">
      <template v-if="order">
        <!-- 状态步骤条 -->
        <div class="steps-wrap">
          <el-steps :active="activeStep" align-center finish-status="success" process-status="process">
            <el-step title="下单" />
            <el-step title="接单" />
            <el-step title="质检" />
            <el-step title="配送" />
            <el-step title="入库" />
            <el-step title="结算" />
            <el-step title="完成" />
          </el-steps>
        </div>

        <!-- 当前阶段的可执行动作：不再让 50/70/80/90 只停留在标签和步骤条上 -->
        <OrderStageActions
          v-if="order"
          :order="order"
          :order-id="orderId"
          :delivery="delivery"
          :inbound="inbound"
          :settlement="settlement"
          :evaluation="evaluation"
          :role="userStore.role"
        />

        <el-descriptions :column="2" border size="small" class="desc">
          <el-descriptions-item label="产品">
            {{ order.productName }}（{{ order.categoryName }}）
          </el-descriptions-item>
          <el-descriptions-item label="数量">
            {{ fmtNum(order.quantity) }} {{ order.unit }}
          </el-descriptions-item>
          <el-descriptions-item label="供货农户">
            {{ order.farmerName }}<span class="muted small"> · {{ order.farmerType }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="收货食堂">{{ order.canteenName }}</el-descriptions-item>
          <el-descriptions-item label="成交单价">¥ {{ order.unitPrice }} / {{ order.unit }}</el-descriptions-item>
          <el-descriptions-item label="订单金额">
            <span class="money">¥ {{ fmtMoney(order.totalAmount) }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="下单时间">{{ fmtDate(order.createTime) }}</el-descriptions-item>
          <el-descriptions-item label="期望交付">{{ fmtDate(order.expectedDelivery) }}</el-descriptions-item>
          <el-descriptions-item label="实际交付">{{ fmtDate(order.actualDelivery) }}</el-descriptions-item>
          <el-descriptions-item label="批次号">
            <span class="mono">{{ order.batchNo || '—' }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="实收数量" v-if="order.receiveQuantity">
            {{ fmtNum(order.receiveQuantity) }} {{ order.unit }}
          </el-descriptions-item>
          <el-descriptions-item label="损耗率" v-if="order.lossRate !== null && order.lossRate !== undefined">
            <span :class="Number(order.lossRate) > 0 ? 'warn-text' : ''">{{ order.lossRate }}%</span>
          </el-descriptions-item>
        </el-descriptions>

        <el-tabs v-model="tab" class="detail-tabs">
          <el-tab-pane label="流转记录" name="flow">
            <div class="timeline">
              <div v-for="(n, i) in events" :key="i" class="timeline-item">
                <span class="timeline-dot"></span>
                <div class="timeline-time">{{ n.time }}</div>
                <div class="timeline-title">{{ n.title }}</div>
                <div class="timeline-desc">{{ n.desc }}</div>
              </div>
            </div>
          </el-tab-pane>

          <el-tab-pane label="质检报告" name="quality">
            <template v-if="quality">
              <div class="qc-row">
                <StatusTag type="result" :value="quality.result" />
                <span class="muted small">{{ quality.inspector }} · {{ fmtDate(quality.inspectTime) }}</span>
              </div>
              <div class="qc-list">
                <div v-for="(it, i) in qualityItems" :key="i" class="qc-line">
                  <span class="bold">{{ it.name }}</span>
                  <span class="muted small">{{ it.std }}</span>
                  <span>{{ it.value }}</span>
                  <span class="tag" :class="it.ok === false ? 'tag-red' : 'tag-green'">
                    {{ it.ok === false ? '不合格' : '达标' }}
                  </span>
                </div>
              </div>
              <div class="hash-mini mono">{{ quality.hash }}</div>
            </template>
            <div v-else class="empty">暂无质检记录</div>
          </el-tab-pane>

          <el-tab-pane label="配送与入库" name="delivery">
            <template v-if="delivery">
              <el-descriptions :column="2" border size="small">
                <el-descriptions-item label="配送单号">{{ delivery.deliveryNo }}</el-descriptions-item>
                <el-descriptions-item label="车牌 / 司机">{{ delivery.vehicle }} · {{ delivery.driver }}</el-descriptions-item>
                <el-descriptions-item label="车厢温度">{{ delivery.temperature }} ℃</el-descriptions-item>
                <el-descriptions-item label="全程里程">{{ delivery.mileage }} km</el-descriptions-item>
                <el-descriptions-item label="起点">{{ delivery.origin }}</el-descriptions-item>
                <el-descriptions-item label="终点">{{ delivery.destination }}</el-descriptions-item>
              </el-descriptions>
            </template>
            <div v-else class="empty">暂无配送记录</div>
            <template v-if="inbound">
              <div class="sub-title">入库记录</div>
              <el-descriptions :column="2" border size="small">
                <el-descriptions-item label="下单 / 实收">
                  {{ fmtQty(inbound.orderQuantity) }} / {{ fmtQty(inbound.receiveQuantity) }} kg
                </el-descriptions-item>
                <el-descriptions-item label="损耗">
                  {{ fmtQty(inbound.lossQuantity) }} kg（{{ inbound.lossRate }}%）
                </el-descriptions-item>
                <el-descriptions-item label="验收人">{{ inbound.operatorName }}</el-descriptions-item>
                <el-descriptions-item label="入库时间">{{ fmtDate(inbound.inboundTime) }}</el-descriptions-item>
              </el-descriptions>
            </template>
          </el-tab-pane>

          <el-tab-pane label="结算与评价" name="settle">
            <template v-if="settlement">
              <el-descriptions :column="2" border size="small">
                <el-descriptions-item label="结算单号">{{ settlement.settlementNo }}</el-descriptions-item>
                <el-descriptions-item label="状态">
                  <StatusTag type="settlement" :value="settlement.status" />
                </el-descriptions-item>
                <el-descriptions-item label="订单金额">¥ {{ fmtMoney(settlement.totalAmount) }}</el-descriptions-item>
                <el-descriptions-item label="损耗扣款">¥ {{ fmtMoney(settlement.lossDeduction) }}</el-descriptions-item>
                <el-descriptions-item label="实际结算">
                  <span class="money">¥ {{ fmtMoney(settlement.actualAmount) }}</span>
                </el-descriptions-item>
                <el-descriptions-item label="审核人">{{ settlement.auditor || '—' }}</el-descriptions-item>
              </el-descriptions>
            </template>
            <div v-else class="empty">暂未生成结算单</div>
            <template v-if="evaluation">
              <div class="sub-title">采购评价</div>
              <div class="eval-line">
                <StarRate :value="evaluation.overallScore" show-text />
                <span class="muted small">{{ evaluation.tags }}</span>
              </div>
              <div class="eval-content">{{ evaluation.content }}</div>
            </template>
          </el-tab-pane>

          <el-tab-pane label="信用变动" name="credit">
            <div v-for="c in credit" :key="c.id" class="credit-line">
              <span class="bold">{{ c.reason }}</span>
              <span :class="Number(c.changeScore) >= 0 ? 'up' : 'down'">
                {{ Number(c.changeScore) >= 0 ? '+' : '' }}{{ c.changeScore }}
              </span>
              <span class="muted small">{{ fmtDate(c.createTime) }}</span>
            </div>
            <div v-if="!credit.length" class="empty">暂无信用变动记录</div>
          </el-tab-pane>
        </el-tabs>
      </template>
    </div>
  </el-drawer>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { adminApi, farmerApi, purchaserApi } from '@/api'
import { useUserStore } from '@/store/user'
import StatusTag from './StatusTag.vue'
import StarRate from './StarRate.vue'
import OrderStageActions from './OrderStageActions.vue'
import { fmtDate, fmtMoney, fmtNum, fmtQty } from '@/utils/format'

const userStore = useUserStore()

const props = defineProps({
  modelValue: Boolean,
  orderId: [Number, String],
  endpoint: { type: String, default: 'admin' }
})
const emit = defineEmits(['update:modelValue'])

const visible = computed({
  get: () => props.modelValue,
  set: (v) => emit('update:modelValue', v)
})

const loading = ref(false)
const data = ref({})
const tab = ref('flow')

const order = computed(() => data.value.order || null)
const quality = computed(() => (data.value.quality || [])[0] || null)
const delivery = computed(() => (data.value.delivery || [])[0] || null)
const inbound = computed(() => (data.value.inbound || [])[0] || null)
const settlement = computed(() => (data.value.settlement || [])[0] || null)
const evaluation = computed(() => (data.value.evaluation || [])[0] || null)
const credit = computed(() => data.value.credit || [])

const qualityItems = computed(() => {
  if (!quality.value?.items) return []
  try {
    return JSON.parse(quality.value.items)
  } catch (e) {
    return []
  }
})

const activeStep = computed(() => {
  const s = order.value?.status
  if (!s) return 0
  if (s === 99) return 0
  const map = { 10: 1, 20: 2, 30: 2, 40: 3, 41: 2, 50: 4, 60: 4, 70: 5, 80: 6, 90: 7, 100: 7 }
  return map[s] || 0
})

const events = computed(() => {
  const o = order.value
  if (!o) return []
  const list = []
  list.push({ time: fmtDate(o.createTime), title: '采购下单', desc: `采购员下单 ${fmtNum(o.quantity)}${o.unit}，金额 ¥${fmtMoney(o.totalAmount)}` })
  if (o.status >= 20) list.push({ time: '—', title: '农户接单', desc: `${o.farmerName} 已确认接单` })
  if (o.status >= 30) list.push({ time: '—', title: '农户发货', desc: '完成采摘并安排发货，进入质检环节' })
  if (quality.value) {
    list.push({
      time: fmtDate(quality.value.inspectTime),
      title: '质检' + quality.value.result,
      desc: `${quality.value.inspector} 录入质检报告，批次 ${quality.value.batchNo}`
    })
  }
  if (delivery.value) {
    list.push({ time: fmtDate(delivery.value.startTime), title: '冷链配送', desc: `${delivery.value.vehicle} 由 ${delivery.value.origin} 发往 ${delivery.value.destination}，全程 ${delivery.value.mileage}km` })
  }
  if (inbound.value) {
    list.push({
      time: fmtDate(inbound.value.inboundTime),
      title: '食堂入库',
      desc: `实收 ${fmtQty(inbound.value.receiveQuantity)}kg，损耗 ${fmtQty(inbound.value.lossQuantity)}kg（${inbound.value.lossRate}%）`
    })
  }
  if (settlement.value) {
    list.push({
      time: fmtDate(settlement.value.createTime),
      title: '结算单 ' + settlement.value.status,
      desc: `订单金额 ¥${fmtMoney(settlement.value.totalAmount)} − 损耗扣款 ¥${fmtMoney(settlement.value.lossDeduction)} = ¥${fmtMoney(settlement.value.actualAmount)}`
    })
  }
  if (evaluation.value) {
    list.push({ time: fmtDate(evaluation.value.createTime), title: '采购评价完成', desc: `总体 ${evaluation.value.overallScore} 星，订单流程闭环` })
  }
  return list
})

const load = async () => {
  if (!props.orderId) return
  loading.value = true
  try {
    const api = props.endpoint === 'farmer' ? farmerApi : props.endpoint === 'purchaser' ? purchaserApi : adminApi
    const res = await api.orderDetail(props.orderId)
    data.value = res.data || {}
  } finally {
    loading.value = false
  }
}

watch(
  () => [props.modelValue, props.orderId],
  ([v]) => {
    if (v) {
      tab.value = 'flow'
      load()
    }
  }
)
</script>

<style scoped>
.dh {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  padding-right: 18px;
}

.dh-title {
  font-size: var(--fs-md);
  font-weight: 700;
}

.dh-no {
  font-size: var(--fs-sm);
  color: var(--c-muted);
  margin-top: 3px;
}

.steps-wrap {
  padding: 4px 0 18px;
}

.desc {
  margin-bottom: 16px;
}

.detail-tabs :deep(.el-tabs__header) {
  margin-bottom: 14px;
}

.qc-row {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
}

.qc-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.qc-line {
  display: grid;
  grid-template-columns: 90px minmax(0, 1fr) 110px 58px;
  gap: 8px;
  align-items: center;
  font-size: var(--fs-sm);
  padding: 8px 10px;
  border-radius: 8px;
  background: var(--surface-2);
}

.hash-mini {
  margin-top: 12px;
  font-size: var(--fs-xs);
  color: var(--c-muted);
  word-break: break-all;
  background: var(--surface-2);
  padding: 8px 10px;
  border-radius: 8px;
}

.sub-title {
  margin: 16px 0 10px;
  font-size: var(--fs-base);
  font-weight: 600;
}

.eval-line {
  display: flex;
  align-items: center;
  gap: 10px;
}

.eval-content {
  margin-top: 8px;
  font-size: var(--fs-sm);
  color: var(--c-text-2);
  line-height: 1.8;
  background: var(--surface-2);
  padding: 10px 12px;
  border-radius: 8px;
}

.credit-line {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 60px 110px;
  align-items: center;
  gap: 8px;
  font-size: var(--fs-sm);
  padding: 9px 0;
  border-bottom: 1px dashed var(--c-border);
}

.up {
  color: var(--c-primary);
  font-weight: 600;
}

.down {
  color: var(--c-red);
  font-weight: 600;
}

.warn-text {
  color: var(--warn);
  font-weight: 600;
}

.timeline {
  padding-left: 6px;
}
</style>
