/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_CONSOLE_AUTH_MODE?: 'access' | 'session'
  readonly VITE_AIDIAG_API_BASE_URL?: string
}
