<script setup lang="ts">
import { computed, ref } from 'vue'
import { formatClock, formatTick, formatValue, niceScale, useElementWidth, type ChartSeries } from '../utils/chart'

const props = withDefaults(defineProps<{
  series: ChartSeries[]
  /** Window bounds in epoch seconds. The x-axis always spans the requested window, so gaps stay visible. */
  start: number
  end: number
  stepSeconds: number
  unit: string
  label: string
  threshold?: number | null
  height?: number
}>(), { threshold: null, height: 168 })

const host = ref<HTMLElement | null>(null)
const width = useElementWidth(host)
const pad = { top: 12, right: 12, bottom: 24, left: 44 }
const plotWidth = computed(() => Math.max(120, width.value - pad.left - pad.right))
const plotHeight = computed(() => props.height - pad.top - pad.bottom)

const isAvailability = computed(() => props.unit === 'up')
const scale = computed(() => {
  if (isAvailability.value) return { max: 1, ticks: [0, 1] }
  let max = 0
  for (const item of props.series) for (const [, value] of item.points) max = Math.max(max, value)
  if (props.threshold !== null) max = Math.max(max, props.threshold * 1.25)
  return niceScale(max)
})
const span = computed(() => Math.max(1, props.end - props.start))
const x = (t: number) => pad.left + ((t - props.start) / span.value) * plotWidth.value
const y = (v: number) => pad.top + plotHeight.value - (Math.min(v, scale.value.max) / scale.value.max) * plotHeight.value

/** Missing samples break the line instead of being interpolated across. */
function segments(points: [number, number][]) {
  const result: [number, number][][] = []
  let current: [number, number][] = []
  const gap = props.stepSeconds * 2.5
  for (const point of points) {
    const previous = current[current.length - 1]
    if (previous && point[0] - previous[0] > gap) {
      result.push(current)
      current = []
    }
    current.push(point)
  }
  if (current.length) result.push(current)
  return result
}

const paths = computed(() => props.series.map((item) => {
  const parts = segments(item.points)
  const line = parts.map((part) => part.map(([t, v], index) => `${index ? 'L' : 'M'}${x(t).toFixed(1)},${y(v).toFixed(1)}`).join('')).join('')
  const baseline = y(0).toFixed(1)
  const area = props.series.length === 1 && !isAvailability.value
    ? parts.map((part) => `M${x(part[0][0]).toFixed(1)},${baseline}${part.map(([t, v]) => `L${x(t).toFixed(1)},${y(v).toFixed(1)}`).join('')}L${x(part[part.length - 1][0]).toFixed(1)},${baseline}Z`).join('')
    : ''
  const last = item.points[item.points.length - 1]
  return { label: item.label, color: item.color, line, area, last, lonely: parts.filter((part) => part.length === 1) }
}))

const xTicks = computed(() => {
  const count = width.value < 420 ? 3 : 5
  const withDate = span.value > 12 * 3600
  return Array.from({ length: count }, (_, index) => {
    const t = props.start + (span.value * index) / (count - 1)
    return { t, label: formatClock(t, withDate && index === 0) }
  })
})

const timestamps = computed(() => {
  const set = new Set<number>()
  for (const item of props.series) for (const [t] of item.points) set.add(t)
  return [...set].sort((a, b) => a - b)
})
const hasData = computed(() => timestamps.value.length > 0)
const cursor = ref<number | null>(null)
const cursorTime = computed(() => (cursor.value === null ? null : timestamps.value[cursor.value] ?? null))
const readout = computed(() => {
  const t = cursorTime.value
  if (t === null) return null
  return {
    t,
    left: x(t),
    rows: props.series.map((item) => ({ label: item.label, color: item.color, value: item.points.find(([pt]) => pt === t)?.[1] ?? null })),
  }
})

function nearestIndex(clientX: number) {
  const box = host.value?.getBoundingClientRect()
  if (!box || !timestamps.value.length) return null
  const t = props.start + ((clientX - box.left - pad.left) / plotWidth.value) * span.value
  let best = 0
  for (let index = 1; index < timestamps.value.length; index++) {
    if (Math.abs(timestamps.value[index] - t) < Math.abs(timestamps.value[best] - t)) best = index
  }
  return best
}

function onPointer(event: PointerEvent) { cursor.value = nearestIndex(event.clientX) }
function onKey(event: KeyboardEvent) {
  if (!timestamps.value.length) return
  const last = timestamps.value.length - 1
  if (event.key === 'ArrowLeft') cursor.value = Math.max(0, (cursor.value ?? last + 1) - 1)
  else if (event.key === 'ArrowRight') cursor.value = Math.min(last, (cursor.value ?? -1) + 1)
  else if (event.key === 'Home') cursor.value = 0
  else if (event.key === 'End') cursor.value = last
  else if (event.key === 'Escape') cursor.value = null
  else return
  event.preventDefault()
}

const tooltipStyle = computed(() => {
  if (!readout.value) return {}
  const flip = readout.value.left > width.value * 0.6
  return flip ? { right: `${width.value - readout.value.left + 10}px` } : { left: `${readout.value.left + 10}px` }
})

const tableRows = computed(() => [...timestamps.value].reverse().map((t) => ({
  t,
  values: props.series.map((item) => item.points.find(([pt]) => pt === t)?.[1] ?? null),
})))
</script>

<template>
  <figure class="chart">
    <ul v-if="series.length > 1" class="legend" aria-label="图例">
      <li v-for="item in series" :key="item.label"><i :style="{ background: item.color }" aria-hidden="true" />{{ item.label }}</li>
    </ul>
    <div
      ref="host"
      class="plot"
      :style="{ height: `${height}px` }"
      :tabindex="hasData ? 0 : -1"
      role="img"
      :aria-label="`${label}。${hasData ? '使用左右方向键逐点读取数值。' : '本窗口没有样本。'}`"
      @pointermove="onPointer"
      @pointerleave="cursor = null"
      @keydown="onKey"
      @blur="cursor = null"
    >
      <svg :width="width" :height="height" aria-hidden="true">
        <g class="grid">
          <line v-for="tick in scale.ticks" :key="`g${tick}`" :x1="pad.left" :x2="pad.left + plotWidth" :y1="y(tick)" :y2="y(tick)" />
        </g>
        <g class="y-labels">
          <text v-for="tick in scale.ticks" :key="`y${tick}`" :x="pad.left - 8" :y="y(tick) + 4" text-anchor="end">{{ isAvailability ? (tick ? 'UP' : 'DOWN') : formatTick(tick, unit) }}</text>
        </g>
        <g class="x-labels">
          <text v-for="(tick, index) in xTicks" :key="`x${tick.t}`" :x="x(tick.t)" :y="height - 6" :text-anchor="index === 0 ? 'start' : index === xTicks.length - 1 ? 'end' : 'middle'">{{ tick.label }}</text>
        </g>
        <g v-if="threshold !== null" class="threshold">
          <line :x1="pad.left" :x2="pad.left + plotWidth" :y1="y(threshold)" :y2="y(threshold)" />
          <text :x="pad.left + plotWidth" :y="y(threshold) - 5" text-anchor="end">阈值 {{ formatTick(threshold, unit) }}</text>
        </g>
        <g v-for="path in paths" :key="path.label">
          <path v-if="path.area" class="area" :d="path.area" :style="{ fill: path.color }" />
          <path class="line" :d="path.line" :style="{ stroke: path.color }" />
          <circle v-for="point in path.lonely" :key="`p${point[0][0]}`" class="dot" :cx="x(point[0][0])" :cy="y(point[0][1])" r="3" :style="{ fill: path.color }" />
          <circle v-if="path.last" class="end" :cx="x(path.last[0])" :cy="y(path.last[1])" r="4" :style="{ fill: path.color }" />
        </g>
        <line v-if="readout" class="crosshair" :x1="readout.left" :x2="readout.left" :y1="pad.top" :y2="pad.top + plotHeight" />
      </svg>
      <p v-if="!hasData" class="empty">本窗口没有样本 · NO_DATA</p>
      <div v-if="readout" class="tooltip" :style="tooltipStyle" role="status">
        <time class="mono">{{ formatClock(readout.t, true) }}</time>
        <div v-for="row in readout.rows" :key="row.label" class="row">
          <strong class="mono">{{ formatValue(row.value, unit) }}</strong>
          <span><i :style="{ background: row.color }" aria-hidden="true" />{{ row.label }}</span>
        </div>
      </div>
    </div>
    <details v-if="hasData" class="table-view">
      <summary>数据表</summary>
      <div class="table-scroll">
        <table>
          <thead><tr><th scope="col">时间</th><th v-for="item in series" :key="item.label" scope="col">{{ item.label }}</th></tr></thead>
          <tbody>
            <tr v-for="row in tableRows" :key="row.t">
              <td class="mono">{{ formatClock(row.t, true) }}</td>
              <td v-for="(value, index) in row.values" :key="index" class="mono">{{ formatValue(value, unit) }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </details>
  </figure>
</template>

<style scoped>
.chart { margin: 0; min-width: 0; }
.legend { display: flex; flex-wrap: wrap; gap: 4px 14px; margin: 0 0 6px; padding: 0; list-style: none; color: var(--text-soft); font-size: 12px; }
.legend li { display: inline-flex; align-items: center; gap: 6px; }
.legend i, .row i { display: inline-block; width: 12px; height: 2px; border-radius: 2px; }
.plot { position: relative; outline: none; border-radius: var(--radius-sm); touch-action: pan-y; }
.plot:focus-visible { box-shadow: var(--focus-ring); }
svg { display: block; overflow: visible; }
.grid line { stroke: var(--chart-grid); stroke-width: 1; }
.y-labels text, .x-labels text { fill: var(--text-muted); font-size: 11px; font-family: var(--font-mono); font-variant-numeric: tabular-nums; }
.threshold line { stroke: var(--warning); stroke-width: 1; stroke-dasharray: 4 4; }
.threshold text { fill: var(--text-soft); font-size: 11px; }
.line { fill: none; stroke-width: 2; stroke-linejoin: round; stroke-linecap: round; }
.area { opacity: .1; }
.end, .dot { stroke: var(--surface-1); stroke-width: 2; }
.crosshair { stroke: var(--text-muted); stroke-width: 1; }
.empty { position: absolute; inset: 0; display: grid; place-items: center; margin: 0; color: var(--text-muted); font-size: 13px; pointer-events: none; }
.tooltip { position: absolute; z-index: 2; top: 4px; display: grid; gap: 4px; min-width: 132px; border: 1px solid var(--line); border-radius: var(--radius-sm); background: var(--surface-3); padding: 8px 10px; pointer-events: none; font-size: 12px; }
.tooltip time { color: var(--text-muted); }
.row { display: flex; align-items: baseline; justify-content: space-between; gap: 12px; }
.row strong { color: var(--text); font-weight: 620; }
.row span { display: inline-flex; align-items: center; gap: 6px; color: var(--text-muted); }
.table-view { margin-top: 6px; }
.table-view summary { display: inline-flex; min-height: 32px; align-items: center; cursor: pointer; color: var(--text-muted); font-size: 12px; }
.table-view summary:hover { color: var(--text-soft); }
.table-scroll { max-height: 220px; overflow: auto; border-top: 1px solid var(--line-soft); }
table { width: 100%; border-collapse: collapse; font-size: 12px; }
th, td { padding: 5px 8px; border-bottom: 1px solid var(--line-soft); text-align: right; }
th:first-child, td:first-child { text-align: left; }
th { position: sticky; top: 0; background: var(--surface-1); color: var(--text-muted); font-weight: 600; }
td { font-variant-numeric: tabular-nums; color: var(--text-soft); }
</style>
