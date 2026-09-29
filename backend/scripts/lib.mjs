/** Shared by the backend scripts: reads keys from the environment or a local, git-ignored `.dev.vars`. */
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

export const BACKEND_DIR = join(dirname(fileURLToPath(import.meta.url)), '..')

export function loadEnv() {
  const env = { ...process.env }
  try {
    for (const line of readFileSync(join(BACKEND_DIR, '.dev.vars'), 'utf8').split(/\r?\n/)) {
      const m = /^\s*([A-Z0-9_]+)\s*=\s*(.*?)\s*$/.exec(line)
      if (m && !line.trim().startsWith('#')) env[m[1]] = m[2].replace(/^["']|["']$/g, '')
    }
  } catch { /* no .dev.vars: keys come from the environment */ }
  return env
}

export const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
export const flag = (name, fallback = null) => {
  const hit = process.argv.find((a) => a === `--${name}` || a.startsWith(`--${name}=`))
  return hit === undefined ? fallback : hit.includes('=') ? hit.split('=')[1] : true
}
