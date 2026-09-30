<script setup lang="ts">
import type { IncidentSummary } from '../api/types'
import { formatLocal } from '../utils/time'
import StatusBadge from './StatusBadge.vue'

defineProps<{ item: IncidentSummary; layout?: 'inbox' | 'directory' }>()
</script>

<template>
  <article class="record" :data-layout="layout || 'inbox'">
    <RouterLink class="main" :to="{ name: 'casefile', params: { id: item.id } }">
      <strong>{{ item.title }}</strong>
      <small>{{ item.primaryService }}</small>
    </RouterLink>
    <StatusBadge v-if="layout === 'directory'" domain="severity" :value="item.severity" explain />
    <StatusBadge domain="incident" :value="item.status" explain />
    <time class="mono">{{ formatLocal(item.lastAlertAt, true) }}</time>
    <span class="evidence">{{ item.evidenceCount }} 条证据</span>
  </article>
</template>

<style scoped>
.record {
  display: grid;
  grid-template-columns: minmax(0, 1.6fr) auto auto auto;
  gap: 8px 16px;
  align-items: center;
  border-top: 1px solid var(--line-soft);
  padding: 12px 0;
}
.record[data-layout="directory"] { grid-template-columns: minmax(0, 1.4fr) auto auto auto auto; }
.main { min-width: 0; display: grid; gap: 4px; }
strong { font-size: 14px; font-weight: 620; overflow-wrap: anywhere; }
small, time, .evidence { color: var(--text-muted); font-size: 13px; overflow-wrap: anywhere; }
.main:hover strong { color: var(--accent-strong); }
@media (max-width: 900px) {
  .record, .record[data-layout="directory"] { grid-template-columns: minmax(0, 1fr) auto; }
  time { grid-column: 1; }
  .evidence { grid-column: 2; justify-self: end; }
}
@media (max-width: 560px) {
  .record, .record[data-layout="directory"] { grid-template-columns: 1fr; }
  .evidence { justify-self: start; }
}
</style>
