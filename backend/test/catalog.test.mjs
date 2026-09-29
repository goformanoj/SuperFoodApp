import { test } from 'node:test'
import assert from 'node:assert/strict'
import { PLATFORMS, classify, parseParams, rankModels, suitability, tierOf } from '../src/providers/catalog.js'

test('parameter counts are read from real model ids, including mixture-of-experts', () => {
  assert.deepEqual(parseParams('openai/gpt-oss-120b'), { totalB: 120, activeB: null })
  assert.deepEqual(parseParams('nvidia/nemotron-3-super-120b-a12b:free'), { totalB: 120, activeB: 12 })
  assert.deepEqual(parseParams('google/gemma-4-26b-a4b-it:free'), { totalB: 26, activeB: 4 })
  assert.deepEqual(parseParams('qwen/qwen3.8-27b:free'), { totalB: 27, activeB: null })
  assert.deepEqual(parseParams('liquid/lfm-2.5-2.6b:free'), { totalB: 2.6, activeB: null })
  assert.deepEqual(parseParams('llama3.2:3b'), { totalB: 3, activeB: null })
  assert.deepEqual(parseParams('nvidia/nemotron-3-nano-omni-30b-a3b-reasoning:free'), { totalB: 30, activeB: 3 })
})

test('a version number is never mistaken for a size', () => {
  // "3.8" in qwen3.8 and "2.5" in lfm-2.5 are versions, not billions of parameters.
  assert.equal(parseParams('qwen/qwen3.8-27b').totalB, 27)
  assert.deepEqual(parseParams('thinkingmachines/inkling:free'), { totalB: null, activeB: null })
})

test('tiers: large from 100B, medium from 20B, small below; words when there is no size', () => {
  assert.equal(tierOf('openai/gpt-oss-120b'), 'large')
  assert.equal(tierOf('openai/gpt-oss-20b'), 'medium')
  assert.equal(tierOf('llama3.2:3b'), 'small')
  assert.equal(tierOf('thinkingmachines/inkling-small:free'), 'small')
  assert.equal(tierOf('acme/model-ultra'), 'large')
  assert.equal(tierOf('thinkingmachines/inkling:free'), 'unknown')
})

test('classify reads tool and image support from the platform metadata', () => {
  const p = classify('google/gemma-4-31b-it:free', {
    supported_parameters: ['tools', 'temperature'],
    architecture: { input_modalities: ['text', 'image'] },
  })
  assert.equal(p.tools, true)
  assert.equal(p.vision, true)
  assert.equal(p.tier, 'medium')
  assert.equal(classify('x/y-7b', {}).tools, false)
  assert.equal(classify('nvidia/nemotron-3-nano-omni-30b-a3b-reasoning:free').moe, true)
})

test('a model can drive the agent if it is big enough and calls tools — until measured otherwise', () => {
  const big = classify('nvidia/nemotron-3-super-120b-a12b:free', { supported_parameters: ['tools'] })
  const small = classify('liquid/lfm-2.5-2.6b:free', { supported_parameters: ['tools'] })
  assert.ok(suitability(big).includes('agent'))
  assert.ok(!suitability(small).includes('agent'))
  // Measured beats guessed, in both directions.
  assert.ok(suitability(small, { passed: 4, total: 4 }).includes('agent'))
  assert.ok(!suitability(big, { passed: 1, total: 4 }).includes('agent'))
  assert.ok(suitability(big).includes('chat'))
})

test('ranking: bigger first when nothing is measured; a measured failure sinks a big model', () => {
  const ids = ['a/x-27b', 'b/y-120b', 'c/z-3b']
  const profiles = ids.map((id) => classify(id, { supported_parameters: ['tools'] }))
  assert.deepEqual(rankModels(profiles).map((p) => p.id), ['b/y-120b', 'a/x-27b', 'c/z-3b'])
  const scorecard = { 'b/y-120b': { passed: 0, total: 4 }, 'c/z-3b': { passed: 4, total: 4 } }
  const ranked = rankModels(profiles, scorecard).map((p) => p.id)
  assert.equal(ranked[0], 'c/z-3b')
  assert.equal(ranked.at(-1), 'b/y-120b')
})

test('platforms whose free tier trains on prompts are flagged; Groq leads', () => {
  assert.equal(PLATFORMS.gemini.trainsOnFreeTier, true)
  assert.equal(PLATFORMS.mistral.trainsOnFreeTier, true)
  for (const name of ['groq', 'cerebras', 'openrouter']) assert.equal(PLATFORMS[name].trainsOnFreeTier, false, name)
  const byPriority = Object.entries(PLATFORMS).sort((a, b) => a[1].priority - b[1].priority)
  assert.equal(byPriority[0][0], 'groq')
  // Only Groq can serve /search: nobody else has a built-in web search.
  assert.deepEqual(Object.keys(PLATFORMS).filter((n) => PLATFORMS[n].caps.builtinSearch), ['groq'])
})
