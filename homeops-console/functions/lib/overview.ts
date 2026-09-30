import type {
  AlertSummary,
  HealthState,
  Overview,
  PowerSummary,
  ServiceSummary,
  ServiceState,
  SystemSummary,
} from '../../shared/homeops'
import { PrometheusClient, type PrometheusAlert, type VectorSample } from './prometheus'

const QUERIES = {
  cpu: '100 * (1 - avg(rate(node_cpu_seconds_total{job="home-node",mode="idle"}[5m])))',
  load1: 'node_load1{job="home-node"}',
  memoryTotal: 'node_memory_MemTotal_bytes{job="home-node"}',
  memoryAvailable: 'node_memory_MemAvailable_bytes{job="home-node"}',
  rootDiskTotal: 'node_filesystem_size_bytes{job="home-node",mountpoint="/",fstype!~"tmpfs|overlay"}',
  rootDiskAvailable: 'node_filesystem_avail_bytes{job="home-node",mountpoint="/",fstype!~"tmpfs|overlay"}',
  uptime: 'time() - node_boot_time_seconds{job="home-node"}',
  networkReceive: 'sum(rate(node_network_receive_bytes_total{job="home-node",device!~"lo|veth.*|br-.*|docker.*"}[5m]))',
  networkTransmit: 'sum(rate(node_network_transmit_bytes_total{job="home-node",device!~"lo|veth.*|br-.*|docker.*"}[5m]))',
  homeMetrics: '{__name__=~"homeops_(ac_online|battery_capacity_ratio|battery_health_ratio|battery_power_watts|wall_power_watts|temperature_celsius|collector_last_success_timestamp_seconds)"}',
  services: 'up{job=~"home-node|aidiag|ai-chat-production|prometheus|alertmanager|alloy|loki|tempo"} or node_systemd_unit_state{job="home-node",name=~"(docker|frpc|cloudflared)\\.service",state="active"} or probe_success{job="blackbox-ai-chat",service_name="ai-chat-production",target_kind="dependency"}',
} as const

type QueryKey = keyof typeof QUERIES
type QueryResults = Record<QueryKey, VectorSample[]>

function firstValue(samples: VectorSample[]): number | null {
  return samples.length > 0 ? samples[0].value : null
}

function metricValue(samples: VectorSample[], metricName: string, label?: [string, string]): number | null {
  const sample = samples.find((item) => item.metric.__name__ === metricName && (!label || item.metric[label[0]] === label[1]))
  return sample?.value ?? null
}

function clampPercent(value: number | null): number | null {
  return value === null ? null : Math.min(100, Math.max(0, value))
}

function round(value: number | null, digits = 1): number | null {
  if (value === null || !Number.isFinite(value)) return null
  const scale = 10 ** digits
  return Math.round(value * scale) / scale
}

function parsePositiveNumber(value: string): number | null {
  const parsed = Number(value)
  return Number.isFinite(parsed) && parsed > 0 ? parsed : null
}

function calculatePower(env: Env, homeMetrics: VectorSample[]): PowerSummary {
  const acValue = metricValue(homeMetrics, 'homeops_ac_online')
  const acOnline = acValue === null ? null : acValue >= 0.5
  const wallWatts = metricValue(homeMetrics, 'homeops_wall_power_watts')
  const batteryWatts = metricValue(homeMetrics, 'homeops_battery_power_watts')
  const baselineWatts = parsePositiveNumber(env.HOMEOPS_LOW_LOAD_BASELINE_WATTS)

  let watts: number | null = null
  let source: PowerSummary['source'] = 'unavailable'
  let live = false
  let measuredAt: string | null = null
  let explanation = '当前没有可用的功耗数据。'

  if (wallWatts !== null) {
    watts = wallWatts
    source = 'wall-meter'
    live = true
    explanation = '来自插座侧电表，包含电源适配器损耗。'
  } else if (acOnline === false && batteryWatts !== null) {
    watts = batteryWatts
    source = 'battery'
    live = true
    explanation = '来自电池实时放电功率，未包含电源适配器损耗。'
  } else if (baselineWatts !== null) {
    watts = baselineWatts
    source = 'measured-baseline'
    measuredAt = env.HOMEOPS_LOW_LOAD_BASELINE_MEASURED_AT
    explanation = '交流供电时电池不放电，显示的是已实测低负载基线，不冒充实时插座功耗。'
  }

  return {
    watts: round(watts, 2),
    source,
    live,
    acOnline,
    batteryPercent: round((metricValue(homeMetrics, 'homeops_battery_capacity_ratio') ?? Number.NaN) * 100, 0),
    batteryHealthPercent: round((metricValue(homeMetrics, 'homeops_battery_health_ratio') ?? Number.NaN) * 100, 1),
    monthlyKwh: watts === null ? null : round(watts * 24 * 30 / 1_000, 2),
    yearlyKwh: watts === null ? null : round(watts * 24 * 365 / 1_000, 1),
    measuredAt,
    explanation,
  }
}

function calculateSystem(results: QueryResults): SystemSummary {
  const totalMemory = firstValue(results.memoryTotal)
  const availableMemory = firstValue(results.memoryAvailable)
  const diskTotal = firstValue(results.rootDiskTotal)
  const diskAvailable = firstValue(results.rootDiskAvailable)
  const homeMetrics = results.homeMetrics
  const usedMemory = totalMemory !== null && availableMemory !== null ? Math.max(0, totalMemory - availableMemory) : null
  const usedDisk = diskTotal !== null && diskAvailable !== null ? Math.max(0, diskTotal - diskAvailable) : null

  return {
    cpuPercent: round(clampPercent(firstValue(results.cpu))),
    load1: round(firstValue(results.load1), 2),
    memoryUsedPercent: totalMemory && usedMemory !== null ? round(clampPercent(usedMemory / totalMemory * 100)) : null,
    memoryUsedBytes: usedMemory,
    memoryTotalBytes: totalMemory,
    rootDiskUsedPercent: diskTotal && usedDisk !== null ? round(clampPercent(usedDisk / diskTotal * 100)) : null,
    rootDiskUsedBytes: usedDisk,
    rootDiskTotalBytes: diskTotal,
    uptimeSeconds: round(firstValue(results.uptime), 0),
    cpuTemperatureCelsius: round(metricValue(homeMetrics, 'homeops_temperature_celsius', ['sensor', 'cpu'])),
    nvmeTemperatureCelsius: round(metricValue(homeMetrics, 'homeops_temperature_celsius', ['sensor', 'nvme'])),
    networkReceiveBytesPerSecond: round(firstValue(results.networkReceive), 0),
    networkTransmitBytesPerSecond: round(firstValue(results.networkTransmit), 0),
  }
}

function sampleState(sample: VectorSample | undefined): ServiceState {
  if (!sample) return 'unknown'
  return sample.value >= 0.5 ? 'up' : 'down'
}

function findSignal(samples: VectorSample[], predicate: (labels: Record<string, string>) => boolean): VectorSample | undefined {
  return samples.find((sample) => predicate(sample.metric))
}

function buildServices(samples: VectorSample[]): ServiceSummary[] {
  const definitions: Array<Omit<ServiceSummary, 'state'> & { match: (labels: Record<string, string>) => boolean }> = [
    { id: 'host', name: '主机采集', group: 'host', detail: 'CPU、内存、磁盘与温度', match: (m) => m.job === 'home-node' && m.__name__ === 'up' },
    { id: 'docker', name: 'Docker', group: 'host', detail: '容器运行底座', match: (m) => m.name === 'docker.service' },
    { id: 'cloudflared', name: 'Cloudflare Tunnel', group: 'host', detail: '应用公网入口', match: (m) => m.name === 'cloudflared.service' },
    { id: 'frpc', name: 'FRP 维护链路', group: 'host', detail: '经阿里云公网节点的 SSH 维护路径', match: (m) => m.name === 'frpc.service' },
    { id: 'aichat', name: 'AI-chat', group: 'application', detail: '生产应用与健康指标', match: (m) => m.job === 'ai-chat-production' && m.__name__ === 'up' },
    { id: 'aidiag', name: 'AIDiag', group: 'application', detail: '独立故障诊断系统', match: (m) => m.job === 'aidiag' && m.__name__ === 'up' },
    { id: 'mysql', name: 'MySQL', group: 'data', detail: 'AI-chat 主数据', match: (m) => m.component === 'mysql' },
    { id: 'redis', name: 'Redis', group: 'data', detail: '会话与缓存', match: (m) => m.component === 'redis' },
    { id: 'rabbitmq', name: 'RabbitMQ', group: 'data', detail: '文档处理消息', match: (m) => m.component === 'rabbitmq' },
    { id: 'elasticsearch', name: 'Elasticsearch', group: 'data', detail: '知识检索索引', match: (m) => m.component === 'elasticsearch' },
    { id: 'nacos', name: 'Nacos', group: 'data', detail: '服务注册与配置', match: (m) => m.component === 'nacos' },
    { id: 'prometheus', name: 'Prometheus', group: 'telemetry', detail: '指标与告警规则', match: (m) => m.job === 'prometheus' },
    { id: 'loki', name: 'Loki', group: 'telemetry', detail: '日志证据', match: (m) => m.job === 'loki' },
    { id: 'tempo', name: 'Tempo', group: 'telemetry', detail: '链路追踪证据', match: (m) => m.job === 'tempo' },
  ]

  return definitions.map(({ match, ...definition }) => ({
    ...definition,
    state: sampleState(findSignal(samples, match)),
  }))
}

function buildAlerts(alerts: PrometheusAlert[]): AlertSummary[] {
  return alerts
    .filter((alert) => alert.state === 'firing')
    .map((alert) => {
      const severity: AlertSummary['severity'] = alert.labels.severity === 'critical'
        ? 'critical'
        : alert.labels.severity === 'warning' ? 'warning' : 'info'
      return {
        name: alert.labels.alertname ?? 'UnnamedAlert',
        severity,
        summary: alert.annotations.summary ?? alert.annotations.description ?? 'Prometheus 告警正在触发',
        activeAt: alert.activeAt,
      }
    })
    .sort((left, right) => (left.severity === 'critical' ? -1 : right.severity === 'critical' ? 1 : 0))
}

function deriveState(freshness: Overview['freshness'], services: ServiceSummary[], alerts: AlertSummary[]): HealthState {
  if (freshness.state === 'unavailable' || alerts.some((alert) => alert.severity === 'critical')) return 'critical'
  if (freshness.state === 'stale' || alerts.some((alert) => alert.severity === 'warning') || services.some((service) => service.state === 'down')) return 'attention'
  if (services.some((service) => service.state === 'unknown')) return 'unknown'
  return 'healthy'
}

function copyEmptyResults(): QueryResults {
  return {
    cpu: [],
    load1: [],
    memoryTotal: [],
    memoryAvailable: [],
    rootDiskTotal: [],
    rootDiskAvailable: [],
    uptime: [],
    networkReceive: [],
    networkTransmit: [],
    homeMetrics: [],
    services: [],
  }
}

export async function collectOverview(env: Env): Promise<Overview> {
  const generatedAt = new Date().toISOString()
  const results = copyEmptyResults()
  const failures: string[] = []
  let alerts: PrometheusAlert[] = []

  try {
    const client = new PrometheusClient(env)
    const queryTasks = Object.entries(QUERIES).map(async ([key, expression]) => {
      try {
        results[key as QueryKey] = await client.query(expression)
      } catch (error) {
        failures.push(key)
        console.error(JSON.stringify({ message: 'homeops_query_failed', query: key, error: error instanceof Error ? error.message : 'unknown_error' }))
      }
    })
    const alertTask = client.alerts()
      .then((value) => { alerts = value })
      .catch((error: unknown) => {
        failures.push('alerts')
        console.error(JSON.stringify({ message: 'homeops_alert_query_failed', error: error instanceof Error ? error.message : 'unknown_error' }))
      })
    await Promise.all([...queryTasks, alertTask])
  } catch (error) {
    failures.push('configuration')
    console.error(JSON.stringify({ message: 'homeops_client_unavailable', error: error instanceof Error ? error.message : 'unknown_error' }))
  }

  const collectorTimestamp = metricValue(results.homeMetrics, 'homeops_collector_last_success_timestamp_seconds')
  const ageSeconds = collectorTimestamp === null ? null : Math.max(0, Date.now() / 1_000 - collectorTimestamp)
  const coreUnavailable = failures.length >= Object.keys(QUERIES).length || results.cpu.length === 0
  const freshness: Overview['freshness'] = {
    state: coreUnavailable ? 'unavailable' : ageSeconds === null || ageSeconds > 90 ? 'stale' : 'fresh',
    observedAt: collectorTimestamp === null ? null : new Date(collectorTimestamp * 1_000).toISOString(),
    ageSeconds: round(ageSeconds, 0),
  }
  const power = calculatePower(env, results.homeMetrics)
  const system = calculateSystem(results)
  const services = buildServices(results.services)
  const activeAlerts = buildAlerts(alerts)
  const state = deriveState(freshness, services, activeAlerts)
  const copy = {
    healthy: ['运行平稳', '主机、应用与观测链路均未发现需要处理的异常。'],
    attention: ['有事项需要留意', '至少一项指标、服务或告警需要检查，但核心数据仍可读取。'],
    critical: ['家庭服务器不可确认', '边缘页面仍在线，但家庭服务器数据入口不可用或存在严重告警。'],
    unknown: ['数据仍在建立', '部分采集项尚未接入，已知数据没有显示明确故障。'],
  } satisfies Record<HealthState, [string, string]>

  return {
    generatedAt,
    siteLabel: env.HOMEOPS_SITE_LABEL,
    state,
    headline: copy[state][0],
    summary: copy[state][1],
    freshness,
    power,
    system,
    services,
    alerts: activeAlerts,
    limitations: [
      '交流供电且电池不充放电时，Linux 电池接口不会给出整机实时功耗。',
      '当前 6.18 W 是 2026-08-11 的低负载电池侧实测基线；插座侧功耗仍需本地可读电表校准。',
      'HomeOps 保持只读，不提供 Shell、重启容器、升级系统或远程断电操作。',
    ],
  }
}
