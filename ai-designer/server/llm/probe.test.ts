import assert from 'node:assert/strict';
import { test } from 'node:test';
import { getLlmConfig, updateLlmConfig } from '../config/llmConfig';
import { probeLlmConnection } from './probe';

test('LLM connection probe uses a short Anthropic-compatible request', async () => {
  const previous = getLlmConfig();
  updateLlmConfig({
    baseUrl: 'https://llm.example.test/base/',
    model: 'test-model',
    authToken: 'test-token',
    apiKey: '',
  });
  try {
    let called = false;
    const fetchMock: typeof fetch = async (input, init) => {
      called = true;
      assert.equal(String(input), 'https://llm.example.test/base/v1/messages');
      assert.equal((init?.headers as Record<string, string>).authorization, 'Bearer test-token');
      const body = JSON.parse(String(init?.body)) as { model: string; max_tokens: number; messages: unknown[] };
      assert.equal(body.model, 'test-model');
      assert.equal(body.max_tokens, 512);
      assert.equal(body.messages.length, 1);
      return new Response(JSON.stringify({ content: [{ type: 'text', text: 'OK' }] }), { status: 200 });
    };

    await probeLlmConnection(fetchMock, 1_000);
    assert.equal(called, true);
  } finally {
    updateLlmConfig(previous);
  }
});

test('LLM connection probe rejects a 200 response without Anthropic text content', async () => {
  const previous = getLlmConfig();
  updateLlmConfig({ authToken: 'test-token', apiKey: '' });
  try {
    const fetchMock: typeof fetch = async () => new Response(JSON.stringify({ content: [] }), { status: 200 });
    await assert.rejects(() => probeLlmConnection(fetchMock, 1_000), /no Anthropic text content/);
  } finally {
    updateLlmConfig(previous);
  }
});
