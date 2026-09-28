import dgram from 'node:dgram';
import net from 'node:net';
import os from 'node:os';

export async function chooseLocalAddress(requestedAddress) {
  const candidates = localCandidates();
  if (requestedAddress != null) {
    if (!isAllowedAddress(requestedAddress)) {
      throw new Error('The --address value must be a numeric private or loopback IPv4 address');
    }
    const assigned = candidates.find((candidate) => candidate.address === requestedAddress);
    if (assigned == null) {
      throw new Error(`The --address value is not assigned to this computer: ${requestedAddress}`);
    }
    return { ...assigned, candidates };
  }

  const externalCandidates = candidates.filter((candidate) => !candidate.internal);
  if (externalCandidates.length === 0) {
    throw new Error('No private IPv4 address found. Connect both devices to the same Wi-Fi or pass --address.');
  }

  const defaultRouteAddress = await detectDefaultRouteAddress();
  const defaultRouteCandidate = externalCandidates.find(
    (candidate) => candidate.address === defaultRouteAddress && !isVirtualInterface(candidate.name)
  );
  const selected = defaultRouteCandidate ?? externalCandidates.sort(candidatePreference)[0];
  return { ...selected, candidates: externalCandidates };
}

export function isAllowedAddress(address) {
  if (net.isIP(address) !== 4) return false;
  const parts = address.split('.').map(Number);
  return parts[0] === 10 ||
    (parts[0] === 172 && parts[1] >= 16 && parts[1] <= 31) ||
    (parts[0] === 192 && parts[1] === 168) ||
    (parts[0] === 169 && parts[1] === 254) ||
    parts[0] === 127 ||
    (parts[0] === 100 && parts[1] >= 64 && parts[1] <= 127);
}

function localCandidates() {
  return Object.entries(os.networkInterfaces())
    .flatMap(([name, entries]) => (entries ?? []).map((entry) => ({ name, ...entry })))
    .filter((entry) => entry.family === 'IPv4' && isAllowedAddress(entry.address))
    .map((entry) => ({
      name: entry.name,
      address: entry.address,
      internal: entry.internal
    }));
}

function candidatePreference(left, right) {
  return interfaceRank(left.name) - interfaceRank(right.name) ||
    addressRank(left.address) - addressRank(right.address) ||
    left.name.localeCompare(right.name, 'en') ||
    left.address.localeCompare(right.address, 'en');
}

function interfaceRank(name) {
  if (isVirtualInterface(name)) return 10;
  if (/wi-?fi|wireless|wlan/i.test(name)) return 0;
  if (/ethernet|^en\d|^eth\d/i.test(name)) return 1;
  return 3;
}

function addressRank(value) {
  if (value.startsWith('192.168.')) return 0;
  if (value.startsWith('10.')) return 1;
  if (value.startsWith('172.')) return 2;
  if (value.startsWith('100.')) return 3;
  return 4;
}

function isVirtualInterface(name) {
  return /vEthernet|WSL|Docker|Hyper-V|VMware|VirtualBox|Tailscale|ZeroTier|WireGuard|VPN|Loopback/i.test(name);
}

function detectDefaultRouteAddress() {
  return new Promise((resolve) => {
    const socket = dgram.createSocket('udp4');
    let settled = false;
    const finish = (value) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      try {
        socket.close();
      } catch {
        // The socket may not have reached a bound state.
      }
      resolve(value);
    };
    const timer = setTimeout(() => finish(null), 750);
    socket.once('error', () => finish(null));
    socket.connect(53, '1.1.1.1', () => {
      const address = socket.address();
      finish(typeof address === 'object' ? address.address : null);
    });
  });
}
