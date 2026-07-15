export type HealthState = 'UP' | 'DOWN' | 'UNKNOWN'

export interface SessionView {
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
  severity: 'INFO' | 'WARNING' | 'CRITICAL'
  status: string
  startedAt: string
  lastAlertAt: string
  resolvedAt: string | null
  hasReport: boolean
  evidenceCount: number
}

export interface IncidentPage { items: IncidentSummary[]; nextCursor: string | null; hasMore: boolean }

export interface IncidentDetail extends IncidentSummary {
  environment: string
  detectedAt: string
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
  type: 'METRIC' | 'LOG' | 'TRACE' | 'TOPOLOGY' | 'RUNBOOK' | 'CHANGE_HISTORY'
  source: string
  serviceName: string
  startTime: string
  endTime: string
  collectedAt: string
  summary: string
  importance: number
}

export interface TimelineEntry { evidenceId: string; type: string; occurredAt: string; summary: string }
export interface ReportView { id: string; generatedAt: string; rootCause: string | null; description: string | null; affectedService: string | null; supportScore: number | null; verificationStatus: string | null; supportingEvidence: number; opposingEvidence: number }
export interface AiAttempt { id: string; sourceReportId: string; status: string; promptVersion: string; modelProvider: string | null; modelName: string | null; failureCode: string | null; createdAt: string; completedAt: string | null }

export interface ProbeView { id: string; kind: string; status: HealthState; observedAt: string }
export interface ServiceView { id: string; displayName: string; environment: string; otelServiceName: string; diagnosisProfile: string; grafanaUrl: string | null; status: HealthState; applicationStatus: HealthState; dependencies: ProbeView[] }
export interface CatalogView { version: number; compiledAt: string; sourceDigest: string; services: ServiceView[] }

export interface ApiError { code: string; message: string; requestId: string }
