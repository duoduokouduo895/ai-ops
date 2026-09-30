export type GlossaryDomain =
  | 'page'
  | 'term'
  | 'incident'
  | 'run'
  | 'report'
  | 'ai'
  | 'service'
  | 'severity'
  | 'alert'
  | 'pipeline'
  | 'signal'
  | 'rule'

export type StatusDomain = 'incident' | 'service' | 'run' | 'report' | 'ai' | 'severity' | 'pipeline' | 'signal' | 'rule'

export type StatusTone = 'success' | 'warning' | 'critical' | 'unknown' | 'neutral'

export interface GlossaryEntry {
  id: string
  term: string
  zh: string
  domain: GlossaryDomain
  what: string
  here: string
  next: string
  bounds?: string
  source: string
  hint?: string
  pageIntro?: string[]
}
