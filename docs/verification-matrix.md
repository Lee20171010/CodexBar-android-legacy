# Verification matrix

This repository now uses layered verification because the app spans credentials, workers, notifications, Glance widgets, and release artifacts.

## Unit and source-level checks

| Area | Coverage |
| --- | --- |
| Credential logging | `NetworkModuleTest` asserts sentinel access/refresh/client-secret values are not emitted by the debug metadata logger and that token clients have no logging interceptor. |
| Backup/data transfer | `BackupRulesTest` verifies DataStore credentials, token refresh state, monitoring session state, and widget display cache are excluded from legacy backup and Android 12+ extraction rules. |
| Secure storage | `SecurePreferencesImplementationTest` verifies DataStore plus Android Keystore value encryption and absence of `EncryptedSharedPreferences`. |
| Claude local pairing | `ClaudeCompanionPairingTest` and `ClaudeCompanionClientTest` cover the non-browsable pairing format, strict private-address parsing, HMAC/AES-GCM exchange, tamper rejection, and explicit verification. Google Code Scanner returns QR data directly to the app without camera permission or a secret-bearing external intent. |
| Claude desktop companion | Node tests cover current-session/weekly parsing, loading/stability behavior, PTY reuse, used/remaining conversion, ANSI sanitization, known-plan allowlisting, domain-separated keys, exact signed requests, and replay rejection. Linux, Windows, and macOS CI each install and spawn the native PTY; Linux also runs `npm audit` against the pinned lockfile. The release workflow extracts the generated ZIP and repeats its install, syntax, and native-PTY smoke checks from the packaged files. |
| Gemini secret removal | `GeminiClientSecretRemovalTest` fails if main source reintroduces Gemini `client_secret` handling. |
| Gemini local pairing | `GeminiCompanionPairingTest`, `GeminiCompanionClientTest`, and `GeminiPairingDeepLinkSourceTest` cover strict private-address parsing, HMAC/AES-GCM exchange, freshness, tamper rejection, and explicit user confirmation. |
| Gemini desktop companion | Node tests cover CLI-output sanitization, persistent identity, private-address binding, authenticated socket exchange, encryption, and nonce replay rejection; CI also runs `npm audit` against the pinned lockfile. |
| Retry-After parsing | `RetryAfterTest` and `RetryInterceptorTest` cover malformed, negative, overflow, date, and capped retry behavior. |
| OpenCode Go usage | `OpenCodeRepositoryImplTest` covers rolling/weekly/monthly window mapping, percent conversion, fixed-endpoint request shape, and auth/rate-limit/service errors. |
| Token refresh races | `TokenRefreshRetryPolicyTest` covers provider/account-scoped retry state, terminal failure behavior, and account-fingerprint changes. |
| Presentation snapshot | `QuotaPresentationMapperTest` covers shared labels, bars, freshness, privacy, and extra usage mapping. |
| Pace/history | `QuotaPaceCalculatorTest` covers sparse samples, reserve, reset windows, and forecast states. |
| Monitoring session | `MonitoringSessionTest` covers explicit start/end and remaining-duration math. |
| Notification synchronization | `DashboardNotificationSyncSourceTest`, `LiveNotificationSourceTest`, and `LiveMonitoringSettingsSourceTest` verify same-snapshot dashboard publication, independent persistent/live toggles, and API 36 promoted-progress construction. |
| Widget startup/recovery | `WidgetRefreshSourceTest` and `WidgetConfigurationUiSourceTest` cover immediate refresh, loading-state recovery, cache fallback, and correct canceled-configuration results. |

## Manual release smoke checklist

Before publishing a non-beta tag:

1. Run strict dependency verification.
2. Run debug unit tests.
3. Run Android lint.
4. Build debug and release APKs.
5. Build release AAB.
6. Generate release SBOM.
7. Run every companion's syntax/tests/audit and generate each release ZIP and SBOM.
8. Confirm no debug APK is attached as the normal release asset.
9. Confirm release notes identify the exact git tag and commit.

## Visual/integration coverage still requiring devices

The repository includes generated/static widget previews and notification code paths, but full screenshot and instrumentation coverage still requires an Android device or emulator matrix:

- API 35 fallback notification and API 36 promoted Live Update eligibility.
- Light/dark/dynamic color.
- Font scale up to 200%.
- RTL layout.
- Phone, tablet, landscape, and launcher widget resize grids.
- TalkBack traversal for dashboard, settings, widget configuration, and monitoring controls.
