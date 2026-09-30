import { describe, expect, it } from 'vitest'
import { isConfirmedRootCause, isEvidenceTimelineEntry, reportLimitTitle, sampleCaption } from '../src/utils/sampleTime'
import { formatLocal } from '../src/utils/time'

describe('sampleCaption', () => {
  it('keeps a stale sample time instead of calling it unknown', () => {
    const sampledAt = '2026-09-10T06:00:00Z'
    const caption = sampleCaption(sampledAt, 'STALE', 'STALE')
    expect(caption.kind).toBe('stale')
    expect(caption.text).toContain('上次采样')
    expect(caption.text).toContain('已过期')
    expect(caption.text).toContain(formatLocal(sampledAt))
  })

  it('uses unknown only when the sample is missing', () => {
    expect(sampleCaption(null, 'UNKNOWN', 'MISSING_SAMPLE')).toEqual({ kind: 'unknown', text: '未知' })
  })
})

describe('report and timeline guards', () => {
  it('only treats a confirmed primary as the root cause', () => {
    expect(isConfirmedRootCause({ disposition: 'CONFIRMED_ROOT_CAUSE', rootCause: 'pool' })).toBe(true)
    expect(isConfirmedRootCause({ disposition: 'WEAKLY_SUPPORTED', rootCause: null, verificationStatus: 'WEAKLY_SUPPORTED' })).toBe(false)
    expect(isConfirmedRootCause({ verificationStatus: 'SUPPORTED', rootCause: 'legacy' })).toBe(true)
  })

  it('keeps insufficient evidence in the conclusion title', () => {
    expect(reportLimitTitle({ disposition: 'INSUFFICIENT_EVIDENCE' })).toBe('报告证据不足，没有受支持根因')
    expect(reportLimitTitle({ verificationStatus: 'INSUFFICIENT_EVIDENCE' })).toBe('报告证据不足，没有受支持根因')
    expect(reportLimitTitle({ verificationStatus: 'WEAKLY_SUPPORTED' })).toBe('报告已生成，但没有受支持根因')
  })

  it('only evidence reference kinds jump to evidence', () => {
    expect(isEvidenceTimelineEntry({ referenceKind: 'EVIDENCE', type: 'EVIDENCE_LOG' })).toBe(true)
    expect(isEvidenceTimelineEntry({ referenceKind: 'ALERT_EVENT', type: 'ALERT_RESOLVED' })).toBe(false)
    expect(isEvidenceTimelineEntry({ type: 'EVIDENCE_TRACE' })).toBe(true)
  })
})
