<script setup lang="ts">
import { onMounted, ref } from 'vue'
import AppShell from '../components/AppShell.vue'
import StatePanel from '../components/StatePanel.vue'
import StatusBadge from '../components/StatusBadge.vue'
import { consoleApi } from '../api/client'
import type { CatalogView } from '../api/types'

const catalog = ref<CatalogView | null>(null); const failed = ref(false); const loading = ref(true)
async function load() { loading.value = true; failed.value = false; try { catalog.value = (await consoleApi.services()).data } catch { failed.value = true } finally { loading.value = false } }
onMounted(load)
</script>

<template>
  <AppShell section="services">
    <header class="page-head"><h1 class="page-title">Services</h1></header>
    <StatePanel v-if="failed" kind="error" title="无法读取 Service Catalog" detail="控制面可能离线，已加载的前端不会把 UNKNOWN 误报为 DOWN。" action="重新检查" @action="load"/>
    <div v-else-if="loading" class="catalog-skeleton"><span class="skeleton"/><span class="skeleton"/></div>
    <template v-else><section class="catalog" aria-label="服务目录"><article v-for="service in catalog?.services" :key="service.id" class="service"><header><div><h2>{{ service.displayName }}</h2><p class="mono">{{ service.otelServiceName }}</p></div><StatusBadge :value="service.status"/></header><dl><div><dt>Environment</dt><dd>{{ service.environment }}</dd></div><div><dt>Diagnosis profile</dt><dd class="mono">{{ service.diagnosisProfile }}</dd></div><div><dt>Application</dt><dd><StatusBadge :value="service.applicationStatus"/></dd></div></dl><div class="probes"><div v-for="probe in service.dependencies" :key="probe.id"><span>{{ probe.id }}</span><small>{{ probe.kind }}</small><StatusBadge :value="probe.status"/></div></div><a v-if="service.grafanaUrl" :href="service.grafanaUrl" target="_blank" rel="noreferrer">在 Grafana 深入查看<span aria-hidden="true"> →</span></a></article></section></template>
  </AppShell>
</template>

<style scoped>
.page-head { margin-bottom: 34px; }.catalog { border-top: 1px solid var(--line); }.service { display: grid; grid-template-columns: minmax(200px, .8fr) minmax(260px, 1.15fr) minmax(240px, 1fr); gap: 30px; border-bottom: 1px solid var(--line); padding: 24px 0; }.service header { display: flex; justify-content: space-between; gap: 14px; }.service h2 { margin: 0; font-size: 18px; letter-spacing: -.02em; }.service header p { margin: 6px 0 0; color: var(--text-muted); font-size: 12px; }.service dl { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 16px; margin: 0; }.service dt { color: var(--text-faint); font: 600 12px var(--font-mono); letter-spacing: .06em; text-transform: uppercase; }.service dd { margin: 7px 0 0; color: var(--text-soft); font-size: 12px; overflow-wrap: anywhere; }.probes { display: grid; align-content: start; }.probes > div { display: grid; grid-template-columns: minmax(0, 1fr) 70px auto; gap: 12px; align-items: center; border-bottom: 1px solid var(--line-soft); padding: 8px 0; font-size: 12px; }.probes > div:last-child { border-bottom: 0; }.probes small { color: var(--text-muted); font-size: 12px; }.service > a { align-self: end; grid-column: 1 / -1; width: fit-content; color: var(--accent-strong); font-size: 12px; }.service > a span { margin-left: 5px; }.catalog-meta { margin-top: 16px; color: var(--text-faint); font-size: 12px; }.catalog-skeleton { display: grid; gap: 1px; border-top: 1px solid var(--line); }.catalog-skeleton span { height: 250px; border-radius: 0; }
@media (max-width: 1050px) { .service { grid-template-columns: 1fr 1fr; }.probes { grid-column: 1 / -1; }.service > a { grid-column: 1 / -1; } }
@media (max-width: 640px) { .page-head { margin-bottom: 26px; }.service { grid-template-columns: 1fr; gap: 20px; padding: 21px 0; }.service dl { grid-template-columns: 1fr; gap: 10px; }.probes { grid-column: auto; }.service > a { grid-column: auto; } }
</style>
