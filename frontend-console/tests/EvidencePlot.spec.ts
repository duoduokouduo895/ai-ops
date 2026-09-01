import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import EvidencePlot from '../src/components/EvidencePlot.vue'

const evidence = (id: string, importance: number) => ({ id, importance, diagnosisRunId:'r', type:'METRIC' as const, source:'prometheus', serviceName:'aidiag', startTime:'2026-07-15T00:00:00Z', endTime:'2026-07-15T00:01:00Z', collectedAt:'2026-07-15T00:01:00Z', summary:'bounded' })

describe('EvidencePlot', () => {
  it('renders only supplied evidence and synchronizes selection', async () => {
    const wrapper = mount(EvidencePlot, { props: { items: [evidence('a', 10), evidence('b', 90)], selected: 'b' } })
    expect(wrapper.findAll('circle')).toHaveLength(2)
    expect(wrapper.findAll('circle')[1].classes()).toContain('selected')
    await wrapper.get('button[aria-label*="METRIC evidence"]').trigger('click')
    expect(wrapper.emitted('select')?.[0]).toEqual(['a'])
  })
})
