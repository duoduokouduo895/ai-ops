import { describe, expect, it } from 'vitest'
import { consoleReturnPath } from '../src/utils/auth'

describe('login return path', () => {
  it('keeps in-app navigation and filters', () => {
    expect(consoleReturnPath('/incidents?status=OPEN#evidence')).toBe('/incidents?status=OPEN#evidence')
  })
  it('rejects external destinations and login loops', () => {
    for (const value of ['https://evil.example', '//evil.example', '/\\evil.example', '/login?redirect=/login',
      '/cdn-cgi/access/logout', '/\n/evil.example', undefined, ['//evil.example']]) {
      expect(consoleReturnPath(value)).toBe('/')
    }
  })
})
