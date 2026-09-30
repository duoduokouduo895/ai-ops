export type HealthState = 'healthy' | 'attention' | 'critical' | 'unknown'
export type FreshnessState = 'fresh' | 'stale' | 'unavailable'
export type ServiceState = 'up' | 'down' | 'unknown'
export type PowerSource = 'wall-meter' | 'battery' | 'measured-baseline' | 'unavailable'

export interface Freshness {
  state: FreshnessState
  observedAt: string | null
  ageSeconds: number | null
}

export interface PowerSummary {
  watts: number | null
  source: PowerSource
  live: boolean
  acOnline: boolean | null
  batteryPercent: number | null
  batteryHealthPercent: number | null
  monthlyKwh: number | null
  yearlyKwh: number | null
  measuredAt: string | null
  explanation: string
}

export interface SystemSummary {
  cpuPercent: number | null
  load1: number | null
  memoryUsedPercent: number | null
  memoryUsedBytes: number | null
  memoryTotalBytes: number | null
  rootDiskUsedPercent: number | null
  rootDiskUsedBytes: number | null
  rootDiskTotalBytes: number | null
  uptimeSeconds: number | null
  cpuTemperatureCelsius: number | null
  nvmeTemperatureCelsius: number | null
  networkReceiveBytesPerSecond: number | null
  networkTransmitBytesPerSecond: number | null
}

export interface ServiceSummary {
  id: string
  name: string
  group: 'host' | 'application' | 'data' | 'telemetry'
  state: ServiceState
  detail: string
}

export interface AlertSummary {
  name: string
  severity: 'critical' | 'warning' | 'info'
  summary: string
  activeAt: string | null
}

export interface Overview {
  generatedAt: string
  siteLabel: string
  state: HealthState
  headline: string
  summary: string
  freshness: Freshness
  power: PowerSummary
  system: SystemSummary
  services: ServiceSummary[]
  alerts: AlertSummary[]
  limitations: string[]
}

export interface HistoryPoint {
  timestamp: number
  value: number
}

export interface HistorySeries {
  key: 'cpu' | 'memory' | 'temperature'
  label: string
  unit: '%' | '°C'
  points: HistoryPoint[]
}

export interface HistoryResponse {
  generatedAt: string
  range: '6h' | '24h' | '7d'
  series: HistorySeries[]
}
