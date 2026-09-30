<script setup lang="ts">
import { computed } from 'vue'
import type { HistoryPoint } from '../../shared/homeops'

const props = defineProps<{ points: HistoryPoint[]; label: string; unit: string; color: string; rangeLabel: string }>()
const width = 420
const height = 116
const padding = 4
const values = computed(() => props.points.map((point) => point.value))
const min = computed(() => values.value.length ? Math.min(...values.value) : 0)
const max = computed(() => values.value.length ? Math.max(...values.value) : 0)
const last = computed(() => values.value.at(-1) ?? null)
const path = computed(() => {
  if (props.points.length < 2) return ''
  const span = Math.max(1, max.value - min.value)
  return props.points.map((point, index) => {
    const x = padding + index / (props.points.length - 1) * (width - padding * 2)
    const y = height - padding - (point.value - min.value) / span * (height - padding * 2)
    return `${index === 0 ? 'M' : 'L'}${x.toFixed(1)} ${y.toFixed(1)}`
  }).join(' ')
})
</script>

<template>
  <section class="sparkline">
    <div class="sparkline-heading">
      <div><span>{{ label }}</span><strong>{{ last === null ? '--' : last.toFixed(1) }}<small>{{ unit }}</small></strong></div>
      <span class="range">{{ min.toFixed(0) }} 至 {{ max.toFixed(0) }}{{ unit }}</span>
    </div>
    <svg :viewBox="`0 0 ${width} ${height}`" role="img" :aria-label="`${label} ${rangeLabel} 趋势，当前 ${last ?? '未知'}${unit}`" preserveAspectRatio="none">
      <line v-for="line in [0.25, 0.5, 0.75]" :key="line" x1="0" :y1="height * line" :x2="width" :y2="height * line" class="grid-line" />
      <path v-if="path" :d="path" fill="none" :stroke="color" stroke-width="2.2" vector-effect="non-scaling-stroke" />
    </svg>
  </section>
</template>
