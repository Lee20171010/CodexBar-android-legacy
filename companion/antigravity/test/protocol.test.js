import assert from 'node:assert/strict';
import { createHmac, randomBytes, randomUUID } from 'node:crypto';
import test from 'node:test';
import {
  AUTH_KEY_CONTEXT,
  ENCRYPTION_KEY_CONTEXT,
  deriveKey,
  requestCanonical,
  verifyRequest
} from '../src/protocol.js';

test('uses distinct authentication and encryption keys', () => {
  const masterKey = randomBytes(32);

  assert.notDeepEqual(
    deriveKey(masterKey, AUTH_KEY_CONTEXT),
    deriveKey(masterKey, ENCRYPTION_KEY_CONTEXT)
  );
});

test('accepts an exact signed request once and rejects replay or extra fields', () => {
  const companionId = randomUUID();
  const masterKey = randomBytes(32);
  const authKey = deriveKey(masterKey, AUTH_KEY_CONTEXT);
  const now = 1_787_443_200;
  const request = {
    protocolVersion: 1,
    companionId,
    requestedAtEpochSeconds: now,
    nonce: randomBytes(16).toString('base64url'),
    signature: ''
  };
  request.signature = createHmac('sha256', authKey)
    .update(requestCanonical(request), 'utf8')
    .digest('base64url');
  const seen = new Map();

  assert.equal(verifyRequest(request, companionId, authKey, now, seen), true);
  assert.equal(verifyRequest(request, companionId, authKey, now, seen), false);
  assert.equal(
    verifyRequest({ ...request, extra: true }, companionId, authKey, now, new Map()),
    false
  );
});
