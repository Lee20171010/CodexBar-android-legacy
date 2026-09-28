import assert from 'node:assert/strict';
import test from 'node:test';
import { parseArguments } from '../src/cli-options.js';

test('native Claude paths preserve Unicode and spaces without shell interpretation', () => {
  for (const command of ['C:\\Users\\山田 太郎\\.local\\bin\\claude.exe', '/Users/Zoë/.local/bin/claude', 'C:\\Apps & Tools\\claude.exe']) {
    assert.equal(parseArguments(['--claude-command', command]).claudeCommand, command);
  }
});

test('Claude command rejects control characters and excessive path lengths', () => {
  for (const command of ['claude\n--help', 'claude\0.exe', 'claude\t.exe', 'claude\x7f.exe', 'a'.repeat(32768)]) {
    assert.throws(() => parseArguments(['--claude-command', command]), /Invalid Claude command path/);
  }
});

test('CLI option bounds and background status requirements remain enforced', () => {
  assert.equal(parseArguments(['--port', '45000']).port, 45000);
  assert.throws(() => parseArguments(['--port', '80']), /Port must/);
  assert.throws(() => parseArguments(['--interval-minutes', '0']), /Refresh interval/);
  assert.throws(() => parseArguments(['--status-file', 'status.json']), /requires --background/);
});
