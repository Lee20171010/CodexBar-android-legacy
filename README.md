# CodexBar for Android

> Android port of [**CodexBar**](https://github.com/steipete/CodexBar) by [@steipete](https://github.com/steipete) — the macOS menu bar app for monitoring AI service quotas.

Monitor AI service quotas from your Android device. Track Claude, Codex (ChatGPT), GitHub Copilot, Gemini, Cursor, z.ai, ZenMux, Kimi Code, ElevenLabs, OpenRouter, Synthetic, Chutes, DeepSeek, Venice, Moonshot API, Cline, IBM Bob, Fireworks AI, Devin, and Antigravity usage in one place. Optional private companions keep Claude and Gemini authentication inside their official CLIs and add local Codex context/token telemetry without exporting session content.

<p align="center">
  <img src="docs/images/releases/v0.9.4/usage-light.png" width="320" alt="v0.9.4 compact four-provider Usage screen" />
  &nbsp;&nbsp;
  <img src="docs/images/releases/v0.9.4/widget-dual_segments.png" width="320" alt="v0.9.4 dual segmented widget at 347 by 69dp" />
</p>

<p align="center"><sub>Demo quota data is shown in screenshots. No account credentials are included.</sub></p>

## Features

v0.9.4 adds Antigravity desktop model quotas, distinct compact model labels, and Windows tray companions that start at sign-in and recover after failures. Claude also recovers stalled quota sessions automatically. See the [release notes](docs/releases/v0.9.4.md).

- Unified quota monitoring for 21 providers: Claude, Codex, GitHub Copilot, Gemini, Cursor, z.ai, ZenMux, Kimi Code, ElevenLabs, OpenRouter, Synthetic, Chutes, DeepSeek, Venice, Moonshot API, Cline, IBM Bob, Fireworks AI, Devin, OpenCode Go, and Antigravity
- Four complete selectable design systems: the existing Material 3 UI, Liquid Glass, WinUI 3, and Aurora, each with light/dark support
- A compact Usage overview with official provider icons, a saved custom provider order, two quota windows per provider, symmetric cards, and details on tap
- Provider cards with animated rings, bars, exact values, reset countdowns, pace forecasts, and retained history charts
- Codex reset-credit inventory, provider-reported model quota windows, and exact expiry details
- Optional privacy-preserving Codex companion for current context usage and input, cached-input, output, reasoning, daily, and per-model token totals
- Adaptive phone navigation and a two-pane large-screen dashboard
- Quick Settings tile for at-a-glance status
- Twelve widget layouts with a live 347 × 69dp preview, background RGB and opacity, light/dark text, per-provider colors, provider order, corner radius, and text size
- Configurable background refresh plus explicit refresh actions that supersede stale queued work
- Built-in stable-release update prompt with a direct link to this fork's signed APK
- Independently configurable persistent notification and time-bounded API 36 promoted Live Update, both synchronized with every dashboard refresh
- Live progress, remaining quota, reset, pace, Refresh, and Stop on eligible Android 16 / One UI Now Bar surfaces, with a compatible ongoing notification elsewhere
- Now Bar entries that name the provider and its remaining quota, summarize the other monitored providers when expanded, and open that provider's detail when tapped
- Secure device-code account connection for Codex and GitHub Copilot, plus an encrypted local Claude Code companion that never exports Anthropic credentials
- Claude companion pairing from one QR scan or a pasted code, and automatic reconnection when the companion's computer receives a new local address
- Push alert when quota resets (fully replenished)
- DataStore + Android Keystore-backed credential storage
- English and Japanese per-app language selection
- Dynamic color for Material 3, light/dark appearance, selectable visual styles, and responsive widget layouts

## Selectable design systems and Codex insights

In **Settings > Preferences**, enable **Minimal display** to hide the card age on dashboard cards while keeping provider, status, quota windows as horizontal bars with remaining percentages and reset times, and any staleness or error reason. This preference is saved on-device and defaults to off. Tapping a card still opens full details; navigation, connections, widgets, and quota collection are unchanged.

<p align="center">
  <img src="docs/images/releases/v0.9.0/dashboard-material3.png" width="210" alt="Material 3 dashboard" />
  <img src="docs/images/releases/v0.9.0/dashboard-liquid-glass.png" width="210" alt="Liquid Glass dashboard" />
  <img src="docs/images/releases/v0.9.0/dashboard-winui3.png" width="210" alt="WinUI 3 dashboard" />
  <img src="docs/images/releases/v0.9.0/dashboard-aurora-dark.png" width="210" alt="Aurora dashboard in dark mode" />
</p>

<p align="center">
  <img src="docs/images/releases/v0.9.0/codex-insights-liquid-glass.png" width="320" alt="Codex reset-credit and context detail" />
  <img src="docs/images/releases/v0.9.0/codex-token-history-liquid-glass.png" width="320" alt="Codex daily token history" />
</p>

<p align="center"><sub>All release screenshots were captured from the debug-only deterministic harness on an Android 16 emulator. They contain demo data, not account credentials.</sub></p>

## Live monitoring and large screens

<p align="center">
  <img src="docs/images/dashboard-large-screen.png" width="760" alt="Two-pane dashboard with a navigation rail on a large screen" />
</p>

<p align="center">
  <img src="docs/images/releases/v0.9.0/live-update.png" width="320" alt="Android 16 monitoring notification with segmented quota progress" />
  &nbsp;&nbsp;
  <img src="docs/images/account-setup.png" width="320" alt="Secure provider account setup" />
</p>

## Download

Signed APKs are available on this fork's [Releases](https://github.com/lingmulongtai/CodexBar-android/releases/latest) page.

### Install the release APK

1. Download [**app-release.apk**](https://github.com/lingmulongtai/CodexBar-android/releases/latest/download/app-release.apk) from the latest GitHub Release. Do not install a source archive or a debug APK from an untrusted location.
2. For an integrity check, download `SHA256SUMS` from the same Release and compare the APK hash on your computer:

   ```powershell
   # Windows PowerShell
   (Get-FileHash .\app-release.apk -Algorithm SHA256).Hash
   ```

   ```bash
   # macOS or Linux
   sha256sum app-release.apk
   ```

3. Transfer the APK to the Android device if it was downloaded elsewhere, then open it from the browser or file manager.
4. If Android blocks the installer, allow **Install unknown apps** only for the browser or file manager currently opening the APK. The exact Settings path varies by device manufacturer.
5. Choose **Install**. For an update, install over the existing app so encrypted settings and provider connections remain in place.
6. After installation, turn off the temporary **Install unknown apps** permission again.

If Android reports a signature conflict, first confirm that both APKs came from this repository's Releases. Uninstalling the existing app is a last resort because it deletes locally encrypted credentials, settings, and cached usage data.

No hosted CodexBar backend is used. Provider tokens stay on-device. The optional Claude, Gemini, and Codex companions are local-network processes on your own computer: the Claude and Gemini companions drive only the official CLI views and never read or serve provider tokens, while the Codex companion serves only bounded aggregate token/model metadata and never prompts, responses, file paths, working directories, session IDs, or account tokens.

## Security & Backup

Provider credentials are stored in `codexbar_secure_prefs`, an on-device DataStore whose values are encrypted with Android Keystore-backed AES-GCM keys. That DataStore is explicitly excluded from Android cloud backup and device-to-device transfer. After restoring or moving to a new device, providers must be linked again instead of reusing undecryptable credential ciphertext from the old device.

## Architecture & Verification

- [Provider account-linking architecture](docs/provider-auth-architecture.md)
- [Android widget feature parity](docs/widget-feature-parity.md)
- [Verification matrix](docs/verification-matrix.md)

## Setup

For normal use, install the signed APK from the latest release and open **Connections** to link providers or enter fallback credentials.

For local development:

1. Install [OpenJDK 17](https://formulae.brew.sh/formula/openjdk@17) (or any JDK 17+)
2. Clone and open the project in Android Studio
3. Build and install the debug APK
4. Open the app and go to **Connections** to link providers or enter fallback credentials

## Connecting Accounts

### Device-code sign-in: exact flow

Codex and GitHub Copilot use a device-code flow. The app intentionally shows the code before opening a browser so it can be copied safely:

1. Open **Connections**, expand the provider, and tap **Connect account**.
2. Wait for the one-time code card. The browser does not open automatically.
3. Tap **Copy paste-ready code**. The copied value removes spaces and separators so the entire code can be pasted into the first field, including pages that display split code boxes.
4. Tap **Open sign-in page** and verify the browser address before entering the code.
5. Paste the complete code, sign in to the intended account, and approve the connection.
6. When the website reports success, return to the same Connections screen before the displayed expiry time. Do not force-close the app.
7. Wait for the app to validate the returned credential and show the connected state. Browser success alone does not mean the on-device validation and encrypted save have finished.
8. If the code expires or the app reports a failure, tap **Connect account** again and use the newly issued code. Do not reuse an old code.

| Provider | Expected sign-in host | Before starting |
| --- | --- | --- |
| Codex | `auth.openai.com` | Use the ChatGPT account whose Codex usage you want to monitor. |
| GitHub Copilot | `github.com` | Use the GitHub account whose Copilot usage you want to monitor. |

Treat a sign-in code as a short-lived credential. Enter it only on the page opened by the app, and never include codes or tokens in screenshots, logs, notes, or GitHub issues. The app never asks for the provider password; password and multi-factor authentication remain in the provider's browser page.

Troubleshooting:

- **No code card appears:** confirm the app is current, retry on a working network, and record only the app version and non-secret error text. Never attach the response body if it contains a device code.
- **The website says complete but the app is still waiting:** return to the app, keep the network connected, and allow several seconds for polling and credential validation. If the displayed expiry passes, request a new code.
- **A DNS error appears:** check Private DNS, VPN, ad blockers, and network access. Codex and GitHub Copilot sign-in retry transient DNS failures until the current code expires.
- **Sign-in succeeds but quota validation fails:** confirm that the selected account has access to the provider product and usage data being monitored.

### Claude (Anthropic)

Claude does not expose a supported third-party Android device-code flow for this plan-usage use case. The recommended connection therefore keeps sign-in inside the official Claude Code CLI:

1. Install Claude Code using [Anthropic's current setup instructions](https://code.claude.com/docs/en/setup), complete browser sign-in, then finish the first-run and folder-trust prompts from `~/.codexbar/claude-workspace` as described in the [companion setup guide](companion/claude/README.md).
2. Enter `/usage` and confirm that **Current session** appears.
3. Download `CodexBar-Claude-Companion-v0.9.4.zip` from the same Release as the APK and extract it.
4. On Windows, run `start-windows.cmd`. On macOS or Linux, run `chmod +x start-macos-linux.sh` once and then `./start-macos-linux.sh`.
5. Keep the phone and computer on the same trusted Wi-Fi, or use the [Tailscale setup](companion/claude/README.md#connect-across-networks-with-tailscale) for different networks. If the computer firewall prompts, permit Node.js on private networks only. In Claude under **Connections**, tap **Scan QR securely in CodexBar** and scan the displayed QR inside the app. Do not use the system camera. If Google Play services cannot open the scanner, paste the `CBCLAUDE1...` pairing code, then tap **Pair & verify Claude companion**.

The companion keeps one tools-disabled Claude Code terminal alive, requests `/usage` without creating a new session every refresh, waits for loading to finish and the complete view to stabilize, then discards the raw rendering. It encrypts only validated quota labels, percentages, reset times, a known plan label, and freshness for Android. Anthropic access/refresh tokens, setup tokens, prompts, responses, files, email addresses, and session text never leave the computer.

Do **not** paste the result of `claude setup-token`. [Anthropic documents `setup-token` for long-lived inference automation](https://code.claude.com/docs/en/authentication); it carries inference scope and cannot provide the plan-usage view required here. Direct Claude token entry and refresh are disabled. An upgrade securely removes legacy Claude access/refresh records and asks you to pair the companion instead.

### Codex (OpenAI / ChatGPT)

Follow the device-code steps above. The app opens `https://auth.openai.com/codex/device` only after you press **Open sign-in page**, polls for completion, and saves the returned tokens only after validation.

Manual fallback, if needed:

```bash
# Access token
cat ~/.codex/auth.json | python3 -c "import sys,json; print(json.loads(sys.stdin.read())['tokens']['access_token'])"

# Refresh token
cat ~/.codex/auth.json | python3 -c "import sys,json; print(json.loads(sys.stdin.read())['tokens']['refresh_token'])"
```

Do not extract bearer tokens from browser DevTools unless you are debugging locally and understand the exposure risk.

#### Optional private Codex telemetry companion

OpenAI's subscription quota response does not contain local Codex CLI context-window or token-count history. To add those insights without uploading session content, install `CodexBar-Codex-Telemetry-Companion-v0.9.4.zip` from the same GitHub Release as the app:

1. Install Node.js 20 or newer on the computer where Codex CLI or Codex desktop stores `~/.codex/sessions`.
2. Extract the companion archive. On Windows, run `start-windows.cmd`; on macOS or Linux, run `./start-macos-linux.sh`.
3. Keep the phone and computer on the same trusted Wi-Fi, scan the displayed QR code, and review the hidden pairing value in the Codex card under **Connections**.
4. Tap **Pair & verify telemetry companion**. Keep the companion running whenever local telemetry should refresh.

The scanner is bounded to recent session files and reads only `token_count` plus model metadata. Android receives current context usage, aggregate token categories, daily totals, and model totals. Requests use HMAC-SHA256 authentication with replay and rate-limit defenses; snapshots use AES-256-GCM; the server accepts only a numeric private address. Never expose its port through router forwarding, a public IP, or a public tunnel. Codex subscription quota, reset credits, and model-limit windows continue to come directly from OpenAI even when this companion is off.

### Antigravity

Download `CodexBar-Antigravity-Companion-v0.9.4.zip` from the same Release as the APK. The Windows [Antigravity companion](companion/antigravity/README.md) reads model quotas and reset times from the running, signed-in Antigravity IDE. In **Connections → Antigravity**, scan its private QR with the in-app scanner. This account usage is separate from Gemini CLI and Claude subscriptions.

Keep Antigravity open and the PC awake. The optional tray resident starts at sign-in and recovers after a crash. For different networks, connect the phone and PC to Tailscale and pair using the PC's `100.x` address on port `43824`. Google credentials, conversations and files stay on the PC. Only explicitly reported model quotas are shown; weekly limits and credit balances are not inferred. These internal IDE endpoints may change after an Antigravity update.

### Gemini (Google)

Direct Gemini OAuth inside the Android app remains disabled. CodexBar does not copy Gemini CLI credentials, embed a Google client secret, or call the internal `cloudcode-pa` service. Instead, the v0.9.4 companion drives the official Gemini CLI's documented `/stats` view and sends only a sanitized quota snapshot over your trusted local network.

#### Install and pair the private companion

1. Install Node.js 20 or newer and the current official Gemini CLI on a computer:

```bash
npm install -g @google/gemini-cli@latest
gemini
```

2. Complete Google's sign-in in that official CLI, then exit it.
3. Download `CodexBar-Gemini-Companion-v0.9.4.zip` from this repository's Release and extract it. Do not run a companion archive from another source.
4. On Windows, double-click `start-windows.cmd`. On macOS or Linux, run `./start-macos-linux.sh`. The first launch installs only the versions pinned in `package-lock.json`.
5. Keep the phone and computer on the same trusted Wi-Fi. If the computer firewall prompts, permit private networks only.
6. Scan the displayed QR code with the phone and choose CodexBar, or paste the complete `codexbar://gemini-pair?...` value into the Gemini card.
7. Review the screen, then tap **Pair & verify companion**. Scanning or pasting never connects or saves the key automatically.
8. Keep the companion running while Gemini monitoring is needed. If the computer's local IP address changes, start it again and replace the pairing.

The pairing key is a local-device password: do not post it in screenshots, logs, or issues. Requests are authenticated with HMAC-SHA256, replay-protected, rate-limited, and responses are encrypted with AES-256-GCM. Android accepts only numeric local/private addresses and encrypts the pairing details with Android Keystore. The snapshot contains model labels, used percentages, reset times, tier, CLI version label, and freshness—never Google access/refresh tokens, client secrets, prompts, files, email addresses, or CLI session content.

Troubleshooting:

- **Pairing verification fails:** confirm the companion still says it is ready, both devices use the same Wi-Fi, private-network firewall access is allowed, and both clocks use automatic time.
- **The companion cannot collect quota:** open the official `gemini` command normally, finish sign-in or any first-run prompts, verify `/stats` shows model usage, exit, and restart the companion.
- **Android stops refreshing Gemini:** the computer may be asleep, its IP may have changed, or the Wi-Fi may isolate clients. Restart and re-pair; do not expose the port through router forwarding or a public tunnel.

This design follows the official Gemini CLI's documented [usage and quota command](https://github.com/google-gemini/gemini-cli/blob/main/docs/resources/quota-and-pricing.md) without using its OAuth credential to access Google's backend from a third-party client. Direct Android OAuth remains fail-closed because Google's [limited-input device flow](https://developers.google.com/identity/protocols/oauth2/limited-input-device) is not a general Android authorization mechanism and does not make Gemini CLI's internal quota service a supported API.

### GitHub Copilot

Follow the device-code steps above. The app opens `https://github.com/login/device` only after you press **Open sign-in page**, then fetches Copilot quota data after GitHub authorization and on-device credential validation complete.

### Cursor

Cursor does not currently expose a supported mobile device-code flow. The original macOS CodexBar reads a signed-in desktop browser session or Cursor.app state; Android cannot safely access either store. This port therefore starts with the original project's manual fallback: copy the `Cookie` request header from `https://cursor.com/api/usage-summary` in a signed-in desktop browser, paste it into the Cursor card, and select **Validate & connect**.

The app rejects malformed or multi-line values before network access, sends the cookie only to `https://cursor.com`, refuses HTTP and HTTPS redirects, and encrypts it with Android Keystore only after the usage endpoint accepts it. Never paste this value into an issue or chat. Signing out of Cursor invalidates the session.

Cursor cards show the billing-cycle Total, Auto, and API percentages, legacy request-plan usage when available, the cycle reset, plan name, and capped on-demand USD usage. Team and Enterprise personal/shared-cap fallbacks follow the original CodexBar mapping.

### z.ai

Create or copy an API token from the [z.ai Coding Plan dashboard](https://z.ai/manage-apikey/coding-plan/personal/my-plan), paste it into the z.ai card, and select **Validate & connect**. The app reads the personal quota response from the fixed Global endpoint at `https://api.z.ai/api/monitor/usage/quota/limit` and maps the Tokens, MCP/time, and shorter rolling token windows using the original CodexBar rules.

The token is sent only as a bearer credential to `api.z.ai`, never across redirects, never written to HTTP logs, and encrypted with Android Keystore after validation. BigModel China and team mode need additional region, organization, and project selectors; they intentionally remain unavailable rather than silently sending a Global token to a configurable host.

### ZenMux

Create a **Management API key** at `https://zenmux.ai/platform/management`, then paste it into the ZenMux card in Connections and select **Validate & connect**. A normal ZenMux inference API key is not accepted by the management quota endpoints.

The app sends the key only as a bearer credential to `https://zenmux.ai/api/v1/management/subscription/detail`. It displays the rolling 5-hour and 7-day flow quotas from the documented response. The key is encrypted with Android Keystore after validation; prompts, inference requests, and request logs are never requested.

### Kimi

Create a Kimi Code API key in the Kimi Code console, paste it into the Kimi card in Connections, and select **Validate & connect**. The app calls only `https://api.kimi.com/coding/v1/usages` and displays the short rolling window (normally 5 hours) together with the 7-day coding quota.

The API key is sent only as a bearer credential to the fixed HTTPS host, never follows redirects, never enters HTTP logs, and is encrypted with Android Keystore only after validation. The app does not request prompts, inference responses, or browser session cookies.

### ElevenLabs

Create a restricted ElevenLabs API key with permission to read the user subscription, paste it into the ElevenLabs card, and select **Validate & connect**. The app calls `https://api.elevenlabs.io/v1/user/subscription` and displays character usage, voice slots, professional voice slots, the subscription tier, and the next character reset when provided.

Use the narrowest key permissions available. The key is sent only in the `xi-api-key` header to the fixed ElevenLabs HTTPS host, redirects and HTTP logging are disabled, and the key is encrypted with Android Keystore after validation. Generated audio, voices, projects, and request content are not fetched.

### OpenRouter

Create a dedicated OpenRouter API key, preferably with a daily, weekly, or monthly credit limit, paste it into the OpenRouter card, and select **Validate & connect**. The app calls `https://openrouter.ai/api/v1/key` and maps the configured key limit, remaining credits, usage, reset cadence, and free-tier state. A management key is not required.

If no key limit is configured, the account remains connected and the dashboard/widget states that there is no spending cap while showing the reported spend. A configured limit adds an exact USD credit summary and a progress bar. The key is sent only as a bearer credential to the fixed OpenRouter HTTPS host, redirects and HTTP logging are disabled, and it is encrypted with Android Keystore after validation.

### Synthetic

Create a dedicated Synthetic API key, paste it into the Synthetic card, and select **Validate & connect**. The app calls only `https://api.synthetic.new/v2/quotas`, maps subscription requests against the documented limit, and shows the renewal timestamp. Synthetic documents that quota checks do not consume subscription limits.

The key is sent only as a bearer credential to the fixed Synthetic HTTPS host, redirects and HTTP logging are disabled, and it is encrypted with Android Keystore after validation. Prompts, model responses, and request history are not fetched.

### Chutes

Create a dedicated scoped Chutes API key, paste it into the Chutes card, and select **Validate & connect**. The app first calls `https://api.chutes.ai/users/me/subscription_usage`, whose official API description returns monthly and 4-hour usage versus caps, and falls back to the documented `/users/me/quotas` response when necessary.

The parser accepts the currently documented flexible quota payload without trusting arbitrary endpoints. The key is sent only as a bearer credential to the fixed Chutes HTTPS host, redirects and HTTP logging are disabled, and it is encrypted with Android Keystore after validation. Chute code, prompts, invocations, and request history are not fetched.

### DeepSeek

Create a dedicated DeepSeek API key, paste it into the DeepSeek card, and select **Validate & connect**. The app calls only `https://api.deepseek.com/user/balance` and displays whether API calls are currently available together with each reported USD or CNY balance. DeepSeek does not expose a bounded quota in this endpoint, so the app intentionally shows an exact status instead of inventing a utilization bar.

The key is sent only as a bearer credential to the fixed DeepSeek HTTPS host, redirects and HTTP logging are disabled, and it is encrypted with Android Keystore after validation. Prompts, chat history, model responses, and usage records are not fetched.

### Venice

Create a dedicated Venice API key with the narrowest permission that can read billing balance, paste it into the Venice card, and select **Validate & connect**. The app calls only `https://api.venice.ai/api/v1/billing/balance`, shows whether the account can make API calls, and displays USD/DIEM balances. When a DIEM epoch allocation is present, it also derives the consumed fraction for the progress bar from the documented remaining balance and allocation.

The key is sent only as a bearer credential to the fixed Venice HTTPS host, redirects and HTTP logging are disabled, and it is encrypted with Android Keystore after validation. Prompts, model responses, billing history, and inference request details are not fetched.

### Moonshot API (International)

Create an API key on the international Moonshot API platform, paste it into the Moonshot API card, and select **Validate & connect**. This is deliberately separate from the Kimi Code card: Kimi Code subscription keys and Moonshot API billing keys use different account surfaces. The app calls only `https://api.moonshot.ai/v1/users/me/balance` and displays available, cash, and voucher balances without inventing a rolling quota bar.

The key is sent only as a bearer credential to the fixed international Moonshot HTTPS host, redirects and HTTP logging are disabled, and it is encrypted with Android Keystore after validation. The app does not automatically forward the key to the China-mainland host. Prompts, model responses, and request history are not fetched.

### Cline

Create a dedicated API key in Cline, paste it into the Cline card, and select **Validate & connect**. The app calls only `https://api.cline.bot/api/v1/users/me/plan/usage-limits` and displays the provider-reported 5-hour, weekly, and monthly plan windows with their reset times.

The key is sent only as a bearer credential to the fixed Cline HTTPS host, redirects and HTTP logging are disabled, and it is encrypted with Android Keystore after validation. Prompts, model responses, and request history are not requested.

### IBM Bob

Create an API key in the IBM Bob portal, paste it into the IBM Bob card, and select **Validate & connect**. The app reads the profile and aggregates the monthly Bobcoin usage and budget across visible teams, preserving the earliest reported refresh time.

API keys use IBM Bob's `Apikey` scheme, while structurally valid JWT credentials use `Bearer`. Regional endpoints returned by the profile are accepted only when they are HTTPS hosts strictly under `bob.ibm.com`; URL user info, ports, paths, queries, fragments, and lookalike domains are rejected. The encrypted credential is never logged, and prompts or coding history are not requested.

### Devin

Create a `cog_` personal access token or service-user key with **ViewOrgConsumption**, then enter it and the **organization ID** in Connections → Devin. Both are available through Devin Settings → Devin API. The app makes read-only requests to the [official organization consumption endpoint](https://docs.devin.ai/api-reference/v3/consumption/organizations-consumption-daily), available across plans.

Devin shows **organization ACUs consumed over the last 30 days**, across Devin products. Daily boundaries use 08:00 UTC. This API does not expose a subscription limit or remaining credits, so CodexBar does not invent a percentage or reset countdown. A 403 indicates missing organization access or permission; 401 means the key needs replacing. No session creation, prompts, or inference requests are made.

### Fireworks AI

Create a dedicated Fireworks API key and copy the account slug from the account URL. Enter both in the Fireworks AI card and select **Validate & connect**. The app calls only `https://api.fireworks.ai/v1/accounts/{accountSlug}/billing/summary` and totals rated spend for the last 30 days in the first reported currency.

The account slug is restricted to a short ASCII identifier, the API key is sent only as a bearer credential to the fixed Fireworks HTTPS host, redirects and HTTP logging are disabled, and both values are encrypted with Android Keystore after validation. Prompts, model responses, and inference history are not requested.

### OpenCode Go

Create an API key for OpenCode Go and paste it into the OpenCode Go card, then select **Validate & connect**. The app calls only `https://opencode.ai/zen/go/v1/usage` and displays the provider-reported rolling 5-hour, weekly, and monthly Go plan windows with their reset times.

The key is sent only as a bearer credential to the fixed `opencode.ai` host, redirects and HTTP logging are disabled, and it is encrypted with Android Keystore after validation. Prompts, model responses, and request history are not requested.

## Build

```bash
./gradlew assembleDebug
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`

Recommended local checks before opening a PR:

```bash
./gradlew --dependency-verification=strict testDebugUnitTest lint assembleDebug
```

## Dependency Verification

Gradle Wrapper distribution checksums and dependency verification metadata are committed. When intentionally changing Gradle, plugins, or libraries, regenerate verification metadata with:

```bash
./gradlew --write-verification-metadata sha256 lint testDebugUnitTest assembleDebug
```

Review and commit `gradle/verification-metadata.xml` together with the dependency change. CI runs with `--dependency-verification=strict` and validates the Gradle Wrapper before compilation.

## Release Builds

Public releases are built only from `v*` Git tags by the protected release workflow. Signing material must stay in GitHub environment secrets:

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

The release workflow publishes signed APK/AAB artifacts, versioned Gemini and Codex companion ZIPs, Android and companion CycloneDX SBOMs, emulator screenshots, `SHA256SUMS`, a build provenance JSON file, and GitHub artifact attestations. Debug APKs from CI are short-lived test artifacts only.

## Tech Stack

- Kotlin 2.3.21, Jetpack Compose, Material 3 Expressive
- Hilt (DI), Retrofit2 + OkHttp (networking)
- WorkManager (background sync), DataStore + Android Keystore-backed encryption
- Glance AppWidget, Quick Settings tile, Android notification/live monitoring APIs
- Node.js companions for the official Gemini CLI and privacy-bounded Codex session telemetry, with authenticated encrypted local snapshots
- KSP, kotlinx.serialization

## Acknowledgments

Based on [CodexBar](https://github.com/steipete/CodexBar) by Peter Steinberger.

## License

[MIT](LICENSE)
