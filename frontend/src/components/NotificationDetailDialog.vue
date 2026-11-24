<template>
  <el-dialog
    v-model="visible"
    width="560px"
    :show-close="true"
    class="ntf-dialog"
    append-to-body
    destroy-on-close
  >
    <template #header>
      <div class="ntf-head">
        <span class="ntf-type">{{ bizLabel(item?.bizType) }}</span>
        <span class="ntf-state" :class="item?.isRead ? 'is-read' : 'is-unread'">
          {{ item?.isRead ? '已读' : '未读' }}
        </span>
        <span class="ntf-time">{{ fullTime(item?.createTime) }}</span>
      </div>
    </template>

    <div v-if="item" class="ntf">
      <h3 class="ntf-title">{{ item.title || '业务通知' }}</h3>
      <p class="ntf-body">{{ item.content || '（该通知没有正文）' }}</p>

      <dl class="ntf-grid">
        <div class="ntf-row">
          <dt>发送人</dt>
          <dd>{{ item.senderName || '系统' }}<span v-if="item.senderRole" class="ntf-sub"> · {{ roleLabel(item.senderRole) }}</span></dd>
        </div>
        <div class="ntf-row">
          <dt>接收人</dt>
          <dd>{{ item.recipientName || '我' }}</dd>
        </div>
        <div class="ntf-row">
          <dt>业务环节</dt>
          <dd>{{ bizLabel(item.bizType) }}</dd>
        </div>
        <div class="ntf-row">
          <dt>关联单据</dt>
          <dd class="ntf-mono">{{ item.bizNo || '—' }}</dd>
        </div>
      </dl>

      <p v-if="!item.isRead" class="ntf-tip">
        未读通知是待处理的线索，阅读后才能删除。
      </p>
    </div>

    <template #footer>
      <el-button @click="visible = false">关闭</el-button>
      <el-button v-if="canGo" type="primary" @click="goHandle">去处理这张单</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  item: { type: Object, default: null }
})
const emit = defineEmits(['update:modelValue', 'handle'])

const visible = computed({
  get: () => props.modelValue,
  set: (v) => emit('update:modelValue', v)
})

const ROLE_LABELS = { ADMIN: '系统管理员', FARMER: '农户/合作社', PURCHASER: '食堂采购员', INSPECTOR: '质检配送员' }
const BIZ_LABELS = {
  DEPART: '配送发车', ARRIVE: '扫码交付', INBOUND: '入库确认',
  SHIP: '接单发货', QUALITY: '质检结果', SETTLE: '结算审核'
}
const roleLabel = (r) => ROLE_LABELS[r] || r || ''
const bizLabel = (b) => BIZ_LABELS[b] || b || '业务通知'

const canGo = computed(() => !!(props.item && (props.item.route || props.item.orderId)))

const pad = (n) => String(n).padStart(2, '0')
const fullTime = (s) => {
  const raw = String(s || '').replace('T', ' ')
  if (raw.length < 19) return raw || '—'
  return raw.slice(0, 19)
}

const goHandle = () => {
  visible.value = false
  emit('handle', props.item)
}
</script>

<style scoped>
.ntf-head { display: flex; align-items: center; gap: 8px; }
.ntf-type {
  font-size: var(--fs-2xs); font-weight: 700; color: var(--accent);
  background: var(--accent-soft); padding: 2px 8px; border-radius: var(--radius);
}
.ntf-state {
  font-size: var(--fs-2xs); padding: 2px 8px; border-radius: var(--radius);
  border: 1px solid var(--line); color: var(--muted);
}
.ntf-state.is-unread {
  color: var(--accent); border-color: color-mix(in srgb, var(--accent) 40%, transparent);
  background: color-mix(in srgb, var(--accent) 10%, transparent);
}
.ntf-time { margin-left: auto; font-size: var(--fs-2xs); color: var(--muted); font-family: var(--font-num); }

.ntf-title { margin: 0 0 10px; font-size: var(--fs-lg); font-weight: 700; color: var(--ink); line-height: 1.45; }
.ntf-body {
  margin: 0; font-size: var(--fs-sm); line-height: 1.8; color: var(--ink-2);
  white-space: pre-wrap; word-break: break-word;
  background: var(--surface-2); border: 1px solid var(--line);
  border-radius: var(--radius); padding: 12px 14px;
}
.ntf-grid { margin: 14px 0 0; display: grid; gap: 8px; }
.ntf-row { display: flex; gap: 12px; align-items: baseline; }
.ntf-row dt { flex: 0 0 64px; font-size: var(--fs-xs); color: var(--muted); }
.ntf-row dd { margin: 0; font-size: var(--fs-sm); color: var(--ink); }
.ntf-sub { color: var(--muted); }
.ntf-mono { font-family: var(--font-num); }
.ntf-tip {
  margin: 14px 0 0; font-size: var(--fs-2xs); color: var(--muted);
  padding: 8px 10px; border-radius: var(--radius);
  background: var(--surface-2); border: 1px dashed var(--line-strong);
}
</style>
