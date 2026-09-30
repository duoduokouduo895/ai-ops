import { formatLocal } from './time'

export type SampleCaption = {
  kind: 'fresh' | 'stale' | 'unknown'
  text: string
}

export function sampleCaption(
  sampledAt: string | null | undefined,
  freshness?: string | null,
  reason?: string | null,
) {
  if (sampledAt && (freshness === 'STALE' || reason === 'STALE')) {
    return { kind: 'stale', text: `上次采样 ${formatLocal(sampledAt)} · 已过期` } satisfies SampleCaption
  }
  if (sampledAt) {
    return { kind: 'fresh', text: formatLocal(sampledAt) } satisfies SampleCaption
  }
  return { kind: 'unknown', text: '未知' } satisfies SampleCaption
}

export function isConfirmedRootCause(report: {
  disposition?: string | null
  verificationStatus?: string | null
  rootCause?: string | null
} | null | undefined) {
  if (!report?.rootCause) return false
  if (report.disposition) return report.disposition === 'CONFIRMED_ROOT_CAUSE'
  return report.verificationStatus === 'SUPPORTED'
}

export function reportLimitTitle(report: {
  disposition?: string | null
  verificationStatus?: string | null
} | null | undefined) {
  if (report?.disposition === 'INSUFFICIENT_EVIDENCE' || report?.verificationStatus === 'INSUFFICIENT_EVIDENCE') {
    return '报告证据不足，没有受支持根因'
  }
  return '报告已生成，但没有受支持根因'
}

export function isEvidenceTimelineEntry(entry: { referenceKind?: string; type: string }) {
  if (entry.referenceKind) return entry.referenceKind === 'EVIDENCE'
  return entry.type.startsWith('EVIDENCE_')
}
