<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import AppShell from '../components/AppShell.vue'
import StatePanel from '../components/StatePanel.vue'
import StatusBadge from '../components/StatusBadge.vue'
import { consoleApi } from '../api/client'
import type { IncidentSummary } from '../api/types'

const items = ref<IncidentSummary[]>([]), cursor = ref<string | null>(null), hasMore = ref(false), failed = ref(false), loading = ref(true)
const status = ref(''), severity = ref(''), search = ref(''); let debounce: number | undefined
const format = (value: string) => new Intl.DateTimeFormat('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false }).format(new Date(value))
async function load(append = false) { loading.value = true; failed.value = false; try { const { data } = await consoleApi.incidents({ limit: 20, cursor: append ? cursor.value || undefined : undefined, status: status.value || undefined, severity: severity.value || undefined, search: search.value || undefined }); items.value = append ? [...items.value, ...data.items] : data.items; cursor.value = data.nextCursor; hasMore.value = data.hasMore } catch { failed.value = true } finally { loading.value = false } }
watch([status, severity], () => load()); watch(search, () => { clearTimeout(debounce); debounce = window.setTimeout(() => load(), 320) }); onMounted(() => load())
</script>

<template>
  <AppShell section="incidents">
    <header class="page-head"><h1 class="page-title">Incidents</h1></header>
    <section class="filters" aria-label="Incident 筛选"><input v-model="search" maxlength="100" placeholder="搜索标题或告警名称" aria-label="搜索 Incident"><select v-model="status" aria-label="状态"><option value="">全部状态</option><option v-for="value in ['OPEN', 'DIAGNOSING', 'DIAGNOSED', 'FAILED', 'RESOLVED', 'CLOSED']" :key="value">{{ value }}</option></select><select v-model="severity" aria-label="严重级别"><option value="">全部级别</option><option v-for="value in ['INFO', 'WARNING', 'CRITICAL']" :key="value">{{ value }}</option></select></section>
    <StatePanel v-if="failed" kind="error" title="Incident 列表不可达" detail="请检查 ops-api 控制面，而不是把 API 离线误判成没有告警。" action="重试" @action="load()"/>
    <section v-else class="records" aria-label="Incident records"><div class="table-head"><span>Last signal</span><span>Incident</span><span>Service</span><span>Status</span><span>Evidence</span></div><TransitionGroup name="record" tag="div"><RouterLink v-for="item in items" :key="item.id" :to="{ name: 'casefile', params: { id: item.id } }" class="row"><time class="mono">{{ format(item.lastAlertAt) }}</time><div><strong>{{ item.title }}</strong><small class="mono">{{ item.id }}</small></div><span class="service">{{ item.primaryService }}</span><StatusBadge :value="item.status"/><span class="mono evidence">{{ item.evidenceCount }}</span></RouterLink></TransitionGroup><div v-if="loading" class="loading" role="status">正在读取受控摘要…</div><StatePanel v-else-if="!items.length" title="没有符合条件的 Incident" detail="调整筛选条件，或等待受控告警进入。"/><button v-if="hasMore && !loading" class="action-button more" type="button" @click="load(true)">加载下一页</button></section>
  </AppShell>
</template>

<style scoped>
.page-head { margin-bottom: 30px; }.filters { display: grid; grid-template-columns: minmax(260px, 1fr) 164px 164px; gap: 8px; margin-bottom: 26px; border-bottom: 1px solid var(--line); padding-bottom: 16px; }.filters input, .filters select { min-height: 42px; border: 1px solid var(--line); border-radius: var(--radius-sm); background: var(--surface-1); padding: 0 12px; color: var(--text); }.filters input::placeholder { color: var(--text-faint); }.filters input:focus, .filters select:focus { outline: none; border-color: var(--accent); box-shadow: var(--focus-ring); }.records { border-block: 1px solid var(--line); }.table-head, .row { display: grid; grid-template-columns: 150px minmax(250px, 1.5fr) minmax(120px, .72fr) 122px 68px; gap: 18px; align-items: center; padding: 14px 12px; }.table-head { color: var(--text-muted); font: 600 10px/1.3 var(--font-mono); letter-spacing: .07em; text-transform: uppercase; }.row { min-height: 69px; border-top: 1px solid var(--line-soft); transition: background var(--duration-fast) var(--ease); }.row:hover { background: var(--surface-1); }.row time, .row small, .evidence { color: var(--text-muted); font-size: 10px; }.row strong { display: block; font-size: 14px; }.row small { display: block; max-width: 260px; margin-top: 3px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }.service { color: var(--text-soft); font-size: 12px; }.loading { border-top: 1px solid var(--line-soft); padding: 24px 8px; color: var(--text-muted); text-align: center; }.more { display: block; margin: 18px auto; }
@media (max-width: 850px) { .filters { grid-template-columns: 1fr 1fr; }.filters input { grid-column: 1 / -1; }.table-head { display: none; }.row { grid-template-columns: minmax(0, 1fr) auto; }.row > time, .row .service, .row .evidence { display: none; } }
@media (max-width: 480px) { .filters { grid-template-columns: 1fr; }.filters input { grid-column: auto; }.row { gap: 12px; padding-inline: 0; } }
</style>
