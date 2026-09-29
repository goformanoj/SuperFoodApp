/**
 * MEASURED results: how each model did on JARVIS's real probe requests.
 *
 * Written by `node scripts/probe.mjs --write`, never by hand. Empty until someone
 * has run the probe with real keys; while it is empty the router falls back to
 * ordering models by size (see catalog.js `rankModels`).
 *
 *   models: { "<model id>": { passed, total, medianMs } }
 */
export const SCORECARD = { generatedAt: null, models: {} }
