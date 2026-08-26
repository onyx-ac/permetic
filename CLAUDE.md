# permetic-android

**Permetic** runs a web app in an Android WebView and grants it scoped, declared
access to native features. The web app runs unchanged.

Permetic works standalone. `docstack-*` is optional, and when registered it adds a
`storage` capability: a document-level store that DocStack's PouchDB adapter talks to.
Permetic owns the WebView, so Permetic owns provisioning — DocStack never touches the
WebView directly.

This repo is the **Permetic monorepo**. Each module lives under `packages/`:

| Module              | Path                        | Coordinates / package                | Role                                                          |
| ------------------- | ---------------------------- | ------------------------------------- | ------------------------------------------------------------- |
| `permetic`          | `packages/permetic`          | `ac.onyx.permetic:permetic-core`      | WebView host. Runs the web app, grants scoped native access.  |
| `permetic-auth-google` | `packages/permetic-auth-google` | `ac.onyx.permetic:permetic-auth-google` | Google identity via Credential Manager. Optional artifact — keeps Play Services out of core. |
| `permetic-push`     | `packages/permetic-push`     | `ac.onyx.permetic:permetic-push`      | FCM capability. Optional artifact.                            |
| `permetic-billing`  | `packages/permetic-billing`  | `ac.onyx.permetic:permetic-billing`   | Play Billing capability. Optional artifact.                   |
| `permetic-web`      | `packages/permetic-web`      | npm `permetic`                        | Contract types + JS runtime that builds the global.           |
| `permetic-ota`      | `packages/permetic-ota`      | npm `permetic-ota`                    | CLI: builds, signs, and publishes OTA bundles. Concept-level.  |

DocStack's native side (`docstack-store`, `docstack-permetic`, `docstack-headless`,
npm `@docstack/pouchdb-adapter-native`) lives in the separate `docstack` repo, as git
submodules under `android/` and `packages/` there. It consumes the contract below but
is not built from this repo.

The JS global is `permetic`.

## Read before coding

- `@specs/01-permetic-webview.md` — the WebView host and its JS runtime. Start here.
- `@specs/02-docstack-store.md` — the native document store and dispatcher (context;
  implemented in the `docstack` repo).
- `@specs/03-docstack-adapter.md` — the PouchDB adapter (context; implemented in the
  `docstack` repo).
- `@specs/04-docstack-headless.md` — the QuickJS engine (context; implemented in the
  `docstack` repo).
- `@specs/05-reference-topology.md` — a worked product example. Not a deliverable;
  read it when a product decision looks like it needs a contract change.
- `@specs/06-permetic-ota.md` — the OTA publishing CLI. Concept-level.
- `@specs/07-permetic-pip.md` — Picture-in-Picture. Read before touching `pip`; the
  WebView has no Web PiP API, so it works differently from how it does in a browser.
- `@specs/08-permetic-auth.md` — `auth`. Read before touching sign-in: Google refuses
  OAuth from an embedded WebView, the capability is deliberately stateless, and a
  refresh token must never cross the bridge. Supersedes the `auth` surface built in
  spec 01 task 6.
- `@packages/permetic-web/src/index.d.ts` — the capability contract. Source of truth.
- `specs/adr/` — decisions already made. If a spec conflicts with an ADR, stop and ask.

## Commands

```bash
./gradlew :permetic:test
./gradlew :permetic:connectedAndroidTest              # needs device/emulator
./gradlew ktlintCheck detekt                          # must pass before commit
(cd packages/permetic-web && npm run build && npm run typecheck && npm test)
```

Never hand-edit anything under `src/main/assets/`. It is build output.

## Conventions

- **Kotlin**: explicit API mode on for published modules. Public API is `suspend`
  functions and `Flow`, never callbacks. No `runBlocking` outside tests.
- **No JS string interpolation.** Everything crosses as a `BridgeRequest` envelope.
  `evaluate("Foo.put('$id')")` is an injection bug and is banned.
- **Errors** cross as `BridgeError` codes from the contract. Never raw exception
  strings, never stack traces in release builds. A capability reports one by throwing
  `CapabilityException(code, message)` — its `message` reaches JS, so keep it a short
  safe diagnostic. Anything else a capability throws becomes an opaque `INTERNAL`
  carrying only the exception's type name.
- **Contract drift is a compile error.** Adding a method means: edit
  `packages/permetic-web/src/index.d.ts`, update the hand-mirrored Kotlin interfaces
  in `capability/` and the shared `packages/permetic-web/contract/manifest.json`,
  fix the implementations (here and in the `docstack` repo). Never one side only —
  a capability-level change (adding/removing a `CapabilityName` entry) fails to
  compile once `Dispatcher` exists (task 5); a method-level change fails
  `ContractParityTest` (JVM) or `contract-parity.test.ts` (TS) today.
- **Native never parses a revision tree.** Trees are opaque blobs it stores and
  returns. Merge semantics belong to `pouchdb-merge`, in JS. See ADR-0001.
- **One protocol.** The adapter has no carrier branch in it. See ADR-0002.
- **Threading**: no bridge work on the main thread, except capability calls that
  legitimately need the Activity (billing sheet, permission prompt).
- **Minimums**: `minSdk 24`, `compileSdk 35`, Kotlin 2.x, JDK 17.

## Workflow

1. Plan mode first (Shift+Tab). Read the relevant spec and the files it names.
2. Restate the numbered tasks you intend to do. Do one at a time.
3. Run the tests named in the spec's verification section. Show the output.
4. Stop for review between tasks. Do not chain tasks unprompted.

Branch per feature: `feat/<module>-<short-name>`. Never commit to `main` directly.

## Out of scope

- The web app itself (separate repo, depends on npm `permetic`).
- `@docstack/client` and `@docstack/pouchdb-adapter-googledrive` (separate repo).
- iOS. The contract is written so a host is possible later. Do not build it now.
