import { GLOSSARY } from './catalog'
import type { GlossaryEntry } from './types'

const BY_ID = new Map(GLOSSARY.map((item) => [item.id, item]))

export function unknownEntry(term: string, domain: GlossaryEntry['domain'] = 'term'): GlossaryEntry {
  return {
    id: `unknown.${domain}.${term}`,
    term,
    zh: '暂未提供专门解释',
    domain,
    what: '当前控制台版本没有为这个值提供专门解释。',
    here: '界面会保留原值，并用中性样式展示，避免把它映射成正常或其他已知状态。',
    next: '继续查看周围字段和证据；不要根据未知状态推断健康或已恢复。',
    bounds: '未知状态不能映射为正常。',
    source: 'frontend-console glossary fallback',
    hint: '当前没有专门解释。界面会保留原值，不会把它当成正常。',
  }
}

export function hintText(entry: GlossaryEntry) {
  return entry.hint || entry.what
}

export function pageIntro(entry: GlossaryEntry) {
  if (entry.pageIntro?.length) return entry.pageIntro.slice(0, 3)
  return [entry.what, entry.here].filter(Boolean).slice(0, 3)
}

export function lookup(id: string): GlossaryEntry {
  return BY_ID.get(id) ?? unknownEntry(id)
}

export function hasEntry(id: string) {
  return BY_ID.has(id)
}

export { alertTopic, statusTopic } from './ids'
export { GLOSSARY, GLOSSARY_GROUPS } from './catalog'
export { statusTone } from './statusTone'
export type { GlossaryDomain, GlossaryEntry, StatusDomain, StatusTone } from './types'
