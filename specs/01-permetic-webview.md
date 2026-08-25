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
       ├── PipCapability         (permetic-core) ──> Activity PiP; see spec 07
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
- Picture-in-Picture does **not** cross the foreground/background boundary — the
  Activity is paused but stays visible — so `system.onLifecycle()` keeps reporting
  `foreground` throughout. PiP has its own signal; see spec 07.

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

   **Done** (2026-08-23). This is where `Dispatcher.kt` finally landed — deferred
   since task 1 because it needed a registry to dispatch into. `CapabilityRegistry`
   keys on each capability interface's own default `name` property (the five task-1
   interfaces were retrofitted to extend a new `PermeticCapability` marker for
   this). `Dispatcher`'s `when (capability)` has no `else` branch, so the
   contract-freeze promise ("adding a capability without a dispatch line is a
   compile error") is now real, not just a plan. One design decision beyond what
   was planned: `WebViewCarrier`'s "await a reply, then send it" coroutines and the
   actual dispatch work run on two deliberately separate coroutine scopes —
   cancelling one scope on teardown to stop wasted work would, if it were the same
   scope, also kill the coroutine responsible for sending back the `CANCELLED`
   reply. `PendingRequestTable` (built in task 2, unused until now) mediates
   between them. `WebViewCarrier` also gained `pushEvent()`, reusing the
   last-seen `JavaScriptReplyProxy` to deliver unsolicited `BridgeEvent`s — Android's
   API supports this outside the `onPostMessage` call that produced the proxy.
   `PermeticWebViewClient` only wraps `AssetsPathHandler` at the assets root
   (verified against the real androidx.webkit 1.12.1 class, not assumed — it has no
   subfolder constructor parameter, so `.assets("web")` is kept on the `Builder` to
   match this doc's own example shape but doesn't yet change where files are read
   from). `BootstrapScript` (task 3) isn't wired into `attach()` yet — there's no
   real `permetic-web` bundle in this repo's assets to inject. Verified on a real
   device: booted the `Medium_Phone_API_36.1` emulator and ran a new
   `PermeticControllerInstrumentedTest` (`attach()` → real `Dispatcher` → a
   registered fake capability → reply, decoded on the JS side) alongside task 3's
   instrumented test — both pass.
6. **`system` and `auth`.** Token caching and a `refresh()` path for Drive 401s.

   **Done** (2026-08-24), with this task's own framing corrected in one place:
   "Drive 401s" predates the Mode A app this round targets, which talks to
   Firestore and never touches Drive. The mechanism is the same and is not
   Drive-specific — a downstream API rejects a token, the caller calls `refresh()` —
   so only the wording was stale. **Open decision D-1 is resolved**: Google via
   Credential Manager, in a new optional `:permetic-auth-google` module so
   `permetic-core` stays free of Play Services (GMS enters only through Credential
   Manager's pre-API-34 backend). `GoogleTokenProvider` returns **Google ID tokens**,
   which is what a Firebase-Auth-fronted Firestore app needs; a non-empty `scopes`
   argument is rejected with `UNAVAILABLE` rather than quietly answered with an
   unscoped token, since scoped OAuth needs `AuthorizationClient` plus an
   activity-result consent flow and this build has no caller for it. Token expiry is
   a conservative fixed lifetime rather than the JWT's own `exp` claim: decoding it
   would mean trusting a payload whose signature we never verify, to save a round
   trip `refresh()` already handles, and `java.util.Base64` needs API 26 against
   `minSdk 24`.

   `auth` is split so that caching is provider-independent and unit-testable with no
   emulator and no Google account: `CachingAuthCapability` (core) owns the cache,
   single-flight and account-change bookkeeping; `TokenProvider` (core) owns identity.
   Two behaviours are worth recording because they are not obvious from the task text.
   **Concurrent callers share one fetch** — a cold-starting web app fires several
   requests at once, and without collapsing them that is N provider round trips and
   potentially N account pickers. **`refresh()` is not `getToken()` with the cache
   cleared**: the caller is telling us the token it holds was rejected, so a refresh
   must not return one fetched before that point, which means ignoring the cache *and*
   declining to join a `getToken()` fetch already in flight (it does collapse with
   other concurrent refreshes, the common case when several parallel requests all 401
   together). A sign-out during an in-flight fetch is handled with an epoch counter so
   the returning fetch cannot repopulate the cache it just cleared.

   Two gaps in task 5's `Dispatcher` surfaced here, since this is the first round with
   real capabilities behind it. There was no public way for a capability to report a
   contract error code (`DispatchException` is `internal`) — now `CapabilityException`.
   And an unexpected exception escaped `dispatch()` entirely, which would leave
   `PermeticController.trackedDispatch` awaiting a result that never arrives and hang
   the web app's promise **forever**; `dispatch()` now maps anything unrecognised to an
   opaque `INTERNAL` carrying only the exception's type name, never its message
   (root `CLAUDE.md`: never raw exception strings), and re-throws
   `CancellationException` ahead of that so teardown still works.

   `openUrl` validates the scheme before building the implicit intent — a
   web-app-supplied `intent://` can reach other apps' components and `file://` can
   hand over a local file, so only `http`/`https` with a real host are forwarded.
   `share`/`openUrl` fall back to the application context with `FLAG_ACTIVITY_NEW_TASK`
   when the `Activity` is gone rather than returning `UNAVAILABLE` as this doc's
   lifecycle rule says: the call genuinely succeeds that way, and refusing something
   that would have worked is the worse contract. `rebind(activity)` re-points both
   `AndroidSystemCapability` and `GoogleTokenProvider` after a configuration change.

   Verified on a real device: 83 JVM unit tests, and 6 instrumented tests on the
   booted `Medium_Phone_API_36.1` emulator — including the real capability answering
   the real `window.permetic` global end to end through the existing fixture page,
   with nothing faked on either side. `GoogleTokenProvider` itself has **no automated
   test**: it needs a signed-in Google account and a real `serverClientId`, so it
   needs a manual device run before it can be called verified.
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

    **Done** (2026-08-24), read side only — `OtaBundleStore` is deliberately **not** a
    downloader. How a bundle arrives (WorkManager poll, push wakeup, launch check) is
    spec 06's open D-1; the seam here is `install(staging)`, which takes an
    already-downloaded directory. **D-4 and D-5 are resolved**: a separate content key,
    so it can be rotated without a store release, and next-launch-only application —
    `resolve()` picks the live directory at startup, so `install()` moving the pointer
    mid-session never changes a running app underneath itself. ECDSA P-256 rather than
    Ed25519, which needs API 33 against `minSdk 24`.

    Everything except the path handlers is JVM-only, enforced by extending task 2's
    detekt `ForbiddenImport` rule to `ota/` (verified by sabotage-and-revert). That is
    not tidiness: it means the entire verify/activate/rollback state machine runs under
    real unit tests against a temp directory, which matters for code whose failure mode
    is "an unverified download becomes remote code execution".

    Verification refuses a bundle four ways, and the third is the one a per-file-digest
    check alone would miss: **a file present that the manifest does not cover**, which
    is exactly how an extra unvouched-for script would ride into an otherwise-intact
    tree. `install()` additionally rejects a `bundleVersion` that does not supersede the
    active one — without that guard, replaying a genuinely-signed *older* bundle
    reintroduces a fixed vulnerability while every signature check passes.

    Two deviations from what this doc and spec 06 sketch, both deliberate. The
    signature is **detached** (`manifest.sig`) rather than a field inside the manifest:
    signing bytes that contain the signature requires both sides to agree on a canonical
    serialization of everything-but-that-field, and any disagreement silently becomes a
    verification failure. And pointer state uses **two alternating slots** rather than
    write-temp-then-atomically-rename, because `java.nio.file.Files.move` is API 26 and
    `File.renameTo` is not atomic over an existing target on every platform; a crash
    mid-write can only corrupt the slot that was not being read from.

    Rollback hinges on knowing a bundle actually ran. `PermeticController` confirms it
    on the **first successful bridge request** — the JS parsed, the `permetic-web`
    runtime initialised, and it reached native — which, unlike a page-load callback, is
    not something a white-screening bundle also produces. `markWebAppReady()` is public
    for apps with a stronger readiness signal.

    This also finally makes `Builder.assets(path)` real, which it had not been since
    task 5: `SubfolderAssetsPathHandler` prepends the subfolder and **delegates** to
    `AssetsPathHandler` rather than reimplementing asset lookup, so MIME detection,
    `index.html` resolution and containment stay androidx's job. Only one handler is
    ever registered — OTA bundle or APK assets, chosen at startup — not a fallback chain
    across both: a bundle is verified as a complete tree, so quietly filling a gap in it
    from another source would serve a mix nothing vouched for.

    Verified: 108 JVM unit tests (25 new, covering every rejection path and the full
    rollback state machine) and 9 instrumented tests on the booted
    `Medium_Phone_API_36.1` emulator, including a signed bundle installed, resolved and
    served to a real WebView end to end. Signing runs under Android's security provider
    there rather than the desktop JVM's, which the unit tests cannot exercise.
    Still open for a later round: signature verification currently trusts a public key
    the embedding app supplies, so **key provisioning and rotation are the host app's
    problem** and undesigned here.
11. **Hardening pass.** Settings lockdown, navigation policy (external links to the
    browser), file chooser, runtime permission mapping, back handling, CSP for the
    bundled assets.

12. **`pip`.** Picture-in-Picture. Adds a `pip` capability, since Android WebView
    does not implement the Web Picture-in-Picture API at all — Activity PiP is the
    only mechanism, and it shrinks the whole WebView, so this is a mode the web app
    has to re-lay-out for rather than a fire-and-forget call. Designed in **spec 07**,
    including the manifest attributes the embedding app must declare (getting
    `configChanges` wrong reloads the page mid-video). Independent of tasks 7–11 and
    can proceed in parallel from task 6 onward.

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

- **D-1** ~~Auth provider: Credential Manager + `AuthorizationClient`, or AppAuth?
  Play Services is easier but ties you to GMS builds.~~ **Resolved (2026-08-24)**:
  Credential Manager, isolated in the optional `:permetic-auth-google` module so
  `permetic-core` stays GMS-free and a non-GMS build can supply its own
  `TokenProvider`. `AuthorizationClient` was not needed — this app wants ID tokens,
  not scoped OAuth. See task 6.
- **D-2** `minSdk`. 24 widens reach; 26 removes WorkManager and notification-channel
  branching.
- **D-3** One Gradle repo with `docstack-*`, or two repos sharing the contract as a
  published artifact?
- **D-4** ~~OTA signing: reuse the app signing identity, or a separate content key?~~
  **Resolved (2026-08-24)**: a separate content key (ECDSA P-256), for exactly the
  rotation reason. Provisioning and rotation of that key are not designed yet — the
  verifier takes whatever public key the embedding app hands it. See task 10.
- **D-5** ~~Does OTA'd content apply on next launch only, or can it hot-swap on
  resume?~~ **Resolved (2026-08-24)**: next-launch-only, for the stated reason.
  `OtaBundleStore.resolve()` picks the live directory at startup and nothing re-reads
  it afterwards, so this is structural rather than a convention to remember. See task 10.
