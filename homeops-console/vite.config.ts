import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  build: {
    target: 'es2022',
    cssCodeSplit: true,
    sourcemap: true,
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['./tests/ui/setup.ts'],
    include: ['./tests/ui/**/*.spec.ts'],
  },
})
