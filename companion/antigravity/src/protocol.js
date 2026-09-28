import { createCipheriv, createHmac, randomBytes, timingSafeEqual } from 'node:crypto';

export const PROTOCOL_VERSION = 1;
export const SNAPSHOT_SCHEMA_VERSION = 1;
export const SNAPSHOT_SOURCE = 'antigravity-local-server';
export const AUTH_KEY_CONTEXT = 'codexbar-antigravity-auth-v1';
export const ENCRYPTION_KEY_CONTEXT = 'codexbar-antigravity-encryption-v1';
const MAX_CLOCK_SKEW_SECONDS = 120;

export function decodeMasterKey(value) {
  if (typeof value !== 'string' || !/^[A-Za-z0-9_-]{43}=?$/.test(value)) {
    throw new Error('Invalid companion key');
  }
  const key = Buffer.from(value, 'base64url');
  if (key.length !== 32) throw new Error('Invalid companion key length');
  return key;
}

export function deriveKey(masterKey, context) {
  return createHmac('sha256', masterKey).update(context, 'utf8').digest();
}

export function requestCanonical(request) {
  return [
    request.protocolVersion,
    request.companionId,
    request.requestedAtEpochSeconds,
    request.nonce
  ].join('\n');
}

export function responseCanonical(envelope) {
  return [
    envelope.protocolVersion,
    envelope.companionId,
    envelope.requestNonce,
    envelope.sentAtEpochSeconds
  ].join('\n');
}

export function verifyRequest(request, companionId, authKey, nowEpochSeconds, seenNonces) {
  if (!isExactRequest(request) || request.protocolVersion !== PROTOCOL_VERSION || request.companionId !== companionId) {
    return false;
  }
  if (Math.abs(request.requestedAtEpochSeconds - nowEpochSeconds) > MAX_CLOCK_SKEW_SECONDS) {
    return false;
  }
  if (seenNonces.has(request.nonce)) return false;
  let provided;
  try {
    provided = Buffer.from(request.signature, 'base64url');
  } catch {
    return false;
  }
  const expected = createHmac('sha256', authKey)
    .update(requestCanonical(request), 'utf8')
    .digest();
  if (provided.length !== expected.length || !timingSafeEqual(provided, expected)) return false;
  seenNonces.set(request.nonce, Date.now());
  return true;
}

export function encryptSnapshot(snapshot, request, companionId, encryptionKey, nowEpochSeconds) {
  const iv = randomBytes(12);
  const envelope = {
    protocolVersion: PROTOCOL_VERSION,
    companionId,
    requestNonce: request.nonce,
    sentAtEpochSeconds: nowEpochSeconds,
    iv: iv.toString('base64url'),
    ciphertext: ''
  };
  const cipher = createCipheriv('aes-256-gcm', encryptionKey, iv);
  cipher.setAAD(Buffer.from(responseCanonical(envelope), 'utf8'));
  const encrypted = Buffer.concat([
    cipher.update(JSON.stringify(snapshot), 'utf8'),
    cipher.final(),
    cipher.getAuthTag()
  ]);
  envelope.ciphertext = encrypted.toString('base64url');
  return envelope;
}

function isExactRequest(request) {
  if (request == null || typeof request !== 'object' || Array.isArray(request)) return false;
  const keys = Object.keys(request).sort();
  const expectedKeys = [
    'companionId',
    'nonce',
    'protocolVersion',
    'requestedAtEpochSeconds',
    'signature'
  ];
  if (JSON.stringify(keys) !== JSON.stringify(expectedKeys)) return false;
  return Number.isSafeInteger(request.protocolVersion) &&
    typeof request.companionId === 'string' &&
    Number.isSafeInteger(request.requestedAtEpochSeconds) &&
    typeof request.nonce === 'string' && /^[A-Za-z0-9_-]{22}$/.test(request.nonce) &&
    typeof request.signature === 'string' && /^[A-Za-z0-9_-]{43}$/.test(request.signature);
}
