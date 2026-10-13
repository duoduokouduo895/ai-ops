import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'
import { loadEnv } from 'vite'

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  const authMode = env.VITE_CONSOLE_AUTH_MODE || 'access'
  if (!['access', 'session'].includes(authMode)) throw new Error('VITE_CONSOLE_AUTH_MODE must be access or session')
  if (authMode === 'access' && env.VITE_AIDIAG_API_BASE_URL && env.VITE_AIDIAG_API_BASE_URL !== '/api/v1/console') {
    throw new Error('Access console requires the same-origin API /api/v1/console')
  }
  return {
    plugins: [vue()],
    build: {
      target: 'es2022',
      cssCodeSplit: true,
      sourcemap: true,
      manifest: true,
    },
    test: {
      environment: 'jsdom',
      setupFiles: ['./tests/setup.ts'],
      include: ['./tests/*.spec.ts', './tests/unit/**/*.spec.ts'],
      exclude: ['./tests/e2e/**'],
    },
  }
})
