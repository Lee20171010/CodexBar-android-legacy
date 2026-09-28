import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, unlinkSync, rmdirSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { createBackgroundStatus } from '../src/background-status.js';

test('background status retains last success across failures without including account data', t => {
  const folder = mkdtempSync(path.join(os.tmpdir(), 'claude-status-'));
  const file = path.join(folder, 'status.json');
  t.after(() => { unlinkSync(file); rmdirSync(folder); });
  const report = createBackgroundStatus(file);
  report('starting');
  assert.equal(JSON.parse(readFileSync(file)).lastSuccessEpochSeconds, null);
  report('refreshed');
  const success = JSON.parse(readFileSync(file));
  report('refresh-failed');
  report('listening');
  const failed = JSON.parse(readFileSync(file));
  assert.equal(failed.lastSuccessEpochSeconds, success.lastSuccessEpochSeconds);
  assert.equal(failed.consecutiveFailures, 1);
  assert.deepEqual(Object.keys(failed).sort(), ['consecutiveFailures', 'lastSuccessEpochSeconds', 'pid', 'state', 'updatedAtEpochSeconds']);
  report('refreshed');
  assert.equal(JSON.parse(readFileSync(file)).consecutiveFailures, 0);
  report('CBCLAUDE1|not-a-status');
  assert.equal(JSON.parse(readFileSync(file)).state, 'refreshed');
});

test('status reporting is optional and write errors do not break collection', () => {
  assert.doesNotThrow(() => createBackgroundStatus(null)('refreshed'));
  assert.doesNotThrow(() => createBackgroundStatus(path.join(import.meta.dirname, 'background-status.test.js', 'invalid'))('refreshed'));
});
