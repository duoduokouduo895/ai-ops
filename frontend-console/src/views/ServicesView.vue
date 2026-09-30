<script setup lang="ts">
import { onMounted, ref } from 'vue'
import AppShell from '../components/AppShell.vue'
import ExplainButton from '../components/ExplainButton.vue'
import HintTerm from '../components/HintTerm.vue'
import PageHeader from '../components/PageHeader.vue'
import StatePanel from '../components/StatePanel.vue'
import StatusBadge from '../components/StatusBadge.vue'
import { consoleApi } from '../api/client'
import type { CatalogView, ProbeView, ServiceView } from '../api/types'
import { isUnauthorized } from '../utils/http'
import { sampleCaption } from '../utils/sampleTime'
import { formatLocal } from '../utils/time'

const catalog = ref<CatalogView | null>(null)
const failed = ref(false)
const loading = ref(true)

function applicationSample(service: ServiceView) {
  return sampleCaption(service.sampledAt, service.freshness, service.reason)
}

function probeSample(probe: ProbeView) {
  return sampleCaption(probe.sampledAt, probe.freshness, probe.reason)
}

async function load() {
  loading.value = true
  failed.value = false
  try {
    catalog.value = (await consoleApi.services()).data
  } catch (error) {
    if (!isUnauthorized(error)) failed.value = true
  } finally { loading.value = false }
}

onMounted(load)
</script>

<template>
  <AppShell section="services">
    <PageHeader title="Services" topic="page.services" />
    <StatePanel v-if="failed" kind="error" title="无法读取 Service Catalog" detail="控制面可能离线，已加载的前端不会把 UNKNOWN 误报为 DOWN。" action="重新检查" @action="load"/>
    <div v-else-if="loading" class="catalog-skeleton"><span class="skeleton"/><span class="skeleton"/></div>
    <StatePanel v-else-if="!catalog?.services.length" title="Service Catalog 为空" detail="尚未发现可展示的服务配置。"/>
    <section v-else class="catalog" aria-label="服务目录">
      <p v-if="catalog.queriedAt" class="query-time mono">
        <HintTerm topic="term.queried-at">查询时间</HintTerm> {{ formatLocal(catalog.queriedAt) }}
      </p>
      <article v-for="service in catalog.services" :key="service.id" class="service">
        <header>
          <div>
            <h2>{{ service.displayName }}</h2>
            <p>{{ service.environment }}</p>
          </div>
          <div class="status-block">
            <span class="meta-label"><HintTerm topic="term.overall-status">Status</HintTerm></span>
            <StatusBadge domain="service" :value="service.status" />
          </div>
        </header>
        <dl>
          <div>
            <dt><HintTerm topic="term.application-status">Application</HintTerm></dt>
            <dd><StatusBadge domain="service" :value="service.applicationStatus" /></dd>
          </div>
          <div>
            <dt><HintTerm topic="term.sampled-at">应用采样时间</HintTerm></dt>
            <dd class="mono" :data-kind="applicationSample(service).kind">{{ applicationSample(service).text }}</dd>
          </div>
        </dl>
        <details v-if="service.dependencies.length" class="more">
          <summary>查看依赖</summary>
          <div class="probes">
            <div v-for="probe in service.dependencies" :key="probe.id">
              <span>{{ probe.id }}</span>
              <small>{{ probe.kind }}</small>
              <StatusBadge domain="service" :value="probe.status" />
              <time class="mono" :data-kind="probeSample(probe).kind">{{ probeSample(probe).text }}</time>
            </div>
          </div>
        </details>
        <details class="more">
          <summary>技术信息</summary>
          <dl>
            <div><dt>ID</dt><dd class="mono">{{ service.id }}</dd></div>
            <div><dt>OTel service</dt><dd class="mono">{{ service.otelServiceName }}</dd></div>
            <div><dt>Diagnosis profile</dt><dd class="mono">{{ service.diagnosisProfile }}</dd></div>
            <div v-if="service.queriedAt"><dt>查询时间</dt><dd class="mono">{{ formatLocal(service.queriedAt) }}</dd></div>
          </dl>
        </details>
        <p v-if="service.grafanaUrl" class="grafana">
          <a :href="service.grafanaUrl" target="_blank" rel="noreferrer">在 Grafana 深入查看<span aria-hidden="true"> →</span></a>
          <ExplainButton topic="term.grafana" name="查看 Grafana 链接的含义" />
        </p>
      </article>
    </section>
  </AppShell>
</template>

<style scoped>
.catalog { border-top: 1px solid var(--line); }
.query-time { display: flex; align-items: center; gap: 4px; margin: 12px 0 0; color: var(--text-muted); font-size: 13px; }
.service { border-bottom: 1px solid var(--line); padding: 22px 0; }
.service header { display: flex; justify-content: space-between; gap: 16px; align-items: start; }
.service h2 { margin: 0; font-size: 18px; letter-spacing: -.02em; overflow-wrap: anywhere; }
.service header p { margin: 6px 0 0; color: var(--text-muted); }
.status-block { display: grid; justify-items: end; gap: 4px; }
.service dl { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px 20px; margin: 16px 0 0; }
dt { color: var(--text-muted); font-size: 12px; font-weight: 600; }
dd { margin: 6px 0 0; overflow-wrap: anywhere; }
.more { margin-top: 12px; }
.more summary { min-height: 44px; cursor: pointer; color: var(--accent-strong); font-size: 13px; font-weight: 600; }
.probes { display: grid; }
.probes > div { display: grid; grid-template-columns: minmax(0, 1fr) auto auto auto; gap: 8px 12px; align-items: center; border-top: 1px solid var(--line-soft); padding: 8px 0; font-size: 13px; }
.probes small, .probes time { color: var(--text-muted); }
dd[data-kind="stale"], time[data-kind="stale"] { color: var(--warning); }
.grafana { display: flex; align-items: center; gap: 4px; margin: 8px 0 0; }
.grafana a { display: inline-flex; min-height: 44px; align-items: center; color: var(--accent-strong); font-size: 13px; }
.catalog-skeleton { display: grid; gap: 1px; border-top: 1px solid var(--line); }
.catalog-skeleton span { height: 160px; border-radius: 0; }
@media (max-width: 640px) {
  .service dl, .probes > div { grid-template-columns: 1fr; }
  .status-block { justify-items: start; }
}
</style>
