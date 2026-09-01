<script setup lang="ts">
import { computed } from 'vue'
import type { Evidence } from '../api/types'
const props = defineProps<{ items: Evidence[]; selected?: string }>()
const emit = defineEmits<{ select: [id: string] }>()
const points = computed(() => props.items.slice(0, 60).map((item, index) => ({ item, x: props.items.length <= 1 ? 50 : 4 + (index / (Math.min(props.items.length, 60) - 1)) * 92, y: 88 - Math.max(0, Math.min(100, item.importance)) * .7 })))
const path = computed(() => points.value.map((point, index) => `${index ? 'L' : 'M'} ${point.x} ${point.y}`).join(' '))
const canDirectlySelect = computed(() => points.value.length <= 12)
</script>

<template>
  <div class="plot" role="group" :aria-label="canDirectlySelect ? '按采集顺序展示证据重要度，可直接选择证据点' : '按采集顺序展示证据重要度；密集证据请通过事件时间线选择'">
    <svg viewBox="0 0 100 100" preserveAspectRatio="none" aria-hidden="true"><path class="grid" d="M0 25H100M0 50H100M0 75H100"/><path v-if="points.length > 1" class="line" :d="path"/><circle v-for="point in points" :key="point.item.id" :cx="point.x" :cy="point.y" r="1.8" :class="{ selected: point.item.id === selected }" /></svg>
    <div v-if="canDirectlySelect" class="plot-points" aria-label="选择证据">
      <button v-for="point in points" :key="`control-${point.item.id}`" type="button" :class="{ selected: point.item.id === selected }" :style="{ left: `${point.x}%`, top: `${point.y}%` }" :aria-label="`${point.item.type} evidence，importance ${point.item.importance.toFixed(1)}`" :aria-pressed="point.item.id === selected" @click="emit('select', point.item.id)" />
    </div>
  </div>
</template>

<style scoped>
.plot { position: relative; height: 184px; border-bottom: 1px solid var(--line-soft); }
svg { width: 100%; height: 184px; overflow: visible; }.grid { stroke: var(--line-soft); stroke-width: .35; }.line { fill: none; stroke: var(--accent); stroke-width: 1.15; vector-effect: non-scaling-stroke; }
circle { fill: var(--surface-1); stroke: var(--accent); stroke-width: 1; vector-effect: non-scaling-stroke; }.selected { fill: var(--accent); stroke: var(--text); }
.plot-points { position: absolute; inset: 0; }.plot-points button { position: absolute; width: 44px; height: 44px; border: 0; border-radius: 50%; background: transparent; cursor: pointer; transform: translate(-50%, -50%); }.plot-points button::after { content: ""; position: absolute; inset: 16px; border: 1px solid transparent; border-radius: 50%; }.plot-points button:hover::after, .plot-points button:focus-visible::after { border-color: var(--accent-strong); }.plot-points button.selected::after { border-color: var(--text); background: var(--accent); }
</style>
