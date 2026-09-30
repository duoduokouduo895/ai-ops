import { describe, expect, it } from 'vitest'
import { prometheusParsing } from '../../functions/lib/prometheus'

describe('Prometheus response validation', () => {
  it('accepts finite vector samples and ignores invalid values', () => {
    const samples = prometheusParsing.parseVector({
      status: 'success',
      data: {
        resultType: 'vector',
        result: [
          { metric: { __name__: 'homeops_ac_online', job: 'home-node' }, value: [1_700_000_000, '1'] },
          { metric: { __name__: 'bad_sample' }, value: [1_700_000_000, 'NaN'] },
        ],
      },
    })

    expect(samples).toEqual([{
      metric: { __name__: 'homeops_ac_online', job: 'home-node' },
      timestamp: 1_700_000_000,
      value: 1,
    }])
  })

  it('rejects a response that is not a successful Prometheus envelope', () => {
    expect(() => prometheusParsing.parseVector({ status: 'error', data: {} }))
      .toThrow('origin_response_invalid_envelope')
  })

  it('parses only well-formed firing alert evidence', () => {
    const alerts = prometheusParsing.parseAlerts({
      status: 'success',
      data: {
        alerts: [{
          labels: { alertname: 'HomeServerRunningOnBattery', severity: 'critical' },
          annotations: { summary: '家庭服务器已切换到电池供电' },
          activeAt: '2026-08-11T05:00:00Z',
          state: 'firing',
        }],
      },
    })

    expect(alerts[0]?.labels.alertname).toBe('HomeServerRunningOnBattery')
  })
})
