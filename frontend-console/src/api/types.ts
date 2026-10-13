export type HealthState = 'UP' | 'DOWN' | 'UNKNOWN'
export type IncidentSeverity = 'INFO' | 'WARNING' | 'CRITICAL'
export type EvidenceType = 'METRIC' | 'LOG' | 'TRACE' | 'TOPOLOGY' | 'RUNBOOK' | 'CHANGE_HISTORY'

export interface SessionView {
  authMode?: 'access' | 'session'
  principal: string
  idleExpiresAt: string
  absoluteExpiresAt: string
  csrfToken: string
}

export interface Overview {
  activeIncidents: number
  recoveredLast24Hours: number
  failedIncidents: number
  pendingRuns: number
  lastAlertAt: string | null
  lastEvidenceAt: string | null
}

export interface IncidentSummary {
  id: string
  title: string
  primaryService: string
  alertName: string
  severity: IncidentSeverity
  status: string
  startedAt: string
  lastAlertAt: string
  resolvedAt: string | null
  hasReport: boolean
  evidenceCount: number
}

export interface IncidentPage { items: IncidentSummary[]; nextCursor: string | null; hasMore: boolean }

export interface IncidentDetail {
  id: string
  title: string
  primaryService: string
  alertName: string
  severity: IncidentSeverity
  status: string
  environment: string
  startedAt: string
  detectedAt: string
  lastAlertAt: string
  resolvedAt: string | null
  updatedAt: string
  diagnosisRunId: string | null
  diagnosisRunStatus: string | null
  attempt: number | null
  failureCode: string | null
  diagnosisStartedAt: string | null
  diagnosisFinishedAt: string | null
}

export interface Evidence {
  id: string
  diagnosisRunId: string
  type: EvidenceType
  source: string
  serviceName: string
  startTime: string
  endTime: string
  collectedAt: string
  summary: string
  importance: number
  metric?: MetricSeries | null
}

/** Points are [epochSeconds, value]. */
export type SeriesPoint = [number, number]

export interface MetricSeries {
  threshold: number | null
  maximum: number | null
  points: SeriesPoint[]
}

export interface TimelineEntry {
  evidenceId: string
  type: string
  occurredAt: string
  summary: string
  referenceKind?: 'ALERT_EVENT' | 'DIAGNOSIS_RUN' | 'EVIDENCE' | 'DIAGNOSIS_REPORT' | string
  referenceId?: string
}
export interface ReportCandidateView {
  id: string
  rank: number
  name: string
  description: string | null
  affectedService: string | null
  supportScore: number | null
  verificationStatus: string | null
  supportingEvidence: number
  opposingEvidence: number
}
export interface ReportView {
  id: string
  generatedAt: string
  rootCause: string | null
  description: string | null
  affectedService: string | null
  supportScore: number | null
  verificationStatus: string | null
  supportingEvidence: number
  opposingEvidence: number
  diagnosisRunId?: string | null
  disposition?: string | null
  dispositionReason?: string | null
  limitations?: string[]
  candidates?: ReportCandidateView[]
}
export interface AiAttempt {
  id: string
  sourceReportId: string
  status: string
  promptVersion: string
  modelProvider: string | null
  modelName: string | null
  failureCode: string | null
  createdAt: string
  completedAt: string | null
}

export interface ProbeView {
  id: string
  kind: string
  status: HealthState
  observedAt: string
  queriedAt?: string | null
  sampledAt?: string | null
  freshness?: string | null
  reason?: string | null
}
export interface ServiceView {
  id: string
  displayName: string
  environment: string
  otelServiceName: string
  diagnosisProfile: string
  grafanaUrl: string | null
  status: HealthState
  applicationStatus: HealthState
  dependencies: ProbeView[]
  queriedAt?: string | null
  sampledAt?: string | null
  freshness?: string | null
  reason?: string | null
}
export interface CatalogView {
  version: number
  compiledAt: string
  sourceDigest: string
  services: ServiceView[]
  queriedAt?: string | null
}

export type PipelineStatus = 'IDLE' | 'IN_PROGRESS' | 'BACKLOG' | 'QUERY_FAILED' | 'NOT_SAMPLED' | 'SNAPSHOT_EXPIRED'

export interface PipelineHealthView {
  queriedAt: string | null
  lastSuccessfulCheckAt: string | null
  freshness: string | null
  reason: string | null
  databaseStatus: HealthState | string | null
  activeIncidents: number | null
  pendingRuns: number | null
  waitingRuns?: number | null
  inProgressRuns?: number | null
  oldestPendingAgeSeconds: number | null
  lastAlertAt: string | null
  lastEvidenceAt: string | null
  lastReportAt: string | null
  pipelineStatus: PipelineStatus | string
}

export interface TrendDay { day: string; opened: number; critical: number; resolved: number }
export interface IncidentTrend { days: number; zone: string; items: TrendDay[] }

export type SignalRange = '1h' | '6h' | '24h'
export type SignalStatus = 'OK' | 'BREACH' | 'NO_DATA' | 'QUERY_FAILED'
export type RuleState = 'FIRING' | 'PENDING' | 'INACTIVE' | 'UNKNOWN'

export interface SignalSeries { label: string; points: { t: number; v: number }[] }
export interface SignalView {
  id: string
  group: 'golden' | 'saturation' | 'dependency' | string
  title: string
  unit: string
  description: string
  threshold: number | null
  alertName: string | null
  alertState: RuleState | null
  status: SignalStatus | string
  reason: string | null
  current: number | null
  series: SignalSeries[]
}
export interface SignalsView {
  serviceId: string
  displayName: string
  environment: string
  otelServiceName: string
  range: SignalRange
  start: string
  end: string
  stepSeconds: number
  queriedAt: string
  signals: SignalView[]
}

export interface ApiError { code: string; message: string; requestId: string }
