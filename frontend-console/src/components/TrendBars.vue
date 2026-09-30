<script setup lang="ts">
import { computed, ref } from 'vue'
import type { TrendDay } from '../api/types'
import { niceScale, useElementWidth } from '../utils/chart'

const props = withDefaults(defineProps<{ items: TrendDay[]; height?: number }>(), { height: 132 })

const host = ref<HTMLElement | null>(null)
const width = useElementWidth(host)
const pad = { top: 8, right: 4, bottom: 22, left: 28 }
const plotWidth = computed(() => Math.max(120, width.value - pad.left - pad.right))
const plotHeight = computed(() => props.height - pad.top - pad.bottom)
const scale = computed(() => niceScale(Math.max(4, ...props.items.map((item) => item.opened))))
const band = computed(() => plotWidth.value / Math.max(1, props.items.length))
const barWidth = computed(() => Math.min(24, band.value * 0.62))
const y = (value: number) => pad.top + plotHeight.value - (value / scale.value.max) * plotHeight.value

const GAP = 2
const RADIUS = 4

/** Column with a rounded data-end and a square baseline. */
function column(x: number, top: number, bottom: number, rounded: boolean) {
  const h = bottom - top
  if (h <= 0) return ''
  const r = rounded ? Math.min(RADIUS, h, barWidth.value / 2) : 0
  const w = barWidth.value
  return `M${x},${bottom}V${top + r}Q${x},${top} ${x + r},${top}H${x + w - r}Q${x + w},${top} ${x + w},${top + r}V${bottom}Z`
}

const bars = computed(() => props.items.map((item, index) => {
  const x = pad.left + band.value * index + (band.value - barWidth.value) / 2
  const base = y(0)
  const criticalTop = y(item.critical)
  const otherCount = item.opened - item.critical
  const otherTop = y(item.opened)
  return {
    item,
    index,
    x,
    critical: column(x, criticalTop, base, otherCount === 0),
    other: otherCount > 0 ? column(x, otherTop, item.critical > 0 ? criticalTop - GAP : base, true) : '',
  }
}))

const labelEvery = computed(() => (width.value < 480 ? 4 : 2))
const shortDay = (day: string) => day.slice(5)
const active = ref<number | null>(null)
const activeBar = computed(() => (active.value === null ? null : bars.value[active.value]))
const tooltipStyle = computed(() => {
  const bar = activeBar.value
  if (!bar) return {}
  const center = bar.x + barWidth.value / 2
  return center > width.value * 0.6 ? { right: `${width.value - center + 12}px` } : { left: `${center + 12}px` }
})

function onKey(event: KeyboardEvent) {
  const last = props.items.length - 1
  if (last < 0) return
  if (event.key === 'ArrowLeft') active.value = Math.max(0, (active.value ?? last + 1) - 1)
  else if (event.key === 'ArrowRight') active.value = Math.min(last, (active.value ?? -1) + 1)
  else if (event.key === 'Escape') active.value = null
  else return
  event.preventDefault()
}
</script>

<template>
  <figure class="trend">
    <ul class="legend" aria-label="图例">
      <li><i class="key other" aria-hidden="true" />WARNING / INFO</li>
      <li><i class="key critical" aria-hidden="true" />CRITICAL</li>
    </ul>
    <div
      ref="host"
      class="plot"
      :style="{ height: `${height}px` }"
      tabindex="0"
      role="img"
      :aria-label="`最近 ${items.length} 天每日新开事件数。使用左右方向键逐日读取。`"
      @keydown="onKey"
      @blur="active = null"
      @pointerleave="active = null"
    >
      <svg :width="width" :height="height" aria-hidden="true">
        <g class="grid">
          <line v-for="tick in scale.ticks" :key="tick" :x1="pad.left" :x2="pad.left + plotWidth" :y1="y(tick)" :y2="y(tick)" />
          <text v-for="tick in scale.ticks" :key="`t${tick}`" :x="pad.left - 8" :y="y(tick) + 4" text-anchor="end">{{ tick }}</text>
        </g>
        <g v-for="bar in bars" :key="bar.item.day" :class="{ dim: active !== null && active !== bar.index }">
          <path v-if="bar.critical" class="critical" :d="bar.critical" />
          <path v-if="bar.other" class="other" :d="bar.other" />
          <text v-if="bar.index % labelEvery === labelEvery - 1 || bar.index === items.length - 1" class="day" :x="bar.x + barWidth / 2" :y="height - 6" text-anchor="middle">{{ shortDay(bar.item.day) }}</text>
          <rect class="hit" :x="pad.left + band * bar.index" :y="pad.top" :width="band" :height="plotHeight" @pointerenter="active = bar.index" />
        </g>
      </svg>
      <div v-if="activeBar" class="tooltip" :style="tooltipStyle" role="status">
        <time class="mono">{{ activeBar.item.day }} UTC</time>
        <div class="row"><strong class="mono">{{ activeBar.item.opened }}</strong><span>新开事件</span></div>
        <div class="row"><strong class="mono">{{ activeBar.item.critical }}</strong><span>其中 CRITICAL</span></div>
        <div class="row"><strong class="mono">{{ activeBar.item.resolved }}</strong><span>当天恢复</span></div>
      </div>
    </div>
    <details class="table-view">
      <summary>数据表</summary>
      <table>
        <thead><tr><th scope="col">日期（UTC）</th><th scope="col">新开</th><th scope="col">CRITICAL</th><th scope="col">恢复</th></tr></thead>
        <tbody>
          <tr v-for="item in [...items].reverse()" :key="item.day"><td class="mono">{{ item.day }}</td><td class="mono">{{ item.opened }}</td><td class="mono">{{ item.critical }}</td><td class="mono">{{ item.resolved }}</td></tr>
        </tbody>
      </table>
    </details>
  </figure>
</template>

<style scoped>
.trend { margin: 0; }
.legend { display: flex; gap: 14px; margin: 0 0 6px; padding: 0; list-style: none; color: var(--text-soft); font-size: 12px; }
.legend li { display: inline-flex; align-items: center; gap: 6px; }
.key { display: inline-block; width: 10px; height: 10px; border-radius: 2px; }
.key.other, .other { fill: var(--series-1); background: var(--series-1); }
.key.critical, .critical { fill: var(--critical); background: var(--critical); }
.plot { position: relative; outline: none; border-radius: var(--radius-sm); }
.plot:focus-visible { box-shadow: var(--focus-ring); }
svg { display: block; overflow: visible; }
.grid line { stroke: var(--chart-grid); stroke-width: 1; }
.grid text, .day { fill: var(--text-muted); font-size: 11px; font-family: var(--font-mono); font-variant-numeric: tabular-nums; }
.dim path { opacity: .45; }
.hit { fill: transparent; }
.tooltip { position: absolute; z-index: 2; top: 0; display: grid; gap: 3px; min-width: 128px; border: 1px solid var(--line); border-radius: var(--radius-sm); background: var(--surface-3); padding: 8px 10px; pointer-events: none; font-size: 12px; }
.tooltip time { color: var(--text-muted); }
.row { display: flex; align-items: baseline; justify-content: space-between; gap: 12px; }
.row strong { color: var(--text); font-weight: 620; }
.row span { color: var(--text-muted); }
.table-view { margin-top: 6px; }
.table-view summary { display: inline-flex; min-height: 32px; align-items: center; cursor: pointer; color: var(--text-muted); font-size: 12px; }
table { width: 100%; border-collapse: collapse; font-size: 12px; }
th, td { padding: 5px 8px; border-bottom: 1px solid var(--line-soft); text-align: right; }
th:first-child, td:first-child { text-align: left; }
th { color: var(--text-muted); font-weight: 600; }
td { color: var(--text-soft); font-variant-numeric: tabular-nums; }
</style>
