import type { StatusDomain } from './types'

export function statusTopic(domain: StatusDomain, value: string) {
  return `${domain}.${value}`
}

export function alertTopic(name: string) {
  return `alert.${name}`
}
