import test from 'node:test';
import assert from 'node:assert/strict';
import { createAI } from '../src/ai.js';

const config = { geminiKey: 'secret-key', geminiModel: 'gemini-2.5-flash' };
test('Groq is preferred when both keys are configured and uses an available default model', async () => {
  const calls = [];
  const ai = createAI({ ...config, groqKey: ' groq-secret ', groqModel: 'openai/gpt-oss-20b' }, async (url, options) => {
    calls.push({ url, ...options });
    return Response.json({ choices: [{ message: { content: '{"reply":"Hello"}' } }] });
  });
  assert.deepEqual(await ai.json('system', { message: 'Hi' }, { type: 'object' }, 400), { reply: 'Hello' });
  assert.equal(ai.provider, 'groq');
  assert.equal(calls.length, 1);
  assert.equal(calls[0].url, 'https://api.groq.com/openai/v1/chat/completions');
  assert.equal(calls[0].headers.Authorization, 'Bearer groq-secret');
  const body = JSON.parse(calls[0].body);
  assert.equal(body.model, 'openai/gpt-oss-20b');
  assert.equal(body.max_completion_tokens, 400);
  assert.deepEqual(body.response_format, { type: 'json_object' });
});
test('Groq failures fall back to Gemini, including invalid JSON responses', async () => {
  for (const failure of [new Response('provider error', { status: 429 }), Response.json({ choices: [{ message: { content: '{incomplete' } }] })]) {
    const calls = [];
    const ai = createAI({ ...config, groqKey: 'groq-secret', groqModel: 'openai/gpt-oss-20b' }, async url => {
      calls.push(url);
      return calls.length === 1 ? failure : Response.json({ candidates: [{ content: { parts: [{ text: '{"reply":"Fallback"}' }] } }] });
    });
    assert.deepEqual(await ai.json('system', {}, {}), { reply: 'Fallback' });
    assert.equal(calls.length, 2);
    assert.ok(calls[1].includes('generativelanguage.googleapis.com'));
  }
});
test('Groq-only errors are sanitized and custom model settings are honored', async () => {
  const ai = createAI({ groqKey: 'groq-secret', groqModel: 'custom-model' }, async (_url, options) => {
    assert.equal(JSON.parse(options.body).model, 'custom-model');
    return new Response('groq-secret provider details', { status: 429 });
  });
  await assert.rejects(() => ai.json('system', {}, {}), error => error.status === 429 && !error.message.includes('groq-secret'));
});
test('Gemini request keeps the API key in the server header and caps output', async () => {
  let captured;
  const ai = createAI(config, async (url, options) => {
    captured = { url, ...options };
    return Response.json({ candidates: [{ content: { parts: [{ text: '{"reply":"Hello"}' }] } }] });
  });
  assert.deepEqual(await ai.json('system', { message: 'Hi' }, { type: 'object' }, 400), { reply: 'Hello' });
  assert.equal(captured.headers['x-goog-api-key'], 'secret-key');
  assert.ok(!captured.url.includes('secret-key'));
  assert.equal(JSON.parse(captured.body).generationConfig.maxOutputTokens, 400);
});
test('provider errors never expose provider responses or credentials', async () => {
  const ai = createAI(config, async () => new Response('secret-key provider error', { status: 429 }));
  await assert.rejects(() => ai.json('system', {}, {}), error => error.status === 429 && !error.message.includes('secret-key'));
});
test('incomplete translation batches fail rather than saving empty translations', async () => {
  const ai = createAI(config, async () => Response.json({ candidates: [{ content: { parts: [{ text: '{"items":[{}]}' }] } }] }));
  await assert.rejects(() => ai.translate(['A salad']), /Incomplete translations/);
});

test('Groq needs only an API key: discovers a compatible model once and reuses it', async () => {
  const calls = [];
  const ai = createAI({ groqKey: 'groq-secret' }, async (url, options) => {
    calls.push({ url, ...options });
    if (url.endsWith('/models')) return Response.json({ data: [{ id: 'whisper-large-v3', active: true }, { id: 'llama-3.3-70b-versatile', active: true }, { id: 'openai/gpt-oss-20b', active: false }] });
    assert.equal(JSON.parse(options.body).model, 'llama-3.3-70b-versatile');
    return Response.json({ choices: [{ message: { content: '{"reply":"Hello"}' } }] });
  });
  await Promise.all([ai.json('system', {}, {}), ai.json('system', {}, {})]);
  assert.equal(calls.filter(call => call.url.endsWith('/models')).length, 1);
  assert.equal(calls.filter(call => call.url.endsWith('/completions')).length, 2);
  assert.ok(calls.every(call => !call.url.includes('groq-secret')));
});

test('Groq can still answer if the model-list endpoint is unavailable', async () => {
  const ai = createAI({ groqKey: 'groq-secret' }, async (url, options) => {
    if (url.endsWith('/models')) return new Response('not available', { status: 503 });
    assert.equal(JSON.parse(options.body).model, 'openai/gpt-oss-20b');
    return Response.json({ choices: [{ message: { content: '{"reply":"Hello"}' } }] });
  });
  assert.deepEqual(await ai.json('system', {}, {}), { reply: 'Hello' });
});
