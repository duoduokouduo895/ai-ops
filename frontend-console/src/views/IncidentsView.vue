<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AppShell from '../components/AppShell.vue'
import HintTerm from '../components/HintTerm.vue'
import IncidentRecord from '../components/IncidentRecord.vue'
import PageHeader from '../components/PageHeader.vue'
import StatePanel from '../components/StatePanel.vue'
import { consoleApi } from '../api/client'
import type { IncidentSummary } from '../api/types'
import { useIncidentsListStore } from '../stores/incidentsList'
import { isUnauthorized } from '../utils/http'

defineOptions({ name: 'IncidentsView' })

const STATUSES = ['OPEN', 'DIAGNOSING', 'DIAGNOSED', 'FAILED', 'RESOLVED', 'CLOSED']
const SEVERITIES = ['INFO', 'WARNING', 'CRITICAL']

const route = useRoute()
const router = useRouter()
const list = useIncidentsListStore()
const items = ref<IncidentSummary[]>([])
const cursor = ref<string | null>(null)
const hasMore = ref(false)
const failed = ref(false)
const appendFailed = ref(false)
const loading = ref(false)
const searchInput = ref(queryValue('search'))
let debounce: number | undefined
let requestId = 0

const filters = computed(() => ({
  search: queryValue('search'),
  status: queryValue('status'),
  severity: queryValue('severity'),
}))

function queryValue(key: string) {
  const value = route.query[key]
  return typeof value === 'string' ? value : ''
}

function writeQuery(patch: Record<string, string>) {
  const query: Record<string, string> = {}
  const next = { ...filters.value, ...patch }
  if (next.search) query.search = next.search
  if (next.status) query.status = next.status
  if (next.severity) query.severity = next.severity
  void router.replace({ query })
}

async function load(append = false) {
  const id = ++requestId
  loading.value = true
  if (!append) {
    failed.value = false
    appendFailed.value = false
  } else {
    appendFailed.value = false
  }
  try {
    const { data } = await consoleApi.incidents({
      limit: 20,
      cursor: append ? cursor.value || undefined : undefined,
      status: filters.value.status || undefined,
      severity: filters.value.severity || undefined,
      search: filters.value.search || undefined,
    })
    if (id !== requestId) return
    items.value = append ? [...items.value, ...data.items] : data.items
    cursor.value = data.nextCursor
    hasMore.value = data.hasMore
  } catch (error) {
    if (id !== requestId || isUnauthorized(error)) return
    if (append) appendFailed.value = true
    else failed.value = true
  } finally {
    if (id === requestId) loading.value = false
  }
}

watch(() => route.name === 'incidents' ? filters.value : null, (next, previous) => {
  if (!next) return
  list.remember(route.query)
  searchInput.value = next.search
  if (previous && next.search === previous.search && next.status === previous.status && next.severity === previous.severity) return
  void load(false)
}, { immediate: true })

watch(searchInput, (value) => {
  clearTimeout(debounce)
  debounce = window.setTimeout(() => {
    if (value !== filters.value.search) writeQuery({ search: value })
  }, 320)
})

onBeforeUnmount(() => clearTimeout(debounce))
</script>

<template>
  <AppShell section="incidents">
    <PageHeader title="Incidents" topic="page.incidents" />
    <section class="filters" aria-label="Incident 筛选">
      <input v-model="searchInput" maxlength="100" placeholder="搜索标题或告警名称" aria-label="搜索 Incident">
      <div class="filter-field">
        <HintTerm topic="term.incident">状态</HintTerm>
        <select :value="filters.status" aria-label="状态" @change="writeQuery({ status: ($event.target as HTMLSelectElement).value })">
          <option value="">全部状态</option>
          <option v-for="value in STATUSES" :key="value" :value="value">{{ value }}</option>
        </select>
      </div>
      <div class="filter-field">
        <HintTerm topic="term.severity">严重级别</HintTerm>
        <select :value="filters.severity" aria-label="严重级别" @change="writeQuery({ severity: ($event.target as HTMLSelectElement).value })">
          <option value="">全部级别</option>
          <option v-for="value in SEVERITIES" :key="value" :value="value">{{ value }}</option>
        </select>
      </div>
    </section>
    <StatePanel v-if="failed" kind="error" title="Incident 列表不可达" detail="请检查 ops-api 控制面，而不是把 API 离线误判成没有告警。" action="重试" @action="load()" />
    <section v-else class="records" aria-label="Incident records">
      <div class="table-head">
        <span>Incident</span>
        <span>Severity</span>
        <span>Status</span>
        <span>Last alert</span>
        <span>Evidence</span>
      </div>
      <IncidentRecord v-for="item in items" :key="item.id" layout="directory" :item="item" />
      <div v-if="loading" class="loading" role="status">正在读取受控摘要…</div>
      <StatePanel v-else-if="!items.length" title="当前查询未返回事件" detail="调整筛选条件，或等待受控告警进入。" />
      <p v-if="appendFailed" class="append-error" role="alert">加载更多失败，已保留当前列表。</p>
      <button v-if="hasMore && !loading" class="action-button more" type="button" @click="load(true)">加载更多</button>
    </section>
  </AppShell>
</template>

<style scoped>
.filters { display: grid; grid-template-columns: minmax(220px, 1fr) auto auto; gap: 8px; margin-bottom: 18px; border-bottom: 1px solid var(--line); padding-bottom: 16px; }
.filter-field { display: grid; gap: 6px; }
.filters input, .filters select { min-height: 44px; border: 1px solid var(--line); border-radius: var(--radius-sm); background: var(--surface-1); padding: 0 12px; color: var(--text); }
.filters input::placeholder { color: var(--text-muted); }
.filters input:focus, .filters select:focus { outline: none; border-color: var(--accent); box-shadow: var(--focus-ring); }
.records { border-block: 1px solid var(--line); }
.table-head { display: grid; grid-template-columns: minmax(0, 1.4fr) auto auto auto auto; gap: 16px; padding: 10px 0; color: var(--text-muted); font: 600 12px/1.3 var(--font-mono); letter-spacing: .06em; text-transform: uppercase; }
.loading, .append-error { border-top: 1px solid var(--line-soft); padding: 20px 8px; color: var(--text-muted); text-align: center; }
.append-error { color: var(--critical); }
.more { display: block; margin: 18px auto; }
@media (max-width: 900px) {
  .filters { grid-template-columns: 1fr 1fr; }
  .filters input { grid-column: 1 / -1; }
  .table-head { display: none; }
}
@media (max-width: 560px) {
  .filters { grid-template-columns: 1fr; }
  .filters input { grid-column: auto; }
}
</style>
