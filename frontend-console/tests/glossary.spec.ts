import { describe, expect, it } from 'vitest'
import { lookup, statusTopic } from '../src/glossary'

describe('glossary', () => {
  it('explains Inbox as an overview that can include recovered history', () => {
    const inbox = lookup('page.inbox')
    expect(inbox.what).toContain('事件概览')
    expect(inbox.bounds).toMatch(/不代表系统此刻仍有故障/)
    expect(inbox.bounds).toMatch(/不能相加/)
  })

  it('separates RESOLVED, DIAGNOSED and COMPLETED', () => {
    expect(lookup('incident.RESOLVED').what).toContain('已标记恢复')
    expect(lookup('incident.RESOLVED').here).toMatch(/不等于 AIDiag 执行了修复/)
    expect(lookup('incident.DIAGNOSED').bounds).toMatch(/不是 RESOLVED/)
    expect(lookup('run.COMPLETED').bounds).toMatch(/不能显示成“服务已恢复”/)
    expect(statusTopic('run', 'COMPLETED')).not.toBe(statusTopic('incident', 'RESOLVED'))
  })

  it('keeps unknown values as unknown instead of healthy', () => {
    const unknown = lookup('incident.NOT_A_STATUS')
    expect(unknown.zh).toContain('暂未提供专门解释')
    expect(unknown.bounds).toMatch(/不能映射为正常/)
  })

  it('explains Support score as a rule score', () => {
    const score = lookup('term.support-score')
    expect(score.zh).toContain('规则支持分数')
    expect(score.bounds).toMatch(/不是故障概率/)
  })

  it('separates in-progress work from a persistent backlog and expired snapshots', () => {
    expect(lookup('pipeline.IN_PROGRESS').bounds).toMatch(/不等于持续积压/)
    expect(lookup('pipeline.BACKLOG').what).toContain('积压门槛')
    expect(lookup('pipeline.SNAPSHOT_EXPIRED').bounds).toMatch(/不能把过期缓存显示成当前空闲/)
    expect(lookup('term.sampled-at').bounds).toMatch(/没有汇总 sampledAt/)
  })

  it('gives same-colored statuses different short hints by domain', () => {
    expect(lookup('incident.RESOLVED').hint).toMatch(/已记录恢复/)
    expect(lookup('run.COMPLETED').hint).toMatch(/诊断任务已结束/)
    expect(lookup('service.UP').hint).toMatch(/可达/)
    expect(lookup('incident.RESOLVED').hint).not.toBe(lookup('run.COMPLETED').hint)
  })

  it('keeps page introductions short and local', () => {
    const inbox = lookup('page.inbox')
    expect(inbox.pageIntro?.join('')).toMatch(/将鼠标移到状态上可查看含义/)
    expect(inbox.pageIntro?.length).toBeLessThanOrEqual(3)
    expect(inbox.pageIntro?.join('')).not.toMatch(/frontend-console\/src\/glossary/)
  })
})
