<template>
  <div class="dmap" :class="{ 'is-empty': !ready }">
    <!-- 无坐标时明确说不能画，不拿假地图糊弄 -->
    <div v-if="!ready" class="dmap-empty">
      <el-icon><Location /></el-icon>
      <span>{{ reason || '该配送单缺少起终点坐标，无法绘制轨迹' }}</span>
    </div>

    <template v-else>
      <svg :viewBox="`0 0 ${W} ${H}`" class="dmap-svg" preserveAspectRatio="xMidYMid meet">
        <defs>
          <pattern id="dm-grid" width="34" height="34" patternUnits="userSpaceOnUse">
            <path d="M34 0H0V34" fill="none" :stroke="C.line" stroke-width="1" />
          </pattern>
          <linearGradient id="dm-road" x1="0" y1="0" x2="1" y2="0">
            <stop offset="0%" :stop-color="C.accent" />
            <stop offset="100%" :stop-color="C.info" />
          </linearGradient>
          <radialGradient id="dm-glow">
            <stop offset="0%" :stop-color="C.accent" stop-opacity="0.30" />
            <stop offset="100%" :stop-color="C.accent" stop-opacity="0" />
          </radialGradient>
        </defs>

        <rect :width="W" :height="H" fill="url(#dm-grid)" opacity="0.55" />

        <!-- 已完成路段 -->
        <path :d="donePath" fill="none" :stroke="C.accent" stroke-width="4.5"
              stroke-linecap="round" stroke-linejoin="round" />
        <!-- 剩余路段（虚线） -->
        <path :d="restPath" fill="none" :stroke="C.line" stroke-width="4.5"
              stroke-dasharray="7 7" stroke-linecap="round" />

        <!-- 起点 -->
        <g :transform="`translate(${pFrom.x} ${pFrom.y})`">
          <circle r="9" :fill="C.surface" :stroke="C.accent" stroke-width="3" />
          <circle r="3.5" :fill="C.accent" />
        </g>
        <!-- 终点 -->
        <g :transform="`translate(${pTo.x} ${pTo.y})`">
          <circle r="11" :fill="C.surface" :stroke="C.info" stroke-width="3" />
          <rect x="-4.5" y="-4.5" width="9" height="9" rx="2" :fill="C.info" />
        </g>

        <!-- 车辆：沿路径移动 -->
        <g :transform="`translate(${carPos.x} ${carPos.y})`" class="dmap-car">
          <circle r="26" fill="url(#dm-glow)" />
          <circle r="13" :fill="C.surface" :stroke="moving ? C.accent : C.muted" stroke-width="2.5" />
          <g :transform="`rotate(${carAngle})`">
            <path d="M-5.5 -3.6 L5.8 0 L-5.5 3.6 Z" :fill="moving ? C.accent : C.muted" />
          </g>
        </g>

        <!-- 地名标注：起点标签必须抬到车辆光晕（r=26）之外，
             否则刚发车时车正好压在起点上，把地名挡住 -->
        <g :transform="`translate(${pFrom.x} ${pFrom.y})`">
          <text class="dmap-label" :y="fromLabelY" text-anchor="middle">{{ shortName(originName) }}</text>
        </g>
        <g :transform="`translate(${pTo.x} ${pTo.y})`">
          <text class="dmap-label" :y="toLabelY" text-anchor="middle">{{ shortName(destName) }}</text>
        </g>

        <!-- 比例尺 -->
        <g :transform="`translate(16 ${H - 22})`">
          <line x1="0" y1="0" :x2="scaleLen" y2="0" :stroke="C.muted" stroke-width="2" />
          <line x1="0" y1="-4" x2="0" y2="4" :stroke="C.muted" stroke-width="2" />
          <line :x1="scaleLen" y1="-4" :x2="scaleLen" y2="4" :stroke="C.muted" stroke-width="2" />
          <text :x="scaleLen / 2" y="-7" text-anchor="middle" class="dmap-scale">{{ scaleText }}</text>
        </g>
      </svg>

      <!-- 浮层指标 -->
      <div class="dmap-hud">
        <div class="hud-item">
          <span class="hud-k">状态</span>
          <span class="hud-v" :class="{ live: moving && !arrived }">{{ hudStatus }}</span>
        </div>
        <div class="hud-item">
          <span class="hud-k">已行驶</span>
          <span class="hud-v">{{ fmtNum(doneKm) }} km</span>
        </div>
        <div class="hud-item">
          <span class="hud-k">剩余</span>
          <span class="hud-v">{{ fmtNum(remainKm) }} km</span>
        </div>
        <div class="hud-item">
          <span class="hud-k">预计到达</span>
          <span class="hud-v">{{ etaText }}</span>
        </div>
        <div class="hud-item">
          <span class="hud-k">车厢温度</span>
          <span class="hud-v" :class="{ bad: temperatureAlarm }">
            {{ temperature == null ? '—' : temperature + '℃' }}
          </span>
        </div>
      </div>

      <div class="dmap-foot">
        路线为配送里程示意（起终点直线距离 {{ fmtNum(straightKm) }} km，实际行驶 {{ fmtNum(roadKm) }} km），
        位置按发车时间与平均车速推算；实际路径以导航为准。
      </div>
    </template>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { fmtNum } from '@/utils/format'

/* 自绘 SVG 地图：不依赖任何外网瓦片服务（内网/离线都能用），
   所有坐标来自后端 /track 接口，不在这里生成任何假位置。 */
const props = defineProps({
  track: { type: Object, default: () => ({}) }
})

const W = 680
const H = 300
const PAD = 52

const C = computed(() => {
  const cs = getComputedStyle(document.documentElement)
  const get = (n, fb) => cs.getPropertyValue(n).trim() || fb
  return {
    accent: get('--accent', '#16a34a'),
    info: get('--info', '#3b82f6'),
    line: get('--line', '#dfe7e3'),
    surface: get('--surface', '#ffffff'),
    muted: get('--muted', '#7c8b84'),
    danger: get('--danger', '#dc2626')
  }
})

const path = computed(() => (Array.isArray(props.track?.path) ? props.track.path : []))
const ready = computed(() => props.track?.hasGeo && path.value.length > 1)
const reason = computed(() => (props.track?.hasGeo === false
  ? '该配送单的农户或食堂还没有坐标，无法绘制轨迹' : ''))
const moving = computed(() => props.track?.status === '配送中')
const statusText = computed(() => props.track?.status || '—')
/* 按发车时间推算的进度跑满、但配送单还没扫码交付时，
   不该继续显示「运输中 / 0 分钟」，要明确是「到了、等人验收」。 */
const arrived = computed(() => moving.value && Number(props.track?.progress || 0) >= 1)
const hudStatus = computed(() => {
  if (arrived.value) return '已抵达 · 待扫码交付'
  return moving.value ? '运输中' : statusText.value
})
const doneKm = computed(() => Number(props.track?.doneKm || 0))
const remainKm = computed(() => Number(props.track?.remainKm || 0))
const roadKm = computed(() => Number(props.track?.roadKm || 0))
const straightKm = computed(() => Number(props.track?.straightKm || 0))
const temperature = computed(() => props.track?.temperature)
const temperatureAlarm = computed(() => !!props.track?.temperatureAlarm)
const originName = computed(() => props.track?.origin?.name || '起点')
const destName = computed(() => props.track?.destination?.name || '终点')
const etaText = computed(() => {
  if (props.track?.status === '已送达') return '已送达'
  if (props.track?.status !== '配送中') return '待发车'
  if (arrived.value) return '已抵达'
  const m = props.track?.etaMinutes
  if (m == null) return '—'
  return `${props.track?.eta || ''}（${m} 分钟）`
})

/* 经纬度 → 视口坐标：等比映射，保证不走形 */
const bbox = computed(() => {
  const lngs = path.value.map((p) => p[0])
  const lats = path.value.map((p) => p[1])
  const minLng = Math.min(...lngs)
  const maxLng = Math.max(...lngs)
  const minLat = Math.min(...lats)
  const maxLat = Math.max(...lats)
  return { minLng, maxLng, minLat, maxLat }
})
/** 1 度经度对应的实际距离约为纬度的 cos(lat) 倍，做等比缩放才不会被压扁 */
const latScale = computed(() => {
  const b = bbox.value
  return Math.cos((((b.minLat + b.maxLat) / 2) * Math.PI) / 180)
})
const project = (lng, lat) => {
  const b = bbox.value
  const spanLng = Math.max(1e-6, (b.maxLng - b.minLng) * latScale.value)
  const spanLat = Math.max(1e-6, b.maxLat - b.minLat)
  // 用同一个缩放因子，取能装下的较小者
  const k = Math.min((W - PAD * 2) / spanLng, (H - PAD * 2) / spanLat)
  const w = spanLng * k
  const h = spanLat * k
  const ox = (W - w) / 2
  const oy = (H - h) / 2
  return {
    x: ox + (lng - b.minLng) * latScale.value * k,
    // 纬度越大越靠上
    y: oy + (b.maxLat - lat) * k
  }
}

const pts = computed(() => path.value.map((p) => project(p[0], p[1])))
const toD = (arr) => arr.map((p, i) => `${i === 0 ? 'M' : 'L'}${p.x.toFixed(2)} ${p.y.toFixed(2)}`).join(' ')

const idx = computed(() => {
  const n = pts.value.length - 1
  const i = Number(props.track?.pathIndex)
  if (Number.isFinite(i) && i >= 0) return Math.max(0, Math.min(n, i))
  const prog = Number(props.track?.progress || 0)
  return Math.round(prog * n)
})
const donePath = computed(() => toD(pts.value.slice(0, idx.value + 1)))
const restPath = computed(() => toD(pts.value.slice(Math.max(0, idx.value))))
const pFrom = computed(() => pts.value[0] || { x: 0, y: 0 })
const pTo = computed(() => pts.value[pts.value.length - 1] || { x: 0, y: 0 })

/* 标签位置：默认放点的上方，上方空间不够就翻到下方，避免被 viewBox 裁掉。
   起终点都用 -34：车辆光晕半径 26 + 车体 13，发车时车压起点、抵达时车压终点，
   标签抬得不够就会被车挡住。 */
const fromLabelY = computed(() => (pFrom.value.y > 50 ? -34 : 30))
const toLabelY = computed(() => (pTo.value.y > 50 ? -34 : 30))
const carPos = computed(() => pts.value[idx.value] || pFrom.value)
const carAngle = computed(() => {
  const a = pts.value[Math.max(0, idx.value - 1)] || carPos.value
  const b = pts.value[Math.min(pts.value.length - 1, idx.value + 1)] || carPos.value
  return (Math.atan2(b.y - a.y, b.x - a.x) * 180) / Math.PI
})

/* 比例尺：取一个「好看」的整公里数 */
const scaleLen = computed(() => {
  if (!ready.value) return 0
  const b = bbox.value
  const spanLng = Math.max(1e-6, (b.maxLng - b.minLng) * latScale.value)
  const spanLat = Math.max(1e-6, b.maxLat - b.minLat)
  const k = Math.min((W - PAD * 2) / spanLng, (H - PAD * 2) / spanLat)
  const totalKm = Math.max(spanLng * 96.8, spanLat * 111)
  const pxPerKm = (Math.max(spanLng, spanLat) * k) / totalKm
  for (const km of [1, 2, 5, 10, 20, 50, 100]) {
    if (km * pxPerKm >= 46) return km * pxPerKm
  }
  return 46
})
const scaleText = computed(() => {
  const b = bbox.value
  const spanLng = Math.max(1e-6, (b.maxLng - b.minLng) * latScale.value)
  const spanLat = Math.max(1e-6, b.maxLat - b.minLat)
  const k = Math.min((W - PAD * 2) / spanLng, (H - PAD * 2) / spanLat)
  const totalKm = Math.max(spanLng * 96.8, spanLat * 111)
  const pxPerKm = (Math.max(spanLng, spanLat) * k) / totalKm
  return Math.round(scaleLen.value / pxPerKm) + ' km'
})

const shortName = (s) => {
  const t = String(s || '').replace(/基地$/, '')
  return t.length > 9 ? t.slice(0, 8) + '…' : t
}
</script>

<style scoped>
.dmap {
  position: relative;
  width: 100%;
  border: 1px solid var(--line);
  border-radius: 12px;
  background: var(--surface);
  overflow: hidden;
}

.dmap-empty {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  height: 240px;
  color: var(--muted);
  font-size: var(--fs-sm);
}

.dmap-svg {
  display: block;
  width: 100%;
  height: auto;
}

.dmap-label {
  font-size: 11px;
  font-weight: 600;
  fill: var(--ink);
  paint-order: stroke;
  stroke: var(--surface);
  stroke-width: 3px;
}

.dmap-scale {
  font-size: 10px;
  fill: var(--muted);
}

/* 车辆：运输中轻微呼吸，强调「正在移动」 */
.dmap-car circle:nth-child(2) {
  transition: all 0.5s linear;
}
.dmap-car {
  transition: transform 1s linear;
}
@media (prefers-reduced-motion: no-preference) {
  .dmap-car > circle:first-child {
    animation: dm-pulse 2.2s ease-in-out infinite;
  }
}
@keyframes dm-pulse {
  0%, 100% { opacity: 0.55; transform: scale(1); }
  50% { opacity: 1; transform: scale(1.16); }
}

.dmap-hud {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 22px;
  padding: 10px 14px;
  border-top: 1px solid var(--line);
  background: var(--surface-2);
}

.hud-item {
  display: flex;
  align-items: baseline;
  gap: 6px;
  font-size: var(--fs-xs);
}

.hud-k { color: var(--muted); }
.hud-v { font-weight: 700; color: var(--ink); }
.hud-v.live { color: var(--accent); }
.hud-v.bad { color: var(--danger); }

.dmap-foot {
  padding: 7px 14px 9px;
  font-size: var(--fs-2xs);
  color: var(--muted);
  line-height: 1.5;
}
</style>
