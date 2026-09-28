# Antigravity companion (Windows)

Read-only model quotas from the running, signed-in Antigravity IDE. Google credentials remain on the PC. This is independent of Gemini CLI and Claude subscriptions.

Requires Windows, Node.js 20+, and the Antigravity IDE running on this PC. No prompts or inference requests are sent. The adapter reads `GetUserStatus`, falling back to `GetCommandModelConfigs`, on a loopback port owned by the Antigravity language-server process. Its local CSRF token is used only in memory. These internal IDE methods can change in future versions.

## Connect

1. Open Antigravity and sign in there.
2. Run `start-windows.cmd --address YOUR_PRIVATE_IPV4`. For different networks, use the PC's Tailscale `100.x` address; keep both devices connected to the same tailnet. Selecting an exit node alone does not pair CodexBar.
3. In the updated Android app, open **Connections → Antigravity → Scan QR securely in CodexBar**, then **Pair & verify**. Keep the QR secret; do not use another camera app.

The default port is **43824**. HMAC authentication, per-request nonces and AES-256-GCM protect snapshots on the private connection. Only model labels, measured usage fractions, reset times and snapshot timestamps leave the PC. Identity, email, OAuth tokens, prompts, files and conversations are excluded. Missing quotas remain unavailable rather than being reported as unused. These are IDE model quotas; weekly/global/credit balances are not inferred.

## Stay running

After pairing, run in PowerShell:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/windows-resident.ps1 -Action Install -Address YOUR_PRIVATE_IPV4
```

This installs a separate **CodexBar Antigravity Companion** tray app under `%LOCALAPPDATA%\CodexBar\AntigravityCompanion`, with current-user logon startup and crash recovery. Start-menu shortcuts show status, restart or pause. It refreshes every five minutes; pause persists until Restart. Antigravity itself must stay open and the PC awake. The tray app never closes or launches the IDE. To remove startup, choose **Disable auto-start and exit**; local pairing files remain preserved.

## Development

`npm ci`, `npm test`, `npm run check`. The Windows resident also supports `-Action Check` to validate WinForms and its child-process job. Parser tests use synthetic payloads with no account data. Reference for the local API behavior: [upstream CodexBar Antigravity adapter](https://github.com/steipete/CodexBar/blob/main/docs/antigravity.md).
