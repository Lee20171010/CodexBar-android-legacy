import { execFile } from 'node:child_process';
import https from 'node:https';
import path from 'node:path';
import { promisify } from 'node:util';
import { parseModelQuotas } from './quota-parser.js';

const execFileAsync = promisify(execFile);
const MAX_RESPONSE_BYTES = 1024 * 1024;

export async function discoverServers() {
  if (process.platform !== 'win32') throw new Error('This companion currently supports the Windows IDE');
  // No account databases or OAuth tokens are read. The local CSRF token is scoped
  // to the running IDE server and stays in this process's memory.
  const script = String.raw`
$ErrorActionPreference = 'Stop'
$listeners = @(Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue)
$servers = @(Get-CimInstance Win32_Process -Filter "Name = 'language_server_windows_x64.exe'" | Where-Object {
  $_.ExecutablePath -match '\\Antigravity\\.*\\extensions\\antigravity\\bin\\language_server_windows_x64\.exe$'
} | Select-Object -First 3 | ForEach-Object {
  $csrf = [regex]::Match($_.CommandLine, '--csrf_token(?:=|\s+)"?([^\s"]+)').Groups[1].Value
  $serverPid = $_.ProcessId
  $ports = @($listeners | Where-Object { $_.OwningProcess -eq $serverPid -and $_.LocalAddress -eq '127.0.0.1' } | Select-Object -ExpandProperty LocalPort -Unique | Sort-Object | Select-Object -First 6)
  if ($csrf -and $ports.Count) { @{ csrf = $csrf; ports = $ports } }
})
ConvertTo-Json -InputObject $servers -Depth 3 -Compress
`;
  const powershell = path.join(process.env.SystemRoot ?? 'C:\\Windows', 'System32', 'WindowsPowerShell', 'v1.0', 'powershell.exe');
  try {
    const { stdout } = await execFileAsync(powershell, ['-NoProfile', '-NonInteractive', '-EncodedCommand', Buffer.from(script, 'utf16le').toString('base64')], {
      windowsHide: true, timeout: 15_000, maxBuffer: 64 * 1024, encoding: 'utf8'
    });
    const servers = JSON.parse(stdout.replace(/^\uFEFF/, ''));
    return Array.isArray(servers) ? servers.filter(server =>
      typeof server.csrf === 'string' && /^[\x21-\x7e]{1,512}$/.test(server.csrf) &&
      Array.isArray(server.ports) && server.ports.length <= 6 &&
      server.ports.every(port => Number.isInteger(port) && port > 0 && port <= 65535)
    ) : [];
  } catch {
    throw new Error('Unable to discover the Antigravity local server');
  }
}

export async function collectAntigravityUsage({ discover = discoverServers, request = requestLocalServer } = {}) {
  const servers = await discover();
  for (const server of servers) {
    for (const port of server.ports) {
      for (const method of ['GetUserStatus', 'GetCommandModelConfigs']) {
        try {
          return parseModelQuotas(await request(port, server.csrf, method));
        } catch {
          // Only fixed errors leave the collector. Upstream errors can include secrets.
        }
      }
    }
  }
  throw new Error('Open Antigravity and sign in to make model quotas available');
}

function requestLocalServer(port, csrf, method) {
  return new Promise((resolve, reject) => {
    const request = https.request({
      hostname: '127.0.0.1', port,
      path: `/exa.language_server_pb.LanguageServerService/${method}`,
      method: 'POST',
      // Antigravity's loopback server uses its own certificate. This exception is
      // confined to a port owned by the discovered IDE process, never a remote URL.
      rejectUnauthorized: false,
      headers: { 'Content-Type': 'application/json', 'Content-Length': '2', 'X-Codeium-Csrf-Token': csrf, 'Connect-Protocol-Version': '1' }
    }, response => {
      const chunks = [];
      let size = 0;
      response.on('data', chunk => {
        size += chunk.length;
        if (size > MAX_RESPONSE_BYTES) { response.destroy(); request.destroy(new Error('Local response too large')); }
        else chunks.push(chunk);
      });
      response.on('error', reject);
      response.on('end', () => {
        if (response.statusCode !== 200) return reject(new Error('Local method unavailable'));
        try { resolve(JSON.parse(Buffer.concat(chunks).toString('utf8'))); }
        catch { reject(new Error('Invalid local response')); }
      });
    });
    const deadline = setTimeout(() => request.destroy(new Error('Local request timed out')), 3_000);
    request.once('close', () => clearTimeout(deadline));
    request.on('error', reject);
    request.end('{}');
  });
}
