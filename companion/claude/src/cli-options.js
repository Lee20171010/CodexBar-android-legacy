import process from 'node:process';
import path from 'node:path';

export function parseArguments(args) {
  const parsed = {
    address: null,
    port: 43823,
    intervalMinutes: 5,
    claudeCommand: process.platform === 'win32' ? 'claude.exe' : 'claude',
    cliVersion: 'official-cli',
    background: false,
    statusFile: null
  };
  for (let index = 0; index < args.length; index += 1) {
    const name = args[index];
    const value = args[index + 1];
    if (name === '--background') { parsed.background = true; continue; }
    if (name === '--address' && value) parsed.address = value;
    else if (name === '--port' && value && Number.isInteger(Number(value))) parsed.port = Number(value);
    else if (name === '--interval-minutes' && value && Number.isInteger(Number(value))) parsed.intervalMinutes = Number(value);
    else if (name === '--claude-command' && value) parsed.claudeCommand = value;
    else if (name === '--cli-version' && value) parsed.cliVersion = value;
    else if (name === '--status-file' && value) parsed.statusFile = value;
    else throw new Error(`Unknown or incomplete argument: ${name}`);
    index += 1;
  }
  if (parsed.port < 1024 || parsed.port > 65535) throw new Error('Port must be between 1024 and 65535');
  if (parsed.intervalMinutes < 1 || parsed.intervalMinutes > 60) throw new Error('Refresh interval must be 1 to 60 minutes');
  if (parsed.claudeCommand.length > 32767 || /[\x00-\x1f\x7f]/.test(parsed.claudeCommand)) throw new Error('Invalid Claude command path');
  if (!/^[A-Za-z0-9._+-]{1,64}$/.test(parsed.cliVersion)) throw new Error('Invalid CLI version label');
  if (parsed.statusFile != null && (!parsed.background || !path.isAbsolute(parsed.statusFile))) {
    throw new Error('--status-file requires --background and an absolute path');
  }
  return parsed;
}
