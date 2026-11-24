<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2 class="page-title">异常上报</h2>
        <p class="page-sub">质检不合格、冷链温度超标、配送异常等情况及时上报，纳入食安预警处置</p>
      </div>
      <div class="page-actions">
        <el-button plain @click="load"><el-icon><Refresh /></el-icon>刷新</el-button>
      </div>
    </div>

    <div class="grid-e">
      <!-- 上报表单 -->
      <div class="card">
        <div class="card-head">
          <div class="card-title">异常上报</div>
          <div class="card-extra">上报后自动生成食安预警</div>
        </div>
        <div class="card-pad">
          <el-form :model="form" label-position="top" class="ex-form">
            <el-form-item label="异常类型" required>
              <el-radio-group v-model="form.type" size="default">
                <el-radio-button v-for="t in types" :key="t" :label="t">{{ t }}</el-radio-button>
              </el-radio-group>
            </el-form-item>

            <el-form-item label="风险等级" required>
              <div class="level-group">
                <button
                  v-for="lv in levels" :key="lv" class="level-btn"
                  :class="['lv-' + lv, { active: form.level === lv }]"
                  @click="form.level = lv"
                >
                  {{ lv }}风险
                </button>
              </div>
            </el-form-item>

            <el-form-item label="关联对象">
              <el-input v-model="form.target" placeholder="订单号 / 批次号 / 车辆" clearable />
            </el-form-item>

            <el-form-item label="异常描述" required>
              <el-input
                v-model="form.content" type="textarea" :rows="4" maxlength="200" show-word-limit
                placeholder="请描述异常现象、发生时间与已采取的措施（不少于 10 个字）"
              />
            </el-form-item>

            <el-form-item label="现场照片">
              <div class="photo-row">
                <div v-for="(p, i) in photos" :key="p.url || p.name" class="photo-ph has-photo">
                  <img v-if="p.preview" :src="p.preview" :alt="p.name" class="photo-img" />
                  <div v-else class="photo-done">
                    <el-icon><Picture /></el-icon><span class="photo-name">{{ p.name }}</span>
                  </div>
                  <span class="photo-del" title="移除这张" @click.stop="removePhoto(i)">×</span>
                </div>
                <!-- 真实上传：走 /api/files/upload，失败会明确报错，不再只弹"演示环境" -->
                <div v-if="photos.length < 3" class="photo-ph" :class="{ busy: uploading }" @click="pickPhoto">
                  <el-icon><Plus /></el-icon>
                  <span>{{ uploading ? '上传中' : '上传' }}</span>
                </div>
              </div>
              <div class="photo-hint">
                支持 JPG / PNG / WEBP，单张不超过 5MB，最多 3 张；照片随预警一并存档，管理员处置时可查看
              </div>
            </el-form-item>
          </el-form>

          <div class="form-actions">
            <el-button plain @click="reset">重置</el-button>
            <el-button type="primary" :loading="submitting" @click="submit">提交上报</el-button>
          </div>
        </div>
      </div>

      <!-- 处理流程 -->
      <div class="side-col">
        <div class="card">
          <div class="card-head">
            <div class="card-title">处理流程</div>
            <div class="card-extra">闭环 5 步</div>
          </div>
          <div class="card-pad">
            <div class="timeline">
              <div v-for="(s, i) in steps" :key="i" class="timeline-item">
                <span class="timeline-dot done"></span>
                <div class="timeline-time">步骤 {{ i + 1 }}</div>
                <div class="timeline-title">{{ s.title }}</div>
                <div class="timeline-desc">{{ s.desc }}</div>
              </div>
            </div>
          </div>
        </div>

        <div class="card">
          <div class="card-head">
            <div class="card-title">异常判定标准</div>
          </div>
          <div class="card-pad">
            <div v-for="(r, i) in rules" :key="i" class="rule-item">
              <span class="rule-icon" :style="{ background: r.bg, color: r.color }">
                <el-icon><component :is="r.icon" /></el-icon>
              </span>
              <div>
                <div class="rule-title">{{ r.title }}</div>
                <div class="rule-desc">{{ r.desc }}</div>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { commonApi, inspectorApi } from '@/api'
import { useAutoRefresh } from '@/utils/useAutoRefresh'

const submitting = ref(false)
const uploading = ref(false)
const photos = ref([])
const types = ref(['质检异常', '温度异常', '配送异常', '其他异常'])
const levels = ref(['高', '中', '低'])

const form = reactive({
  type: '质检异常',
  level: '中',
  target: '',
  content: ''
})

const steps = [
  { title: '现场取证拍照', desc: '对异常现场、批次标签、温度仪表拍照留存' },
  { title: '填报异常并定级', desc: '选择异常类型、风险等级并描述现象' },
  { title: '系统生成食安预警', desc: '自动写入食安预警台账，管理员可见' },
  { title: '管理员处置', desc: '核实后决定驳回、退货、扣减信用或复检' },
  { title: '结果回执', desc: '处置结果回写预警状态，形成闭环' }
]

const rules = [
  { title: '质检不合格', desc: '农残、重金属、含水率、新鲜度任一项不达标', icon: 'CircleCloseFilled', color: '#b91c1c', bg: '#fdeaea' },
  { title: '温度超出 2~8℃', desc: '冷链运输或到货中心温度超出标准区间', icon: 'Sunny', color: '#b45309', bg: '#fdf2e2' },
  { title: '配送超时', desc: '超出约定送达时间 2 小时以上', icon: 'Clock', color: '#1d4ed8', bg: '#e8efff' },
  { title: '数量差异', desc: '实收数量与订单数量差异超过 5%', icon: 'ScaleToOriginal', color: '#0f766e', bg: '#e6f6f4' }
]

const load = async () => {
  try {
    const res = await inspectorApi.exceptions()
    const d = res.data || {}
    if (d.types) types.value = d.types
    if (d.levels) levels.value = d.levels
    if (!types.value.includes(form.type)) form.type = types.value[0]
  } catch (e) {
    /* 使用默认值 */
  }
}

const reset = () => {
  form.type = types.value[0]
  form.level = '中'
  form.target = ''
  form.content = ''
  photos.value.forEach((p) => p.preview && URL.revokeObjectURL(p.preview))
  photos.value = []
}

/* 现场照片：真实上传到服务端（与质检页同一套接口），提交时带服务端地址留痕。
   原来这里是三个只弹"演示环境"的假格子——点了没反应是用户最反感的一类。 */
const pickPhoto = () => {
  if (uploading.value) return
  const input = document.createElement('input')
  input.type = 'file'
  input.accept = 'image/*'
  input.multiple = true
  input.onchange = async () => {
    const files = Array.from(input.files || []).slice(0, 3 - photos.value.length)
    if (!files.length) return
    uploading.value = true
    try {
      for (const f of files) {
        if (f.size > 5 * 1024 * 1024) {
          ElMessage.warning(f.name + ' 超过 5MB，已跳过')
          continue
        }
        const res = await commonApi.upload(f, 'exception')
        photos.value.push({
          name: res.data?.name || f.name,
          url: res.data?.url || '',
          preview: URL.createObjectURL(f)
        })
      }
      if (photos.value.length) ElMessage.success(`已上传 ${photos.value.length} 张现场照片`)
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

const submit = async () => {
  if (!form.content || form.content.trim().length < 10) {
    ElMessage.warning('请填写不少于 10 个字的异常描述')
    return
  }
  submitting.value = true
  try {
    await inspectorApi.reportException({
      type: form.type,
      content: form.content.trim(),
      target: form.target || '—',
      level: form.level,
      photos: photos.value.map((p) => p.url).filter(Boolean)
    })
    ElMessage.success(photos.value.length
      ? `异常已上报（含 ${photos.value.length} 张现场照片），管理员将在食安预警中跟进处理`
      : '异常已上报，管理员将在食安预警中跟进处理')
    reset()
  } finally {
    submitting.value = false
  }
}

useAutoRefresh(load)
onMounted(load)
</script>

<style scoped>
.grid-e {
  display: grid;
  grid-template-columns: minmax(0, 1.18fr) minmax(0, 1fr);
  gap: 14px;
  align-items: start;
}

.side-col {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.ex-form :deep(.el-form-item__label) {
  font-size: var(--fs-sm);
  color: #4b5563;
  padding-bottom: 6px;
}

.ex-form :deep(.el-radio-button__inner) {
  padding: 7px 14px;
  font-size: var(--fs-sm);
}

.level-group {
  display: flex;
  gap: 10px;
}

.level-btn {
  padding: 8px 20px;
  border-radius: 9px;
  border: 1px solid var(--c-border-strong);
  background: #fff;
  font-size: var(--fs-sm);
  color: #4b5563;
  cursor: pointer;
  transition: all 0.18s;
}

.level-btn.lv-高.active {
  border-color: var(--danger);
  background: #fef2f2;
  color: var(--danger);
  font-weight: 600;
}

.level-btn.lv-中.active {
  border-color: var(--warn);
  background: var(--warn-soft);
  color: var(--warn);
  font-weight: 600;
}

.level-btn.lv-低.active {
  border-color: var(--muted);
  background: #f9fafb;
  color: var(--ink-2);
  font-weight: 600;
}

.photo-row {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
}

.photo-ph {
  position: relative;
  width: 84px;
  height: 66px;
  border-radius: 9px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 3px;
  font-size: var(--fs-2xs);
  border: 1px dashed var(--line-strong);
  background: var(--accent-soft);
  color: var(--accent);
  cursor: pointer;
  overflow: hidden;
  transition: border-color 0.15s, background 0.15s;
}

.photo-ph:hover {
  border-color: var(--accent);
}

.photo-ph.busy {
  opacity: 0.6;
  cursor: progress;
}

.photo-ph.has-photo {
  border-style: solid;
  border-color: var(--line);
  background: var(--surface-2);
  cursor: default;
}

.photo-img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

.photo-done {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
  color: var(--ink-2);
  padding: 4px;
}

.photo-name {
  max-width: 74px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: var(--fs-2xs);
}

.photo-del {
  position: absolute;
  top: 2px;
  right: 4px;
  width: 16px;
  height: 16px;
  line-height: 14px;
  text-align: center;
  border-radius: 50%;
  background: rgba(0, 0, 0, 0.55);
  color: #fff;
  font-size: 12px;
  cursor: pointer;
}

.photo-hint {
  font-size: var(--fs-xs);
  color: var(--c-muted-2);
  margin-top: 6px;
}

.form-actions {
  display: flex;
  gap: 10px;
  margin-top: 8px;
  padding-top: 16px;
  border-top: 1px solid var(--c-border);
}

.rule-item {
  display: flex;
  gap: 11px;
  padding: 11px 0;
  border-bottom: 1px dashed var(--c-border);
}

.rule-item:last-child {
  border-bottom: none;
}

.rule-icon {
  width: 32px;
  height: 32px;
  border-radius: 9px;
  display: grid;
  place-items: center;
  font-size: var(--fs-md);
  flex-shrink: 0;
}

.rule-title {
  font-size: var(--fs-sm);
  font-weight: 600;
  color: var(--ink);
}

.rule-desc {
  font-size: var(--fs-xs);
  color: var(--c-muted);
  margin-top: 3px;
  line-height: 1.6;
}
</style>
