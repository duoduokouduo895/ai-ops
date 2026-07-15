<script setup lang="ts">
defineProps<{ value: string }>()
const tone = (value: string) => ['UP', 'RESOLVED', 'CLOSED', 'COMPLETED', 'SUPPORTED'].includes(value) ? 'success'
  : ['DOWN', 'FAILED', 'CRITICAL', 'REJECTED'].includes(value) ? 'critical'
  : ['WARNING', 'DIAGNOSING', 'UNKNOWN', 'FALLBACK'].includes(value) ? 'warning' : 'neutral'
</script>
<template><span class="badge" :data-tone="tone(value)"><i />{{ value }}</span></template>
<style scoped>
.badge { display:inline-flex; align-items:center; gap:7px; font: 600 11px/1 var(--font-mono); letter-spacing:.03em; color:var(--text-soft); }
.badge i { width:7px; height:7px; border-radius:50%; background:var(--text-muted); }
.badge[data-tone="success"] { color:var(--success); }.badge[data-tone="success"] i{background:var(--success)}
.badge[data-tone="warning"] { color:var(--warning); }.badge[data-tone="warning"] i{background:var(--warning)}
.badge[data-tone="critical"] { color:var(--critical); }.badge[data-tone="critical"] i{background:var(--critical)}
</style>
