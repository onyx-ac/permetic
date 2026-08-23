# 01 — Permetic WebView host and JS runtime

Status: **draft, awaiting review** · Owner: Onyx
Modules: `permetic`, `permetic-push`, `permetic-billing`, `permetic-web`

## Goal

Run an existing web app inside an Android WebView and grant it scoped, declared
access to native features it cannot reach from JS. The web app's own code runs
unchanged.

## Non-goals

- Owning the data model. Storage is a separate optional capability; see spec 02.
- Replacing the app's router, service worker, or build.
- iOS. The contract allows a host later; do not build one.
- A generic RPC bridge. The capability set is closed and versioned.

## Three modes, one host

| Mode | Web app storage | Artifacts | `available('storage')` |
| --- | --- | --- | --- |
| A | none (API + Redux) | `permetic-core` (+ push/billing) | false |
| B | `@docstack/client`, browser adapter | `permetic-core` (+ push/billing) | false |
| C | `@docstack/client`, native adapter | + `docstack-store`, `docstack-permetic` | true |

Mode A is the baseline: nothing about Permetic assumes a database exists. Mode B
costs nothing extra — DocStack runs as it does in any browser, with
`permetic.auth` supplying the Drive token. Mode C moves documents into the native
store so they survive WebView data clearing and can be synced with no WebView
attached.

Permetic is the orchestrator in all three. It owns the WebView, so it owns
provisioning: DocStack registers on the same builder as every other capability.

```kotlin
val permetic = PermeticController.Builder(activity)
    .allowOrigin("https://appassets.androidplatform.net")
    .assets("web")                                   // src/main/assets/web
    .capability(PlayAuthCapability(activity, clientId = BuildConfig.OAUTH_CLIENT))
    .capability(FcmPushCapability(activity))         // omit -> available('push') false
    .capability(PlayBillingCapability(activity))
    .capability(WorkManagerBackgroundCapability(context))
    .capability(DocStackStorageCapability(context))  // mode C only
    .build()

permetic.attach(webView)
```

`available()` is derived from what was passed to `.capability(...)`. An
unregistered capability rejects with `UNAVAILABLE` — never a stub that silently
no-ops.

## Architecture

```
Web app (unchanged) ──> @docstack/client ──> PouchDB ──> adapter (mode C)
       │                                                      │
       │  window.permetic  (permetic-web runtime)             │
       ▼                                                      ▼
  WebMessageListener ──── BridgeRequest / BridgeResponse ─────┐
                                                              │
  PermeticController ── CapabilityRegistry ────────────────── ┤
       ├── SystemCapability      (permetic-core)              │
       ├── AuthCapability        (permetic-core)              │
       ├── PushCapability        (permetic-push, optional)    │
       ├── BillingCapability     (permetic-billing, optional) │
       ├── BackgroundCapability  (permetic-core) ──> WorkManager
       └── StorageCapability     (docstack-permetic, optional) ──> docstack-store
```

## Asset serving, offline, and OTA

The app is served locally by `WebViewAssetLoader` over
`https://appassets.androidplatform.net`. That origin never touches the network —
every request is intercepted and answered from disk. The origin exists so the page
gets a real security context, not because anything is fetched. **Offline is
inherent**: there is no cache to warm and no offline mode to implement. Because it
is a secure context, service workers, `crypto.subtle` and full storage all work,
none of which they would under `file://`.

`allowFileAccess = false` does not conflict with any of this. That flag governs
whether the WebView will load `file://` URLs; the asset loader reads files through
app-internal APIs and serves them under the https origin, so disk access is
unaffected. It stays off because `file://` has no meaningful origin — the only
`addWebMessageListener` rule that matches it is `*`, which discards the allowlist
entirely — and because `file://` origin quirks are a long-standing local-file
exfiltration surface.

### OTA updates

`WebViewAssetLoader` takes multiple path handlers. `AssetsPathHandler` serves the
bundle shipped in the APK; `InternalStoragePathHandler` serves a directory in
app-internal storage. Downloaded web assets are therefore served through the same
origin with the same privileges, and no `file://` is involved. A resolver decides
which directory is live.

Requirements:

- **Signatures are mandatory.** OTA content runs at the same privilege as bundled
  content; there is no boundary between them. An unverified download is a remote
  code execution channel into the app. Verify before the staging directory is
  eligible to become live.
- **Atomic swap with rollback.** Download to staging, verify, flip a pointer, load
  on next launch. Keep the last known good bundle and revert if boot fails.
- **The `contractVersion` handshake becomes load-bearing.** OTA'd JS can be newer
  than the installed native capabilities. That mismatch must fail loudly at
  startup, not surface later as confusing `UNAVAILABLE` results.
- **Play policy is satisfied.** JavaScript interpreted in a WebView is not the
  downloaded executable code the Device and Network Abuse policy targets. Recorded
  here so it is not relitigated.
- **Bundled fallback always exists.** The APK ships a complete working bundle, so a
  fresh install with no network still runs.

This is the same problem Zipline's module loading solves for the headless bundle
(spec 04, D-2). Decide both with one mechanism if possible.

## Non-negotiables

- **Transport is `WebViewCompat.addWebMessageListener`**, not `@JavascriptInterface`.
  Origin-scoped, async, and pairs with `addDocumentStartJavaScript` so the global
  exists before app code runs. `@JavascriptInterface` is a fallback only, behind
  `WebViewFeature.isFeatureSupported`, exposing exactly one method.
- **Content is served by `WebViewAssetLoader`** over
  `https://appassets.androidplatform.net`. Never `file://` — the app needs a secure
  context for crypto, IndexedDB and service workers, and the bridge needs a real
  origin to scope to.
- **Origin allowlist is explicit.** No wildcards. Defaults to the asset-loader
  origin only.
- **No `file://`, ever** — including for OTA'd assets. Everything is served through
  the asset loader's https origin.
- **Settings**: `allowFileAccess = false`, `allowContentAccess = false`,
  `allowFileAccessFromFileURLs = false`, `allowUniversalAccessFromFileURLs = false`,
  `mixedContentMode = MIXED_CONTENT_NEVER_ALLOW`, `setSafeBrowsingEnabled(true)`.
  Debugging enabled only under `BuildConfig.DEBUG`.

## Lifecycle rules

- The controller holds the `Activity` through a nullable weak binding set in
  `onCreate` and cleared in `onDestroy`. Capabilities needing an Activity return
  `UNAVAILABLE` when it is absent rather than throwing.
- In-flight requests are cancelled when the WebView is destroyed and resolve as
  `CANCELLED`. They are never silently dropped.
- Subscriptions survive configuration changes: keyed by subscription id and
  re-attached, not recreated.

## Tasks

Do these in order, one per review cycle.

1. **Contract freeze.** Review `permetic-web/src/index.d.ts`. Generate the Kotlin
   dispatcher and `kotlinx.serialization` models from it. Add the CI check that
   fails when one side changes without the other.

   **Done** (2026-08-23). Hand-written Kotlin mirrors rather than generated ones —
   the contract is small and slow-changing, so a real codegen tool wasn't judged
   worth building yet. Drift is still caught two ways: `CapabilityName` is a Kotlin
   enum so the eventual `Dispatcher`'s `when (capability)` is non-exhaustive by
   construction (a compile error on drift, not a runtime surprise), and a shared
   `packages/permetic-web/contract/manifest.json` is checked independently by a JVM
   test (`ContractParityTest`) and a TS test (`contract-parity.test.ts`, via
   `ts-morph` parsing `index.d.ts` directly) — verified by temporarily renaming a
   contract method and confirming the TS test caught it. `Dispatcher.kt` itself is
   not built yet: it depends on the envelope (task 2) and the registry (task 5, not
   started), so building it now would mean redoing it once the registry exists.
2. **Envelope codec.** JVM-only, no Android types. Encode/decode, correlation ids,
   cancellation, subscription id allocation. Round-trip and fuzz tests.

   **Done** (2026-08-23). `BridgeResponse` needed a hand-written `KSerializer` — its
   TS union discriminates on the `ok` boolean with two different field sets, not a
   `type` tag, so kotlinx.serialization's default sealed polymorphism (which adds
   its own discriminator field) doesn't fit. Added a detekt `ForbiddenImport` rule
   scoped to files directly under `transport/` (excluding `transport/android/`)
   banning `android.*`/`androidx.*` imports, so the JVM-only constraint is a lint
   failure, not just a convention — verified with a sabotage-and-revert check.
3. **Transport.** `addWebMessageListener` + `addDocumentStartJavaScript`. Origin
   allowlist enforced on every message. `@JavascriptInterface` fallback behind a
   feature check. Binary side-channel for attachment bodies.

   **Done** (2026-08-23), except the binary side-channel: deferred, since this
   build never registers a `storage` capability (no DocStack integration). Neither
   `WebViewCarrier` nor `JavascriptInterfaceFallback` depend on a concrete
   dispatcher type — both take a plain `suspend (BridgeRequest) -> BridgeResponse`
   function, so the transport stays buildable and testable independently of the
   registry (task 5), matching ADR-0002's "the carrier has no dispatch branch in
   it". Verified against a real device, not just compiled: booted the
   `Medium_Phone_API_36.1` AVD already present on the dev machine (WHPX-accelerated,
   headless) and ran `./gradlew :permetic:connectedDebugAndroidTest` — passes
   end to end against the real `WebMessageListener` callback and the fixture page in
   `androidTest/assets/`, no mocked `permetic` object. (Caught and fixed one real
   bug in the process: `ActivityScenario.launch` needs a manifest-declared launcher
   activity, not the bare framework `Activity` class.)
4. **`permetic-web` runtime.** Builds `window.permetic` from a `Carrier`: promise
   correlation, subscription bookkeeping, version handshake, and
   `createMockPermetic()` for the web app's browser-mode dev server.

   **Done** (2026-08-23), except `storage` (same reason as task 3 — no DocStack
   integration in this build). Two corrections to this task's own framing, worth
   recording: "promise correlation" isn't actually needed inside `buildPermetic` —
   the `Carrier`'s own returned `Promise` already correlates each call; what needs
   bookkeeping is unsolicited `BridgeEvent` routing to `onXxx` subscription
   listeners, so `buildPermetic` takes an explicit `onEvent` registration function
   rather than reading a global. And `index.d.ts` never actually specifies how an
   `onXxx` subscription gets *cancelled* (only storage's `subscribeChanges` is
   called out as a carrier-level special case) — established a convention to fill
   that gap: cancellation reuses the normal envelope with a reserved method name
   `"unsubscribe"` under the same capability. This is internal wire protocol, not
   part of the public contract, but **`Dispatcher` (task 5) needs to honor it** —
   every capability with an `onXxx` method must handle an `"unsubscribe"` request
   carrying `[subscriptionId]` and cancel the corresponding `Flow` collector.
   `system`/`auth` build unconditionally per the non-optional `Permetic` fields;
   `push`/`billing`/`background` are gated on `available(name)`. The barrel entry
   is `main.ts`, not `index.ts` — an `index.ts` alongside the existing `index.d.ts`
   would make `tsc`'s declaration emit collide with the frozen contract file.
5. **`PermeticController` + registry.** Builder, lifecycle binding, Activity weak
   reference, cancellation on destroy, subscription survival across config changes.
6. **`system` and `auth`.** Token caching and a `refresh()` path for Drive 401s.
7. **`permetic-push`.** FCM token, `POST_NOTIFICATIONS` on API 33+, foreground
   message delivery, cold-start tap payload consumed exactly once.
8. **`permetic-billing`.** Play Billing 7, Activity-scoped, purchase / acknowledge
   / consume, purchase-update stream, pending-purchase handling.
9. **`background`.** WorkManager scheduling by job id. The worker is supplied by
   the embedding app.
10. **Asset resolver and OTA.** `AssetsPathHandler` for the shipped bundle,
    `InternalStoragePathHandler` for downloaded ones, a resolver choosing the live
    directory, signature verification, atomic swap, rollback on failed boot, and
    the contract-version check at startup.
11. **Hardening pass.** Settings lockdown, navigation policy (external links to the
    browser), file chooser, runtime permission mapping, back handling, CSP for the
    bundled assets.

Storage is spec 02 and can proceed in parallel from task 3 onward.

## Verification

Each task is done when `./gradlew :permetic:test` and the conformance suite pass,
and the instrumented fixture page in `androidTest/assets/` exercises the real
global end to end — no mocked `permetic` object. Tasks 7–9 additionally need a
manual device run: notification tap, purchase sheet, worker firing while the app
is swiped away.

End-to-end acceptance: the sample app loads, obtains a Drive token through
`permetic.auth`, receives a push, and completes a test purchase. With
`permetic-billing` removed from the build, the same app still runs and
`available('billing')` returns false.

## Open decisions

- **D-1** Auth provider: Credential Manager + `AuthorizationClient`, or AppAuth?
  Play Services is easier but ties you to GMS builds.
- **D-2** `minSdk`. 24 widens reach; 26 removes WorkManager and notification-channel
  branching.
- **D-3** One Gradle repo with `docstack-*`, or two repos sharing the contract as a
  published artifact?
- **D-4** OTA signing: reuse the app signing identity, or a separate content key?
  A separate key lets you rotate without a store release.
- **D-5** Does OTA'd content apply on next launch only, or can it hot-swap on
  resume? Next-launch-only is simpler and avoids a running app changing underneath
  itself mid-session.
