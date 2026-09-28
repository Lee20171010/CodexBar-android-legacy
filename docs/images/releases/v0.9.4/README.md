# v0.9.4 release captures

Captured on 2026-09-28 from the v0.9.4 debug APK (version code 29), on a disposable Android API 36.1 emulator at 540 × 1200 pixels / 210 dpi, font scale 1.0. These are fresh Android captures with synthetic quota data; no account data or pairing credentials are included.

| Capture | Verification |
| --- | --- |
| `usage-light.png`, `usage-dark.png` | Four providers fit on one Usage screen; Antigravity has its official artwork and distinct High/Low labels |
| `antigravity-details.png` | Full model label, remaining quota, reset and synthetic history in the details sheet |
| `widget-dual.png`, `widget-segments.png` | Templates 9 and 6 at exactly 347 × 69dp with Codex, Antigravity and Claude |
| `widget-dual_segments.png`, `widget-segments_dual.png` | Templates 11 and 12 at 347 × 69dp with separate High/Low model percentages and reset labels |

All four native AppWidgetHost layouts reported fully visible provider names, model variants, percentages and resets. The release source passed 349 Android unit tests, lint and debug assembly. Companion verification includes Claude's 25 tests, native Windows PTY smoke and dependency audit, plus Antigravity's 9 tests, dependency audit and Windows tray initialization. Both production dependency audits found zero vulnerabilities. Installer checks covered missing dependencies on PowerShell 5 and preserving a saved port while respecting an explicit override.

Debug APK SHA-256: `91317850f003254073761073dd9a05f9f0e6dd62834c6b0334b40216a58dd358`. This identifies the capture input, not the signed release APK.

## Reproduction and boundaries

Use a disposable emulator: the fixtures replace its cached widget data and settings. Start `ScreenshotActivity` with `personal=true`, `antigravity=true`, and the selected `dark_theme` value. Start `WidgetHostActivity` with `seed_demo=true`, `three_services=true`, `antigravity=true`, width 347, height 69 and the selected template ID. These activities are excluded from the release build.

Separate live verification on the Windows PC confirmed encrypted Tailscale snapshots containing Claude's 5-hour and weekly windows, and eight Antigravity model windows. None of those live values were used in these screenshots. Physical-phone pairing and Niagara Launcher acceptance for Antigravity remain separate from emulator evidence.

PR CI checks all Android tests/lint/build and the four companions; Claude runs on Windows, macOS and Linux. The tag workflow builds and smoke-tests signed artifacts, checks the pinned certificate, verifies the packaged Claude companion, and publishes checksums, SBOMs and provenance.
