<script setup lang="ts">
defineProps<{ value: string }>()
const tone = (value: string) => ['UP', 'RESOLVED', 'CLOSED', 'COMPLETED', 'SUPPORTED'].includes(value) ? 'success'
  : ['DOWN', 'FAILED', 'CRITICAL', 'REJECTED'].includes(value) ? 'critical'
  : ['WARNING', 'DIAGNOSING', 'FALLBACK', 'PENDING', 'OPEN'].includes(value) ? 'warning'
  : ['UNKNOWN', 'INSUFFICIENT_EVIDENCE', 'NO_DETERMINISTIC_RULE'].includes(value) ? 'unknown' : 'neutral'
</script>

<template><span class="badge" :data-tone="tone(value)"><i aria-hidden="true" /><span>{{ value }}</span></span></template>

<style scoped>
.badge { display: inline-flex; max-width: 100%; align-items: center; gap: 7px; color: var(--text-soft); font: 600 10px/1.2 var(--font-mono); letter-spacing: .035em; white-space: nowrap; }
.badge i { width: 7px; height: 7px; flex: 0 0 auto; border: 1px solid currentColor; border-radius: 50%; background: transparent; }
.badge[data-tone="success"] { color: var(--success); }.badge[data-tone="success"] i { background: var(--success); }
.badge[data-tone="warning"] { color: var(--warning); }.badge[data-tone="warning"] i { background: var(--warning); }
.badge[data-tone="critical"] { color: var(--critical); }.badge[data-tone="critical"] i { background: var(--critical); }
.badge[data-tone="unknown"] { color: var(--unknown); }.badge[data-tone="unknown"] i { border-style: dashed; }
</style>
