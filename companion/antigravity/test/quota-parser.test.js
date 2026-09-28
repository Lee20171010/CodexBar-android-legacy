import assert from 'node:assert/strict';
import test from 'node:test';
import { parseModelQuotas } from '../src/quota-parser.js';
import { collectAntigravityUsage } from '../src/local-server.js';

const now = 1_800_000_000;
const model = (label, remainingFraction) => ({ label, quotaInfo: { remainingFraction, resetTime: new Date((now + 3600) * 1000).toISOString() } });
test('exports model usage and reset only, not identity or arbitrary backend fields', () => {
  const result = parseModelQuotas({ userStatus: { email: 'private@example.test', name: 'Private', cascadeModelConfigData: {
    clientModelConfigs: [model('Gemini Pro', 0.75), model('Claude Sonnet', 0)]
  } } }, now);
  assert.deepEqual(result, { windows: [
    { label: 'Gemini Pro', usedFraction: 0.25, resetsAtEpochSeconds: now + 3600 },
    { label: 'Claude Sonnet', usedFraction: 1, resetsAtEpochSeconds: now + 3600 }
  ] });
});
test('never converts absent or invalid quotas to a fabricated 100 percent remaining', () => {
  for (const fraction of [undefined, null, '1', NaN, Infinity, -1, 1.1]) {
    assert.throws(() => parseModelQuotas({ clientModelConfigs: [model('Unknown', fraction)] }, now));
  }
});
test('rejects expired quotas, duplicates and unsafe labels while preserving valid models', () => {
  const expired = model('Expired', 0.5); expired.quotaInfo.resetTime = new Date((now - 300) * 1000).toISOString();
  const result = parseModelQuotas({ clientModelConfigs: [expired, model('A\nB', 1), model('Valid', 0.5), model('valid', 0.3)] }, now);
  assert.equal(result.windows.length, 1);
  assert.equal(result.windows[0].usedFraction, 0.5);
});
test('model-config fallback works and local CSRF credentials never enter snapshots', async () => {
  const calls = [];
  const result = await collectAntigravityUsage({
    discover: async () => [{ ports: [12345], csrf: 'local-secret' }],
    request: async (port, csrf, method) => {
      calls.push(method);
      if (method === 'GetUserStatus') throw new Error('Account private@example.test local-secret');
      return { clientModelConfigs: [{ label: 'Gemini', quotaInfo: { remainingFraction: 0.5 } }] };
    }
  });
  assert.deepEqual(calls, ['GetUserStatus', 'GetCommandModelConfigs']);
  assert.deepEqual(result, { windows: [{ label: 'Gemini', usedFraction: 0.5 }] });
  await assert.rejects(collectAntigravityUsage({ discover: async () => [] }), /Open Antigravity/);
});
