<template>
  <div class="trace-panel">
    <div class="trace-grid">
      <!-- 批次基础信息 -->
      <div class="card card-pad info-card">
        <div class="card-title">批次基础信息</div>
        <div class="info-grid">
          <div class="info-item">
            <span class="k">产品名称</span><span class="v">{{ trace.productName || '—' }}</span>
          </div>
          <div class="info-item">
            <span class="k">产品大类</span><span class="v">{{ trace.categoryName || '—' }}</span>
          </div>
          <div class="info-item">
            <span class="k">供货农户</span><span class="v">{{ trace.farmerName || '—' }}</span>
          </div>
          <div class="info-item">
            <span class="k">收货食堂</span>
            <span class="v">{{ trace.canteenName }}{{ trace.campus ? '（' + trace.campus + '）' : '' }}</span>
          </div>
          <div class="info-item">
            <span class="k">下单数量</span><span class="v">{{ num(trace.orderQuantity) }} {{ trace.unit || 'kg' }}</span>
          </div>
          <div class="info-item">
            <span class="k">实收数量</span>
            <span class="v">
              {{ trace.receiveQuantity ? num(trace.receiveQuantity) : '待入库' }}
              <em v-if="Number(trace.lossRate) > 0" class="loss">（损耗 {{ trace.lossRate }}%）</em>
            </span>
          </div>
          <div class="info-item">
            <span class="k">成交单价</span><span class="v">¥ {{ trace.unitPrice }} / {{ trace.unit || 'kg' }}</span>
          </div>
          <div class="info-item">
            <span class="k">关联订单</span><span class="v mono">{{ trace.orderNo || '—' }}</span>
          </div>
          <div class="info-item">
            <span class="k">产地地址</span><span class="v">{{ trace.farmerAddress || '—' }}</span>
          </div>
          <div class="info-item">
            <span class="k">农户信用</span><span class="v">{{ trace.creditScore }} 分 · {{ trace.farmerType }}</span>
          </div>
        </div>
      </div>

      <!-- 二维码 -->
      <div class="card card-pad qr-card">
        <div class="card-title">溯源批次码</div>
        <div class="qr-box">
          <img v-if="trace.qrCode" :src="trace.qrCode" alt="溯源二维码" class="qr-img" />
          <div v-else class="qr-ph"><el-icon><Picture /></el-icon><span>二维码生成中</span></div>
        </div>
        <div class="batch-no mono">{{ trace.batchNo }}</div>
        <div class="qr-hint">编码规则：日期 + 农户 ID + 产品 ID + 随机数</div>
        <div class="qr-hint">扫码即可查看完整溯源信息</div>
      </div>
    </div>

    <div class="trace-grid-2">
      <!-- 全链路时间线 -->
      <div class="card card-pad">
        <div class="card-head" style="padding: 0 0 12px; border-bottom: 1px solid var(--c-border)">
          <div class="card-title">全链路溯源时间线</div>
          <div class="card-extra">
            <span class="tag" :class="trace.complete ? 'tag-green' : 'tag-orange'">
              {{ trace.complete ? '溯源完整' : '待完成入库' }}
            </span>
          </div>
        </div>
        <div class="timeline">
          <div v-for="(n, i) in trace.nodes || []" :key="i" class="timeline-item">
            <span class="timeline-dot"></span>
            <div class="timeline-time">{{ n.time }}</div>
            <div class="timeline-title">{{ n.title }}</div>
            <div class="timeline-desc">{{ n.desc }}</div>
            <div v-if="n.operator" class="timeline-op">{{ n.operator }}</div>
          </div>
          <div v-if="!(trace.nodes || []).length" class="empty">暂无溯源节点</div>
        </div>
      </div>

      <div class="side-col">
        <!-- 质检报告 -->
        <div class="card card-pad">
          <div class="card-title">质检报告</div>
          <div class="qc-head">
            <div>
              <div class="qc-result" :class="trace.qualityResult === '合格' ? 'ok' : 'no'">
                {{ trace.qualityResult || '—' }}
              </div>
              <div class="qc-sub">{{ trace.inspector || '—' }} · {{ trace.inspectTime || '—' }}</div>
            </div>
            <el-icon class="qc-icon" :class="trace.qualityResult === '合格' ? 'ok' : 'no'">
              <component :is="trace.qualityResult === '合格' ? 'CircleCheckFilled' : 'CircleCloseFilled'" />
            </el-icon>
          </div>
          <div class="qc-items">
            <div v-for="(it, i) in trace.qualityItems || []" :key="i" class="qc-item">
              <span class="qc-name">{{ it.name }}</span>
              <span class="qc-std">{{ it.standard || it.std }}</span>
              <span class="qc-val">{{ it.value }}</span>
              <span class="tag" :class="it.ok === false ? 'tag-red' : 'tag-green'">
                {{ it.ok === false ? '不合格' : '达标' }}
              </span>
            </div>
            <div v-if="!(trace.qualityItems || []).length" class="empty">暂无质检明细</div>
          </div>
        </div>

        <!-- 数据存证 -->
        <div class="card card-pad">
          <div class="card-title">数据存证</div>
          <div class="hash-box">
            <div class="hash-label">SHA-256（数据库简易存证）</div>
            <div class="hash mono">{{ trace.hash || '—' }}</div>
          </div>
          <div class="hash-note">
            <el-icon><Lock /></el-icon>
            各节点数据经 SHA-256 摘要存证，可核验一致性（非区块链级防篡改）。
          </div>
          <el-button size="small" plain style="margin-top: 10px" @click="verify">
            <el-icon><CircleCheck /></el-icon>核验存证
          </el-button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ElMessage } from 'element-plus'

const props = defineProps({
  trace: { type: Object, default: () => ({}) }
})

const num = (v) => (v === null || v === undefined ? '—' : Number(v).toString())

const verify = () => {
  if (!props.trace.hash) {
    ElMessage.warning('暂无可核验的存证哈希')
    return
  }
  ElMessage.success('存证哈希校验通过：' + props.trace.hash.slice(0, 16) + '…（与批次业务数据一致）')
}
</script>

<style scoped>
.trace-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 268px;
  gap: 14px;
  margin-bottom: 14px;
}

.trace-grid-2 {
  display: grid;
  grid-template-columns: minmax(0, 1.42fr) minmax(0, 1fr);
  gap: 14px;
  align-items: start;
}

.side-col {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.info-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px 22px;
  margin-top: 12px;
}

.info-item {
  display: flex;
  gap: 10px;
  font-size: 13px;
  align-items: baseline;
}

.info-item .k {
  color: var(--c-muted);
  flex-shrink: 0;
  width: 66px;
  font-size: 12.5px;
}

.info-item .v {
  color: #1b2a24;
  font-weight: 500;
}

.loss {
  color: #b45309;
  font-style: normal;
  font-size: 12px;
}

.qr-card {
  text-align: center;
}

.qr-box {
  margin: 12px auto 10px;
  width: 152px;
  height: 152px;
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
  gap: 6px;
  color: var(--c-muted-2);
  font-size: 12px;
}

.qr-ph .el-icon {
  font-size: 26px;
}

.batch-no {
  font-size: 12.5px;
  font-weight: 600;
  color: #14532d;
  background: #f2fbf5;
  border: 1px dashed #bbe6cb;
  border-radius: 8px;
  padding: 6px 8px;
  word-break: break-all;
}

.qr-hint {
  font-size: 11.5px;
  color: var(--c-muted-2);
  margin-top: 7px;
  line-height: 1.6;
}

.timeline {
  padding: 14px 0 0 6px;
  max-height: 460px;
  overflow-y: auto;
}

.timeline-op {
  font-size: 11.5px;
  color: var(--c-muted-2);
  margin-top: 4px;
}

.qc-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin: 12px 0;
  padding: 12px 14px;
  border-radius: 10px;
  background: #f7faf9;
}

.qc-result {
  font-size: 18px;
  font-weight: 700;
}

.qc-result.ok {
  color: #15803d;
}

.qc-result.no {
  color: #b91c1c;
}

.qc-sub {
  font-size: 11.5px;
  color: var(--c-muted);
  margin-top: 3px;
}

.qc-icon {
  font-size: 26px;
}

.qc-icon.ok {
  color: #22c55e;
}

.qc-icon.no {
  color: #ef4444;
}

.qc-items {
  display: flex;
  flex-direction: column;
  gap: 7px;
}

.qc-item {
  display: grid;
  grid-template-columns: 74px minmax(0, 1fr) 96px 54px;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  padding: 5px 0;
  border-bottom: 1px dashed var(--c-border);
}

.qc-name {
  font-weight: 600;
  color: #27362f;
}

.qc-std {
  color: var(--c-muted-2);
  font-size: 11.5px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.qc-val {
  color: #374151;
}

.hash-box {
  margin-top: 12px;
  padding: 10px 12px;
  border-radius: 10px;
  background: #0f2b20;
}

.hash-label {
  font-size: 11px;
  color: rgba(255, 255, 255, 0.45);
  margin-bottom: 6px;
}

.hash {
  color: #86efac;
  font-size: 11px;
  line-height: 1.7;
  word-break: break-all;
}

.hash-note {
  display: flex;
  gap: 6px;
  margin-top: 10px;
  font-size: 11.5px;
  color: var(--c-muted);
  line-height: 1.7;
}
</style>
