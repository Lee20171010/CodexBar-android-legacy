import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

test('Windows child gate does not launch collection without owner approval', async () => {
  const child = spawn(process.execPath, [fileURLToPath(new URL('../scripts/windows-child.js', import.meta.url))], {
    windowsHide: true, stdio: ['pipe', 'pipe', 'pipe']
  });
  let output = '';
  child.stdout.on('data', chunk => { output += chunk; });
  child.stderr.on('data', chunk => { output += chunk; });
  const exited = new Promise((resolve, reject) => {
    child.once('error', reject);
    child.once('exit', code => resolve(code));
  });
  child.stdin.end('invalid\n');
  assert.equal(await exited, 1);
  assert.equal(output, '');
});
