import { randomBytes, randomUUID } from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

export function loadOrCreateIdentity(homeDirectory = os.homedir()) {
  const directory = path.join(homeDirectory, '.codexbar');
  const configPath = path.join(directory, 'antigravity-companion.json');
  fs.mkdirSync(directory, { recursive: true, mode: 0o700 });
  if (fs.existsSync(configPath)) {
    const parsed = JSON.parse(fs.readFileSync(configPath, 'utf8'));
    if (isValidIdentity(parsed)) return parsed;
    throw new Error(`Invalid companion identity file: ${configPath}`);
  }

  const identity = {
    protocolVersion: 1,
    companionId: randomUUID(),
    sharedKey: randomBytes(32).toString('base64url')
  };
  const temporaryPath = `${configPath}.${process.pid}.tmp`;
  fs.writeFileSync(temporaryPath, `${JSON.stringify(identity, null, 2)}\n`, {
    encoding: 'utf8',
    mode: 0o600,
    flag: 'wx'
  });
  fs.renameSync(temporaryPath, configPath);
  return identity;
}

function isValidIdentity(value) {
  return value?.protocolVersion === 1 &&
    typeof value.companionId === 'string' &&
    /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(value.companionId) &&
    typeof value.sharedKey === 'string' &&
    /^[A-Za-z0-9_-]{43}$/.test(value.sharedKey);
}
