#!/usr/bin/env node
/**
 * Bake a shared app trace into a pack entry (Part C2.3).
 *
 *   node backend/scripts/bake-pack.mjs <trace.json> [package] [name]
 *
 * Reads a C2.1 app-trace JSON, distils the app's learned control labels, and prints a
 * `packs.js`-ready entry plus what this trace ADDS over what is already served. The
 * flow: run this on a trace the user shared → review the `added` labels → paste the
 * entry into `backend/src/packs.js` → commit; Cloudflare Git-Builds redeploys `main`,
 * and every install gets the new labels with no reinstall.
 *
 * `package` is optional — when omitted, the package named in the trace's own learned
 * lines is used (the device now records it). Pass it to bake a specific app.
 */
import { readFileSync } from 'node:fs'
import { distill } from '../src/packBake.js'

const [, , tracePath, pkg, name] = process.argv
if (!tracePath) {
  console.error('usage: node backend/scripts/bake-pack.mjs <trace.json> [package] [name]')
  process.exit(1)
}

let trace
try {
  trace = JSON.parse(readFileSync(tracePath, 'utf8'))
} catch (e) {
  console.error(`could not read/parse ${tracePath}: ${e.message}`)
  process.exit(1)
}

const result = distill(trace, { package: pkg, name })
if (!result) {
  console.error('no learned labels found in this trace — nothing to bake.')
  console.error('(a trace only teaches a label when a generic intent resolved to a real one on screen.)')
  process.exit(2)
}

const addedCount = Object.values(result.added).reduce((n, arr) => n + arr.length, 0)
console.log(`# ${addedCount} new label(s) from this trace for ${result.pack.package}`)
if (addedCount > 0) console.log('# added:', JSON.stringify(result.added))
console.log('# paste this entry into the PACKS array in backend/src/packs.js:')
console.log(renderEntry(result.pack))

/** Render a pack as a PACKS-array literal (version/package are added at serve time). */
function renderEntry(pack) {
  const controls = Object.entries(pack.controls)
    .map(([intent, labels]) => `    ${intent}: ${JSON.stringify(labels)},`)
    .join('\n')
  return [
    '  {',
    `    match: ${JSON.stringify(pack.match)},`,
    `    name: ${JSON.stringify(pack.name)},`,
    '    controls: {',
    controls,
    '    },',
    '  },',
  ].join('\n')
}
