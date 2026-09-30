import { describe, expect, it } from 'vitest'
import { formatDuration, formatLocal, timeZoneName } from '../src/utils/time'

describe('time', () => {
  it('formats timestamps in the browser time zone', () => {
    const instant = '2026-07-15T00:47:00Z'
    const formatted = formatLocal(instant)
    const local = new Intl.DateTimeFormat('zh-CN', {
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
      hour12: false,
      timeZoneName: 'short',
    }).format(new Date(instant))
    expect(formatted).toBe(local)
    expect(timeZoneName()).toBe(Intl.DateTimeFormat().resolvedOptions().timeZone)
  })

  it('does not use last-updated time as the unresolved duration endpoint', () => {
    expect(formatDuration('2026-07-15T00:00:00Z', '2026-07-15T00:47:00Z')).toBe('47m')
  })
})
