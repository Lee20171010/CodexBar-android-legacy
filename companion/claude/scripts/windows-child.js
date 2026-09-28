// The resident assigns this process to its Windows Job Object before releasing
// the gate. No Claude/ConPTY descendant can start outside the owned job.
const timeout = setTimeout(() => process.exit(1), 30_000);
let input = '';
let started = false;
process.stdin.setEncoding('utf8');
process.stdin.on('end', () => { if (!started) process.exit(1); });
process.stdin.on('data', async (chunk) => {
  if (started) return;
  input += chunk;
  if (input.length > 16) process.exit(1);
  if (!input.includes('\n')) return;
  if (input.trim() !== 'start') process.exit(1);
  started = true;
  clearTimeout(timeout);
  process.stdin.pause();
  try {
    await import('../src/index.js');
  } catch {
    // The resident reports a retry without persisting arbitrary error output.
    process.exit(1);
  }
});
