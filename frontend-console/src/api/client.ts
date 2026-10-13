import axios from 'axios'
import { accessMode } from '../utils/auth'
import type { AiAttempt, CatalogView, Evidence, IncidentDetail, IncidentPage, IncidentTrend, Overview, PipelineHealthView, ReportView, SessionView, SignalRange, SignalsView, TimelineEntry } from './types'

const baseURL = accessMode ? '/api/v1/console' : (import.meta.env.VITE_AIDIAG_API_BASE_URL || '/api/v1/console')
let csrfToken = ''

export const api = axios.create({ baseURL, withCredentials: true, timeout: 12_000 })

api.interceptors.request.use((config) => {
  config.headers['X-Request-Id'] = crypto.randomUUID()
  if (csrfToken && ['post', 'put', 'patch', 'delete'].includes((config.method || '').toLowerCase())) {
    config.headers['X-AIDiag-CSRF'] = csrfToken
  }
  return config
})

api.interceptors.response.use(
  (response) => {
    // An expired edge login can return HTML instead of the expected JSON API response.
    if (accessMode && !String(response.headers['content-type'] || '').includes('application/json')) {
      window.dispatchEvent(new CustomEvent('aidiag:session-expired'))
      return Promise.reject(new Error('CONSOLE_ACCESS_REQUIRED'))
    }
    return response
  },
  (error) => {
    if (error?.response?.status === 401) {
      clearCsrf()
      window.dispatchEvent(new CustomEvent('aidiag:session-expired'))
    }
    return Promise.reject(error)
  },
)

export function bindCsrf(value: string) { csrfToken = value }
export function clearCsrf() { csrfToken = '' }

export const consoleApi = {
  login: (username: string, password: string) => api.post<SessionView>('/session', { username, password }),
  session: () => api.get<SessionView>('/session'),
  logout: () => api.delete('/session'),
  overview: () => api.get<Overview>('/overview'),
  trend: (days = 14) => api.get<IncidentTrend>('/overview/trend', { params: { days } }),
  signals: (serviceId: string, range: SignalRange) => api.get<SignalsView>(`/services/${encodeURIComponent(serviceId)}/signals`, { params: { range } }),
  pipelineHealth: () => api.get<PipelineHealthView>('/pipeline-health'),
  services: () => api.get<CatalogView>('/services'),
  incidents: (params: Record<string, string | number | undefined>) => api.get<IncidentPage>('/incidents', { params }),
  incident: (id: string) => api.get<IncidentDetail>(`/incidents/${id}`),
  report: (id: string) => api.get<ReportView>(`/incidents/${id}/report`),
  evidence: (id: string) => api.get<Evidence[]>(`/incidents/${id}/evidence`),
  timeline: (id: string) => api.get<TimelineEntry[]>(`/incidents/${id}/timeline`),
  aiAttempts: (id: string) => api.get<AiAttempt[]>(`/incidents/${id}/ai-attempts`),
}
