#!/usr/bin/env node
import process from 'node:process';
import { parseArguments } from './cli-options.js';
import qrcode from 'qrcode-terminal';
import { loadOrCreateIdentity } from './config.js';
import { ClaudeUsageSession } from './claude-cli.js';
import { chooseLocalAddress } from './network.js';
import { SNAPSHOT_SCHEMA_VERSION, SNAPSHOT_SOURCE } from './protocol.js';
import { startSnapshotServer } from './server.js';
import { createBackgroundStatus } from './background-status.js';

const options = parseArguments(process.argv.slice(2));
const reportStatus = createBackgroundStatus(options.statusFile);
reportStatus('starting');
const identity = loadOrCreateIdentity();
const networkSelection = await chooseLocalAddress(options.address);
const address = networkSelection.address;
const usageSession = new ClaudeUsageSession({ command: options.claudeCommand });
let latestSnapshot = null;
let refreshing = false;

async function refreshSnapshot() {
  if (refreshing) return;
  refreshing = true;
  try {
    const parsed = await usageSession.collect();
    latestSnapshot = {
      schemaVersion: SNAPSHOT_SCHEMA_VERSION,
      source: SNAPSHOT_SOURCE,
      generatedAtEpochSeconds: Math.floor(Date.now() / 1000),
      cliVersion: options.cliVersion,
      ...(parsed.tier == null ? {} : { tier: parsed.tier }),
      windows: parsed.windows
    };
    safeStatus(`Quota snapshot refreshed (${parsed.windows.length} window${parsed.windows.length === 1 ? '' : 's'}).`);
    reportStatus('refreshed');
  } catch (error) {
    safeStatus(options.background ? 'Quota refresh failed. Check Claude sign-in or connectivity.' : `Quota refresh failed: ${safeErrorMessage(error)}`);
    reportStatus('refresh-failed');
  } finally {
    refreshing = false;
  }
}

await refreshSnapshot();
const server = await startSnapshotServer({
  address,
  port: options.port,
  identity,
  getSnapshot: () => latestSnapshot
});
const actualPort = server.address().port;
reportStatus('listening');
if (!options.background) {
  const pairingCode = buildPairingCode({ address, port: actualPort, identity });

  process.stdout.write('\nCodexBar Claude companion is ready.\n');
  process.stdout.write(
    `Listening only on ${address}:${actualPort} (${networkSelection.name})\n`
  );
  const alternatives = networkSelection.candidates.filter((candidate) => candidate.address !== address);
  if (alternatives.length > 0) {
    process.stdout.write(
      `Other local addresses: ${alternatives.map((item) => `${item.address} (${item.name})`).join(', ')}\n`
    );
    process.stdout.write('If the phone cannot connect, restart with --address followed by the Wi-Fi address.\n');
  }
  if (latestSnapshot == null) {
    process.stdout.write('No quota snapshot is available yet. Resolve the message above before pairing.\n');
  }
  process.stdout.write('In CodexBar, open Connections > Claude and tap "Scan QR securely in CodexBar".\n');
  process.stdout.write('Do not scan this secret with the system camera or another app.\n\n');
  qrcode.generate(pairingCode, { small: true });
  process.stdout.write(`\nPairing code (keep private):\n${pairingCode}\n\n`);
  process.stdout.write('No Anthropic token, prompt, response, file, email address, or CLI session text is served.\n');
}

const refreshTimer = setInterval(refreshSnapshot, options.intervalMinutes * 60_000);
refreshTimer.unref();
for (const signal of ['SIGINT', 'SIGTERM']) {
  process.once(signal, () => {
    reportStatus('stopping');
    clearInterval(refreshTimer);
    usageSession.close();
    server.close(() => process.exit(0));
  });
}


function buildPairingCode({ address, port, identity }) {
  return [
    'CBCLAUDE1',
    address,
    String(port),
    identity.companionId,
    identity.sharedKey
  ].join('|');
}

function safeStatus(message) {
  process.stdout.write(`[${new Date().toISOString()}] ${message}\n`);
}

function safeErrorMessage(error) {
  const message = error instanceof Error ? error.message : 'Unknown error';
  return message.replace(/[\r\n\t]/g, ' ').slice(0, 180);
}
