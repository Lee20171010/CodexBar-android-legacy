import fs from 'node:fs';
import path from 'node:path';

export function createBackgroundStatus(filePath) {
  const states = new Set(['starting', 'refreshed', 'refresh-failed', 'listening', 'stopping']);
  let lastSuccess = null;
  let consecutiveFailures = 0;
  return (state) => {
    if (filePath == null || !states.has(state)) return;
    const now = Math.floor(Date.now() / 1000);
    if (state === 'refreshed') {
      lastSuccess = now;
      consecutiveFailures = 0;
    } else if (state === 'refresh-failed') {
      consecutiveFailures += 1;
    }
    // Fixed fields only: never persist the QR, pairing identity or CLI output.
    const status = { pid: process.pid, state, updatedAtEpochSeconds: now,
      lastSuccessEpochSeconds: lastSuccess, consecutiveFailures };
    try {
      fs.mkdirSync(path.dirname(filePath), { recursive: true });
      const temporary = `${filePath}.${process.pid}.tmp`;
      fs.writeFileSync(temporary, JSON.stringify(status), { mode: 0o600 });
      fs.renameSync(temporary, filePath);
    } catch {
      // Status display must never interrupt collection or the phone connection.
    }
  };
}
