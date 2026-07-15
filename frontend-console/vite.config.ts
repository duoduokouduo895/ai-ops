import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
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
})
