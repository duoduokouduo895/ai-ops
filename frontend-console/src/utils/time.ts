const dateTime: Intl.DateTimeFormatOptions = {
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  second: '2-digit',
  hour12: false,
  timeZoneName: 'short',
}

const compactDateTime: Intl.DateTimeFormatOptions = {
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  hour12: false,
  timeZoneName: 'short',
}

export function timeZoneName() {
  return Intl.DateTimeFormat().resolvedOptions().timeZone
}

export function formatLocal(value: string | Date | null | undefined, compact = false) {
  if (!value) return '—'
  return new Intl.DateTimeFormat('zh-CN', compact ? compactDateTime : dateTime).format(new Date(value))
}

export function formatDuration(start: string, end: string) {
  const seconds = Math.max(0, (Date.parse(end) - Date.parse(start)) / 1000)
  if (seconds < 60) return `${Math.round(seconds)}s`
  if (seconds < 3600) return `${Math.round(seconds / 60)}m`
  const hours = seconds / 3600
  return hours < 48 ? `${hours.toFixed(1)}h` : `${(hours / 24).toFixed(1)}d`
}
