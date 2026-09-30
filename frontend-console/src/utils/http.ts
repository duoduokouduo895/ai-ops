import axios from 'axios'

export function httpStatus(error: unknown) {
  return axios.isAxiosError(error) ? error.response?.status : undefined
}

export function isUnauthorized(error: unknown) {
  return httpStatus(error) === 401
}

export function isNotFound(error: unknown) {
  return httpStatus(error) === 404
}
