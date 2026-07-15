import { gzipSync } from 'node:zlib'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'

const root = new URL('../dist/', import.meta.url)
const manifest = JSON.parse(readFileSync(new URL('.vite/manifest.json', root), 'utf8'))
const entry = Object.values(manifest).find((item) => item.isEntry)
if (!entry) throw new Error('Vite entry chunk is missing')

const visited = new Set()
const js = new Set()
const css = new Set()
function visit(item) {
  if (!item || visited.has(item.file)) return
  visited.add(item.file)
  if (item.file.endsWith('.js')) js.add(item.file)
  for (const file of item.css || []) css.add(file)
  for (const name of item.imports || []) visit(manifest[name])
}
visit(entry)

const gzipBytes = (files) => [...files].reduce((total, file) => total + gzipSync(readFileSync(new URL(file, root))).length, 0)
const jsBytes = gzipBytes(js)
const cssBytes = gzipBytes(css)
const kb = (bytes) => (bytes / 1024).toFixed(1)
console.log(`Initial JS gzip: ${kb(jsBytes)} KB; CSS gzip: ${kb(cssBytes)} KB`)
if (jsBytes > 220 * 1024) throw new Error(`Initial JS exceeds 220 KB gzip: ${kb(jsBytes)} KB`)
if (cssBytes > 60 * 1024) throw new Error(`Initial CSS exceeds 60 KB gzip: ${kb(cssBytes)} KB`)
