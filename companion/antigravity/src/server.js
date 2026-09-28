import net from 'node:net';
import {
  AUTH_KEY_CONTEXT,
  ENCRYPTION_KEY_CONTEXT,
  decodeMasterKey,
  deriveKey,
  encryptSnapshot,
  verifyRequest
} from './protocol.js';

const MAX_REQUEST_BYTES = 8 * 1024;
const SOCKET_TIMEOUT_MILLIS = 8_000;
const NONCE_TTL_MILLIS = 5 * 60 * 1000;
const MAX_REQUESTS_PER_MINUTE = 30;

export function startSnapshotServer({ address, port, identity, getSnapshot }) {
  const masterKey = decodeMasterKey(identity.sharedKey);
  const authKey = deriveKey(masterKey, AUTH_KEY_CONTEXT);
  const encryptionKey = deriveKey(masterKey, ENCRYPTION_KEY_CONTEXT);
  const seenNonces = new Map();
  const requestBuckets = new Map();

  const server = net.createServer((socket) => {
    socket.setTimeout(SOCKET_TIMEOUT_MILLIS, () => socket.destroy());
    const remoteAddress = socket.remoteAddress ?? 'unknown';
    if (!takeRateLimit(requestBuckets, remoteAddress)) {
      socket.destroy();
      return;
    }
    let input = '';
    socket.setEncoding('utf8');
    socket.on('data', (chunk) => {
      input += chunk;
      if (Buffer.byteLength(input, 'utf8') > MAX_REQUEST_BYTES) {
        socket.destroy();
        return;
      }
      const newlineIndex = input.indexOf('\n');
      if (newlineIndex < 0) return;
      socket.pause();
      const nowEpochSeconds = Math.floor(Date.now() / 1000);
      pruneNonces(seenNonces);
      try {
        const request = JSON.parse(input.slice(0, newlineIndex));
        if (!verifyRequest(request, identity.companionId, authKey, nowEpochSeconds, seenNonces)) {
          socket.destroy();
          return;
        }
        const snapshot = getSnapshot();
        if (snapshot == null) {
          socket.destroy();
          return;
        }
        const envelope = encryptSnapshot(
          snapshot,
          request,
          identity.companionId,
          encryptionKey,
          nowEpochSeconds
        );
        socket.end(`${JSON.stringify(envelope)}\n`);
      } catch {
        socket.destroy();
      }
    });
    socket.on('error', () => {
      // Invalid and disconnected clients are intentionally silent.
    });
  });
  server.maxConnections = 16;
  return new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(port, address, () => resolve(server));
  });
}

function pruneNonces(seenNonces) {
  const cutoff = Date.now() - NONCE_TTL_MILLIS;
  for (const [nonce, seenAt] of seenNonces) {
    if (seenAt < cutoff) seenNonces.delete(nonce);
  }
}

function takeRateLimit(buckets, remoteAddress) {
  const minute = Math.floor(Date.now() / 60_000);
  const current = buckets.get(remoteAddress);
  if (current?.minute === minute) {
    if (current.count >= MAX_REQUESTS_PER_MINUTE) return false;
    current.count += 1;
    return true;
  }
  buckets.set(remoteAddress, { minute, count: 1 });
  if (buckets.size > 128) {
    for (const [key, value] of buckets) {
      if (value.minute !== minute) buckets.delete(key);
    }
  }
  return true;
}
