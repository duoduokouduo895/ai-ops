import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import TimeSeriesChart from '../src/components/TimeSeriesChart.vue'
import TrendBars from '../src/components/TrendBars.vue'
import { lookup } from '../src/glossary'
import { formatValue, niceScale, seriesColor } from '../src/utils/chart'

describe('chart utilities', () => {
  it('builds a clean zero-based scale', () => {
    expect(niceScale(7)).toEqual({ max: 8, ticks: [0, 2, 4, 6, 8] })
    expect(niceScale(0).max).toBe(1)
    expect(niceScale(0.34).ticks[0]).toBe(0)
  })

  it('binds outcome colors to the entity, not to rank', () => {
    expect(seriesColor('timeout', false)).toBe('var(--series-2)')
    expect(seriesColor('timeout', false)).toBe(seriesColor('timeout', false))
    expect(seriesColor('unexpected', false)).toBe('var(--text-muted)')
    expect(seriesColor('anything', true)).toBe('var(--chart-single)')
  })

  it('never renders missing values as zero', () => {
    expect(formatValue(null, 'count')).toBe('—')
    expect(formatValue(Number.NaN, 's')).toBe('—')
    expect(formatValue(0.42, 's')).toBe('420 ms')
    expect(formatValue(0, 'up')).toBe('失败')
  })
})

describe('TimeSeriesChart', () => {
  const base = { start: 0, end: 600, stepSeconds: 30, unit: 'count', label: '模型超时' }

  it('draws the rule threshold and breaks the line across missing samples', () => {
    const wrapper = mount(TimeSeriesChart, {
      props: { ...base, threshold: 1, series: [{ label: 'x', color: 'red', points: [[0, 0], [30, 1], [300, 2], [330, 3]] }] },
    })
    expect(wrapper.find('.threshold text').text()).toContain('阈值 1')
    const d = wrapper.find('path.line').attributes('d') ?? ''
    expect(d.match(/M/g)).toHaveLength(2)
  })

  it('reads values point by point from the keyboard and offers a table view', async () => {
    const wrapper = mount(TimeSeriesChart, {
      props: { ...base, series: [{ label: 'success', color: 'blue', points: [[0, 1], [30, 4]] }, { label: 'timeout', color: 'orange', points: [[30, 2]] }] },
    })
    expect(wrapper.findAll('.legend li')).toHaveLength(2)
    await wrapper.find('.plot').trigger('keydown', { key: 'End' })
    const tooltip = wrapper.find('.tooltip')
    expect(tooltip.text()).toContain('4 次')
    expect(tooltip.text()).toContain('2 次')
    expect(wrapper.findAll('tbody tr')).toHaveLength(2)
  })

  it('labels an empty window as NO_DATA instead of drawing a flat zero', () => {
    const wrapper = mount(TimeSeriesChart, { props: { ...base, series: [] } })
    expect(wrapper.text()).toContain('NO_DATA')
    expect(wrapper.find('path.line').exists()).toBe(false)
  })
})

describe('TrendBars', () => {
  it('splits CRITICAL from other incidents and exposes each day in the table', async () => {
    const items = [
      { day: '2026-09-29', opened: 3, critical: 1, resolved: 2 },
      { day: '2026-09-30', opened: 0, critical: 0, resolved: 1 },
    ]
    const wrapper = mount(TrendBars, { props: { items } })
    expect(wrapper.findAll('path.critical')).toHaveLength(1)
    expect(wrapper.findAll('path.other')).toHaveLength(1)
    expect(wrapper.findAll('tbody tr')).toHaveLength(2)
    await wrapper.find('.plot').trigger('keydown', { key: 'ArrowRight' })
    expect(wrapper.find('.tooltip').text()).toContain('2026-09-29')
  })
})

describe('signal glossary', () => {
  it('keeps NO_DATA and unknown rule state from reading as healthy', () => {
    expect(lookup('signal.NO_DATA').bounds).toMatch(/不能显示为 0/)
    expect(lookup('rule.UNKNOWN').here).toMatch(/不能当成未触发/)
    expect(lookup('page.ai-chat').pageIntro?.length).toBeGreaterThan(0)
  })
})
