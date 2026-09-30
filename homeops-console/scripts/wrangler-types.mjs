import { access, copyFile, rm } from 'node:fs/promises'
import { spawn } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import path from 'node:path'

const projectDirectory = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const developmentVariables = path.join(projectDirectory, '.dev.vars')
const exampleVariables = path.join(projectDirectory, '.dev.vars.example')
const wranglerEntrypoint = path.join(projectDirectory, 'node_modules', 'wrangler', 'bin', 'wrangler.js')
let createdTemporaryVariables = false

try {
  await access(developmentVariables)
} catch {
  await copyFile(exampleVariables, developmentVariables)
  createdTemporaryVariables = true
}

try {
  const argumentsList = [wranglerEntrypoint, 'types', '--path=./functions/types.d.ts']
  if (process.argv.includes('--check')) argumentsList.push('--check')
  const exitCode = await new Promise((resolve, reject) => {
    const child = spawn(process.execPath, argumentsList, { cwd: projectDirectory, stdio: 'inherit' })
    child.once('error', reject)
    child.once('exit', (code) => resolve(code ?? 1))
  })
  if (exitCode !== 0) process.exitCode = exitCode
} finally {
  if (createdTemporaryVariables) await rm(developmentVariables, { force: true })
}
