import type { StatusDomain, StatusTone } from './types'

const TABLE: Record<StatusDomain, Partial<Record<string, StatusTone>>> = {
  incident: {
    RESOLVED: 'success',
    CLOSED: 'success',
    FAILED: 'critical',
    OPEN: 'warning',
    DIAGNOSING: 'warning',
    DIAGNOSED: 'warning',
  },
  service: {
    UP: 'success',
    DOWN: 'critical',
    UNKNOWN: 'unknown',
  },
  run: {
    COMPLETED: 'success',
    FAILED: 'critical',
    PENDING: 'warning',
    LEASED: 'warning',
    COLLECTING: 'warning',
    EVIDENCE_READY: 'warning',
    ANALYZING: 'warning',
    VERIFYING: 'warning',
    GENERATING_REPORT: 'warning',
    RETRY_WAIT: 'warning',
  },
  report: {
    SUPPORTED: 'success',
    REJECTED: 'critical',
    WEAKLY_SUPPORTED: 'unknown',
    UNVERIFIED: 'unknown',
    INSUFFICIENT_EVIDENCE: 'unknown',
  },
  ai: {
    COMPLETED: 'success',
    FAILED: 'critical',
    REJECTED: 'critical',
    FALLBACK: 'warning',
    PENDING: 'warning',
  },
  severity: {
    CRITICAL: 'critical',
    WARNING: 'warning',
    INFO: 'neutral',
  },
  pipeline: {
    IDLE: 'success',
    IN_PROGRESS: 'warning',
    BACKLOG: 'warning',
    QUERY_FAILED: 'critical',
    NOT_SAMPLED: 'unknown',
    SNAPSHOT_EXPIRED: 'unknown',
  },
  signal: {
    OK: 'success',
    BREACH: 'critical',
    NO_DATA: 'unknown',
    QUERY_FAILED: 'unknown',
  },
  rule: {
    INACTIVE: 'success',
    PENDING: 'warning',
    FIRING: 'critical',
    UNKNOWN: 'unknown',
  },
}

export function statusTone(domain: StatusDomain, value: string): StatusTone {
  return TABLE[domain][value] ?? 'unknown'
}
