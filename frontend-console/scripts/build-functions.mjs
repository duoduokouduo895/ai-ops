import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import { loadEnv } from 'vite'

const env = loadEnv('production', process.cwd(), 'VITE_')
if ((env.VITE_CONSOLE_AUTH_MODE || 'access') !== 'session') {
  const wrangler = fileURLToPath(new URL('../node_modules/wrangler/bin/wrangler.js', import.meta.url))
  const result = spawnSync(process.execPath, [wrangler, 'pages', 'functions', 'build',
    '--outdir', 'dist/_worker.js', '--build-output-directory', 'dist'], { stdio: 'inherit' })
  if (result.error) throw result.error
  if (result.status !== 0) process.exit(result.status || 1)
}
