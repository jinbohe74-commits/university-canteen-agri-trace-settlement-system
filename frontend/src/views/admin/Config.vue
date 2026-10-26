<template>
  <div class="page" v-loading="loading">
    <div class="page-head">
      <div>
        <h2 class="page-title">系统参数配置</h2>
        <p class="page-sub">智能匹配权重、信用加减分规则、食安预警阈值等后台可配置，无需改代码</p>
      </div>
      <div class="page-actions">
        <el-button plain @click="restoreDefault">
          <el-icon><RefreshLeft /></el-icon>恢复默认
        </el-button>
        <el-button type="primary" :loading="saving" @click="save">
          <el-icon><Select /></el-icon>保存配置
        </el-button>
      </div>
    </div>

    <!-- 第一行：权重 + 信用 -->
    <div class="cfg-grid">
      <SectionCard title="智能匹配权重配置" :body-style="false">
        <template #extra>
          <span class="sum-chip" :class="{ bad: !sumOk }">权重之和 = {{ sumText }}</span>
        </template>

        <div class="tip-bar">
          综合得分 = W1×品类 + W2×价格 + W3×数量 + W4×信用 + W5×距离/历史，按得分降序推荐
          Top{{ model.match_top_n || 5 }} 货源。
        </div>

        <div class="w-list">
          <div v-for="w in WEIGHTS" :key="w.key" class="w-row">
            <div class="w-info">
              <div class="w-name">{{ w.name }}<span class="w-tag">{{ w.w }}</span></div>
              <div class="w-desc">{{ w.desc }}</div>
            </div>
            <div class="w-slider">
              <el-slider
                v-model="model[w.key]"
                :min="0"
                :max="1"
                :step="0.05"
                :show-tooltip="false"
              />
            </div>
            <div class="w-val mono">{{ num2(model[w.key]) }}</div>
          </div>
        </div>

        <div class="w-foot" :class="{ bad: !sumOk }">
          <el-icon><component :is="sumOk ? 'CircleCheckFilled' : 'WarningFilled'" /></el-icon>
          <span v-if="sumOk">权重配置有效，归一化后用于货源综合排序。</span>
          <span v-else>权重之和应为 1.00，当前为 {{ sumText }}，保存后系统将按占比归一化处理。</span>
        </div>
      </SectionCard>

      <SectionCard title="信用分规则" :body-style="false">
        <template #extra><span class="muted small">范围 0~100</span></template>
        <div class="credit-list">
          <div v-for="r in CREDIT_ROWS" :key="r.key" class="rule-row">
            <div class="rr-info">
              <div class="rr-name">{{ r.name }}</div>
              <div class="rr-desc">{{ r.desc }}</div>
            </div>
            <div class="rr-val">
              <el-input-number
                v-model="model[r.key]"
                :min="r.min"
                :max="r.max"
                :step="1"
                size="small"
                controls-position="right"
                style="width: 104px"
              />
              <span class="unit">分</span>
            </div>
          </div>
        </div>
        <div class="card-note">
          信用分由系统按订单履约、质检结果与损耗情况自动加减，低于 60 分将限制参与智能匹配。
        </div>
      </SectionCard>
    </div>

    <!-- 第二行：预警阈值 + 业务参数 -->
    <div class="cfg-grid2">
      <SectionCard title="食安预警阈值" :body-style="false">
        <template #extra><span class="muted small">四类阈值即时生效</span></template>
        <div class="credit-list">
          <div v-for="r in WARN_ROWS" :key="r.key" class="rule-row">
            <div class="rr-info">
              <div class="rr-name">{{ r.name }}</div>
              <div class="rr-desc">{{ r.desc }}</div>
            </div>
            <div class="rr-val">
              <span v-if="r.pm" class="unit">±</span>
              <el-input-number
                v-model="model[r.key]"
                :min="r.min"
                :max="r.max"
                :step="1"
                size="small"
                controls-position="right"
                style="width: 104px"
              />
              <span class="unit">{{ r.unit }}</span>
            </div>
          </div>
        </div>
        <div class="card-note">
          阈值变更后对新产生的预警立即生效，历史预警不受影响。
        </div>
      </SectionCard>

      <div class="right-col">
        <SectionCard title="结算与其他参数" :body-style="false">
          <div class="kv-row">
            <div class="kv-info">
              <div class="kv-name">结算周期</div>
              <div class="kv-desc">按周期生成结算单，统一对账</div>
            </div>
            <div class="kv-val">
              <span class="tag tag-green">{{ model.business_settle_cycle || '—' }}</span>
              <span class="muted small">{{ model.business_settle_day || '' }}</span>
            </div>
          </div>
          <div class="kv-row">
            <div class="kv-info">
              <div class="kv-name">登录失败锁定</div>
              <div class="kv-desc">连续失败达阈值即锁定账号</div>
            </div>
            <div class="kv-val">
              <el-input-number
                v-model="model.business_login_fail_limit"
                :min="1" :max="20" :step="1" size="small" controls-position="right" style="width: 92px"
              />
              <span class="unit">次 /</span>
              <el-input-number
                v-model="model.business_login_lock_minutes"
                :min="1" :max="120" :step="1" size="small" controls-position="right" style="width: 92px"
              />
              <span class="unit">分钟</span>
            </div>
          </div>
        </SectionCard>

        <SectionCard title="推荐与匹配" :body-style="false">
          <div class="kv-row">
            <div class="kv-info">
              <div class="kv-name">推荐货源数量</div>
              <div class="kv-desc">智能匹配按得分降序推荐 Top{{ model.match_top_n || 5 }}</div>
            </div>
            <div class="kv-val">
              <el-input-number
                v-model="model.match_top_n"
                :min="1" :max="20" :step="1" size="small" controls-position="right" style="width: 92px"
              />
              <span class="unit">个</span>
            </div>
          </div>
          <div class="kv-row">
            <div class="kv-info">
              <div class="kv-name">报价有效期</div>
              <div class="kv-desc">超期报价不参与匹配与下单</div>
            </div>
            <div class="kv-val">
              <el-input-number
                v-model="model.quote_valid_hours"
                :min="1" :max="168" :step="1" size="small" controls-position="right" style="width: 92px"
              />
              <span class="unit">小时</span>
            </div>
          </div>
        </SectionCard>

        <SectionCard title="功能开关" :body-style="false">
          <div v-for="s in SWITCHES" :key="s.key" class="sw-row">
            <div class="sw-info">
              <div class="sw-name">{{ s.name }}</div>
              <div class="sw-desc">{{ s.desc }}</div>
            </div>
            <el-switch v-model="model[s.key]" />
          </div>
        </SectionCard>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { adminApi } from '@/api'
import SectionCard from '@/components/SectionCard.vue'

const WEIGHTS = [
  { key: 'match_weight_category', w: 'W1', name: '品类匹配度', desc: '供需产品品类、规格的匹配度' },
  { key: 'match_weight_price', w: 'W2', name: '价格竞争力', desc: '报价相对市场价与历史均价的表现' },
  { key: 'match_weight_quantity', w: 'W3', name: '数量满足度', desc: '可供数量对采购需求的满足程度' },
  { key: 'match_weight_credit', w: 'W4', name: '农户信用', desc: '农户信用分与历史履约表现' },
  { key: 'match_weight_distance', w: 'W5', name: '距离·历史合作', desc: '产地到食堂运距与历史合作次数' }
]

const CREDIT_ROWS = [
  { key: 'credit_init_score', name: '新农户初始信用分', desc: '新农户入驻基础信用分', min: 0, max: 100 },
  { key: 'credit_reward_normal', name: '正常履约', desc: '按期接单、发货、入库完成整单', min: -10, max: 10 },
  { key: 'credit_punish_late', name: '超时发货', desc: '超过约定发货时间未发货', min: -20, max: 0 },
  { key: 'credit_punish_quality', name: '质检不合格', desc: '批次抽检不合格或无检测报告', min: -20, max: 0 },
  { key: 'credit_punish_loss', name: '损耗率超阈值', desc: '入库损耗率超过设定阈值', min: -20, max: 0 }
]

const WARN_ROWS = [
  { key: 'warn_price_rate', name: '价格波动阈值', desc: '日均价较周参考价波动超此值即预警', min: 1, max: 100, unit: '%', pm: true },
  { key: 'warn_order_overdue_days', name: '订单逾期阈值', desc: '未接单 / 未发货超时天数', min: 1, max: 30, unit: '天' },
  { key: 'warn_cert_expire_days', name: '证照到期提醒', desc: '到期前逐日提醒天数', min: 1, max: 180, unit: '天' },
  { key: 'warn_loss_rate', name: '损耗率阈值', desc: '超阈值自动提醒并扣信用分', min: 0, max: 50, unit: '%' }
]

const SWITCHES = [
  { key: 'switch_sms_warning', name: '短信 / 站内预警通知', desc: '预警产生时同步推送管理员与采购员' },
  { key: 'switch_batch_qr_required', name: '批次二维码强制绑定', desc: '未绑定批次码的订单不允许出库' },
  { key: 'switch_partial_receipt', name: '部分收货自动结算', desc: '按实收数量自动生成结算单并扣损耗' }
]

const loading = ref(false)
const saving = ref(false)
const items = ref([])
const model = reactive({})

const num2 = (v) => Number(v || 0).toFixed(2)

const sumText = computed(() => {
  const s = WEIGHTS.reduce((acc, w) => acc + Number(model[w.key] || 0), 0)
  return s.toFixed(2)
})

const sumOk = computed(() => Math.abs(Number(sumText.value) - 1) < 0.005)

const load = async () => {
  loading.value = true
  try {
    const res = await adminApi.config()
    const d = res.data || {}
    items.value = d.items || []
    const m = {}
    for (const it of items.value) {
      if (it.valueType === 'number') m[it.configKey] = Number(it.configValue)
      else if (it.valueType === 'bool') m[it.configKey] = String(it.configValue) === 'true'
      else m[it.configKey] = it.configValue
    }
    Object.keys(model).forEach((k) => delete model[k])
    Object.assign(model, m)
  } catch (e) {
    /* 拦截器已提示 */
  } finally {
    loading.value = false
  }
}

const buildPayload = () => {
  const payload = {}
  for (const it of items.value) {
    const v = model[it.configKey]
    if (v === undefined || v === null) continue
    if (it.valueType === 'bool') payload[it.configKey] = v ? 'true' : 'false'
    else if (it.valueType === 'number') payload[it.configKey] = String(Number(v))
    else payload[it.configKey] = String(v)
  }
  return payload
}

const save = async () => {
  saving.value = true
  try {
    await adminApi.saveConfig(buildPayload())
    ElMessage.success('配置已保存，智能匹配与预警阈值即时生效')
    await load()
  } catch (e) {
    /* 拦截器已提示 */
  } finally {
    saving.value = false
  }
}

const restoreDefault = async () => {
  try {
    await ElMessageBox.confirm(
      '确认恢复系统参数默认值？智能匹配权重、信用规则与预警阈值将重置为出厂配置。',
      '恢复默认',
      { type: 'warning', confirmButtonText: '恢复默认', cancelButtonText: '取消' }
    )
  } catch (e) {
    return
  }
  try {
    await adminApi.resetConfig()
    ElMessage.success('已恢复默认配置')
    await load()
  } catch (e) {
    /* 拦截器已提示 */
  }
}

onMounted(load)
</script>

<style scoped>
.cfg-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.1fr) minmax(0, 1fr);
  gap: 14px;
  margin-bottom: 14px;
  align-items: start;
}

.cfg-grid2 {
  display: grid;
  grid-template-columns: minmax(0, 1.1fr) minmax(0, 1fr);
  gap: 14px;
  align-items: start;
}

.right-col {
  display: flex;
  flex-direction: column;
  gap: 14px;
  min-width: 0;
}

.sum-chip {
  padding: 2px 10px;
  border-radius: 8px;
  background: #e8f7ee;
  color: #15803d;
  font-size: 12px;
  font-weight: 600;
  font-family: var(--font-num);
}

.sum-chip.bad {
  background: #fdeaea;
  color: #b91c1c;
}

.tip-bar {
  margin-bottom: 16px;
  padding: 9px 12px;
  border-radius: 9px;
  background: #f2fbf5;
  border: 1px dashed #cbeed9;
  font-size: 12px;
  color: #3f6b55;
  line-height: 1.7;
}

.w-list {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.w-row {
  display: grid;
  grid-template-columns: minmax(0, 1.02fr) minmax(0, 1fr) 48px;
  align-items: center;
  gap: 14px;
}

.w-name {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  font-weight: 600;
  color: #1b2a24;
}

.w-tag {
  padding: 0 5px;
  border-radius: 5px;
  background: #f1f5f4;
  color: #64748b;
  font-size: 10.5px;
  font-weight: 700;
  line-height: 16px;
}

.w-desc {
  margin-top: 3px;
  font-size: 11.5px;
  color: var(--c-muted-2);
}

.w-slider {
  padding: 0 2px;
}

.w-slider :deep(.el-slider__runway) {
  height: 5px;
  background: #eef3f1;
}

.w-slider :deep(.el-slider__bar) {
  height: 5px;
  background: linear-gradient(90deg, #4ade80, #16a34a);
}

.w-slider :deep(.el-slider__button) {
  width: 14px;
  height: 14px;
  border: 2px solid #16a34a;
}

.w-val {
  text-align: right;
  font-size: 13px;
  font-weight: 700;
  color: #15803d;
  font-family: var(--font-num);
}

.w-foot {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 16px;
  padding-top: 12px;
  border-top: 1px dashed var(--c-border);
  font-size: 11.5px;
  color: #15803d;
}

.w-foot.bad {
  color: #b45309;
}

.credit-list {
  display: flex;
  flex-direction: column;
}

.rule-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 9px 0;
  border-bottom: 1px dashed var(--c-border);
}

.rule-row:last-child {
  border-bottom: none;
}

.rr-name {
  font-size: 12.5px;
  font-weight: 600;
  color: #1b2a24;
}

.rr-desc {
  margin-top: 2px;
  font-size: 11.5px;
  color: var(--c-muted-2);
}

.rr-val {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  flex-shrink: 0;
}

.unit {
  font-size: 12px;
  color: var(--c-muted);
}

.card-note {
  margin-top: 12px;
  padding-top: 10px;
  border-top: 1px dashed var(--c-border);
  font-size: 11.5px;
  color: var(--c-muted-2);
  line-height: 1.7;
}

.kv-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 9px 0;
  border-bottom: 1px dashed var(--c-border);
}

.kv-row:last-child {
  border-bottom: none;
}

.kv-name {
  font-size: 12.5px;
  font-weight: 600;
  color: #1b2a24;
}

.kv-desc {
  margin-top: 2px;
  font-size: 11.5px;
  color: var(--c-muted-2);
}

.kv-val {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  flex-shrink: 0;
}

.sw-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 9px 0;
  border-bottom: 1px dashed var(--c-border);
}

.sw-row:last-child {
  border-bottom: none;
}

.sw-name {
  font-size: 12.5px;
  font-weight: 600;
  color: #1b2a24;
}

.sw-desc {
  margin-top: 2px;
  font-size: 11.5px;
  color: var(--c-muted-2);
}

@media (max-width: 1280px) {
  .cfg-grid,
  .cfg-grid2 {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
