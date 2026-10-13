import type { HistoryResponse, Overview } from '../shared/homeops'

const now = Date.now()
const wave = (center: number, amplitude: number, phase: number) => Array.from({ length: 72 }, (_, index) => ({
  timestamp: (now - (71 - index) * 20 * 60 * 1_000) / 1_000,
  value: Math.round((center + Math.sin(index / 5 + phase) * amplitude + Math.sin(index / 2.7) * amplitude * 0.22) * 10) / 10,
}))

export const demoOverview: Overview = {
  generatedAt: new Date().toISOString(),
  siteLabel: 'XiaoXin Air 14 · Home',
  state: 'healthy',
  headline: '运行平稳',
  summary: '主机、应用与观测链路均未发现需要处理的异常。',
  freshness: { state: 'fresh', observedAt: new Date(now - 12_000).toISOString(), ageSeconds: 12 },
  power: {
    watts: 6.18,
    source: 'measured-baseline',
    live: false,
    acOnline: true,
    batteryPercent: 97,
    batteryHealthPercent: 72.1,
    monthlyKwh: 4.45,
    yearlyKwh: 54.1,
    measuredAt: '2026-08-11',
    explanation: '交流供电时电池不放电，显示的是已实测低负载基线，不冒充实时插座功耗。',
  },
  system: {
    cpuPercent: 8.4,
    load1: 0.41,
    memoryUsedPercent: 55.2,
    memoryUsedBytes: 5.33 * 1024 ** 3,
    memoryTotalBytes: 9.65 * 1024 ** 3,
    rootDiskUsedPercent: 6.1,
    rootDiskUsedBytes: 28.6 * 1024 ** 3,
    rootDiskTotalBytes: 468.4 * 1024 ** 3,
    uptimeSeconds: 24 * 24 * 60 * 60,
    cpuTemperatureCelsius: 42.1,
    nvmeTemperatureCelsius: 38.9,
    networkReceiveBytesPerSecond: 184_000,
    networkTransmitBytesPerSecond: 62_000,
  },
  services: [
    ['host', '主机采集', 'host', 'CPU、内存、磁盘与温度'],
    ['docker', 'Docker', 'host', '容器运行底座'],
    ['cloudflared', 'Cloudflare Tunnel', 'host', '应用公网入口'],
    ['frpc', 'FRP 维护链路', 'host', '经阿里云公网节点的 SSH 维护路径'],
    ['aichat', 'AI-chat', 'application', '生产应用与健康指标'],
    ['aidiag', 'AIDiag', 'application', '独立故障诊断系统'],
    ['mysql', 'MySQL', 'data', 'AI-chat 主数据'],
    ['redis', 'Redis', 'data', '会话与缓存'],
    ['rabbitmq', 'RabbitMQ', 'data', '文档处理消息'],
    ['elasticsearch', 'Elasticsearch', 'data', '知识检索索引'],
    ['prometheus', 'Prometheus', 'telemetry', '指标与告警规则'],
    ['loki', 'Loki', 'telemetry', '日志证据'],
    ['tempo', 'Tempo', 'telemetry', '链路追踪证据'],
  ].map(([id, name, group, detail]) => ({ id, name, group: group as 'host' | 'application' | 'data' | 'telemetry', detail, state: 'up' as const })),
  alerts: [],
  limitations: [
    '交流供电且电池不充放电时，Linux 电池接口不会给出整机实时功耗。',
    '当前 6.18 W 是 2026-08-11 的低负载电池侧实测基线；插座侧功耗仍需本地可读电表校准。',
    'HomeOps 保持只读，不提供 Shell、重启容器、升级系统或远程断电操作。',
  ],
}

export const demoHistory: HistoryResponse = {
  generatedAt: new Date().toISOString(),
  range: '24h',
  series: [
    { key: 'cpu', label: 'CPU', unit: '%', points: wave(8.4, 3.6, 0) },
    { key: 'memory', label: '内存', unit: '%', points: wave(55.2, 2.1, 1.2) },
    { key: 'temperature', label: 'CPU 温度', unit: '°C', points: wave(43.2, 2.8, 2.4) },
  ],
}
