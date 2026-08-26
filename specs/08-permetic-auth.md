# 08 — `auth`

Status: **draft, awaiting review** · Owner: Onyx
Modules: `permetic` (contract), `permetic-auth-google` (implementation), `permetic-web`

Written from ADR 0011's sibling, `specs/adr/0009-auth-capability.md`, which is a hard
blocker rather than a request: Tokido signs in with Google on the web and cannot in the
cage.

## Goal

Let the caged web app obtain a Google identity, and later Google API authorization,
through native calls — because the WebView cannot obtain either.

## Non-goals

- **Account UI.** The chooser is the system's; Permetic draws nothing.
- **Firebase awareness.** The capability hands over a Google ID token. That it becomes
  a Firebase session is the page's business.
- **Token storage, caching, or refresh scheduling.** See "Stateless on purpose" — this
  is the one place this spec overrides work already written.
- **Apple sign-in.** Unbuilt on every platform, not blocked on this, not bundled in.
- iOS.

## Why this is different from every other capability

`pip` exists because Android WebView does not *implement* an API. `auth` exists because
**Google refuses to serve one**: OAuth from an embedded WebView is rejected with
`disallowed_useragent`, deliberately, as anti-phishing policy.

That distinction decides the shape. There is no setting, no `WebChromeClient`, and no
user-agent string that fixes it — and spoofing the user agent is against Google's terms,
not merely fragile. Sign-in happens natively, outside the WebView, and only the result
crosses back.

| Web flow | Why it fails in the cage |
| --- | --- |
| `signInWithPopup` | needs `window.open` and a `postMessage` handshake to the opener |
| `signInWithRedirect` | cross-origin storage; WebView storage partitioning finishes what Firebase's retreat from this flow started |
| hand-rolled OAuth in an iframe or the WebView | `disallowed_useragent` before the consent screen renders |

The good news is how small the result is. Firebase's `signInWithCredential` takes a
Google ID token and needs no popup, no redirect and no third-party cookies. The bridge
is "hand the page a token", and every sign-in path downstream of it works unchanged.

## Ship `signIn()` first, and alone

It is the entire difference between an app that can be used in the wrapper and one that
cannot, and it is a single native call returning a single string. `authorize` /
`authorizeOffline` / `grantedScopes` follow when Drive or Calendar are actually being
built.

## Contract (landed 2026-08-26 — replaced the previously frozen `AuthCapability`)

```ts
export interface AuthCapability {
  /** Whether this device can do any of this at all. See "supported() is not available()". */
  supported(): Promise<boolean>;

  /**
   * A Google ID token for Firebase signInWithCredential.
   * Resolves to null when the user dismissed the chooser — that is an outcome,
   * not an error. See "Cancellation is a result".
   *
   * `nonce` is optional but recommended: pass a random value, and check it comes
   * back in the token's claims, so a token captured elsewhere cannot be replayed
   * into this session.
   */
  signIn(options?: { nonce?: string }): Promise<string | null>;

  /** Short-lived access token for Google APIs. Null if the user refused. */
  authorize(scopes: readonly string[]): Promise<AuthorizationResult | null>;

  /**
   * A one-time server auth code — the only route to a refresh token, and it is
   * exchanged by the app's own service, never in the page. See "The one rule".
   */
  authorizeOffline(scopes: readonly string[]): Promise<string | null>;

  /** What is actually held right now; people revoke at myaccount.google.com. */
  grantedScopes(): Promise<readonly string[]>;

  revoke(scopes?: readonly string[]): Promise<void>;
  signOut(): Promise<void>;
  account(): Promise<{ id: string; email?: string } | null>;

  /**
   * The signed-in Google account changed. Kept (D-6) because the changes the page
   * does not cause are the ones it cannot otherwise see: an account removed on the
   * device, or access revoked at myaccount.google.com.
   */
  onAccountChange(listener: (id: string | null) => void): Unsubscribe;
}

export interface AuthorizationResult {
  accessToken: string;
  /** Null when the provider does not report one; the page owns expiry either way. */
  expiresAt: number | null;
  /** What was actually granted — consent can be partial, and the app must learn which. */
  grantedScopes: readonly string[];
}
```

### Incremental, not bundled

`signIn()` asks for identity and nothing else. Feature scopes are requested by
`authorize()` when somebody switches that feature on. This is Google's own guidance and
it is materially better consent: one refusal costs one feature rather than the account,
and the app learns *which* was refused — which is why `grantedScopes` is on the result
and not just a separate query.

The `getToken(scopes, interactive)` surface this replaced could not express it: it
conflates identity with authorization, so a first call either over-asks or cannot ask
at all.

## The one rule that belongs in the contract

**A refresh token must never cross the bridge.**

| Crosses | Lifetime | Why it is acceptable |
| --- | --- | --- |
| ID token | minutes | Audience-bound, exchanged immediately for a Firebase session |
| Access token | ~1 hour | Short enough that leaking it leaks an hour |
| Server auth code | single use | Useless without the client secret, which lives on the app's server |
| **Refresh token** | **until revoked** | **Never** |

This is an API shape, not a review comment: `authorizeOffline` returns a *code*, and
there is deliberately no member that returns a refresh token. A long-lived Google
credential inside a WebView is a long-lived credential inside everything that WebView
will ever render, for as long as the install exists.

## Stateless on purpose — and what this costs

**The capability caches nothing.** Every call really goes and asks. A capability that
quietly caches tokens becomes a second source of truth about who is signed in, competing
with the page's own session and the app service's.

This is a **direct reversal of what spec 01 task 6 already built**, and the reversal is
correct. That work added `CachingAuthCapability` — a scope-keyed token cache, single-flight
collapsing of concurrent fetches, and a `refresh()` path modelled on "a downstream API
returned 401". Under this contract:

- **The cache has nothing worth caching.** Credential Manager mints a freshly signed ID
  token per call, and the page exchanges it once for a Firebase session. Caching it saves
  no round trip and creates a stale-identity risk.
- **Single-flight loses its motivation.** It existed because a cold-starting page might
  fire N data calls each needing a token. Here `signIn()` is called once, explicitly, by
  the page — not N times by a data layer.
- **`refresh()` has no counterpart.** Expiry is the page's and the service's to handle;
  when a token dies the page calls again.

What survived from task 6 is the part that was about *Google* rather than about caching:
the Credential Manager call (now `GoogleAuthCapability`), the `:permetic-auth-google`
module keeping Play Services out of core, and the decision not to parse the JWT. See
"Reconciling with task 6" for the concrete list.

## Cancellation is a result

People dismiss the account chooser constantly — it is the most common outcome after
success. Surfacing it as a thrown error makes every call site write a `try`/`catch` that
swallows a normal interaction, and surfacing it as `PERMISSION_DENIED` actively lies
about what happened.

`signIn()`, `authorize()` and `authorizeOffline()` therefore resolve to **null** on
dismissal. Task 6 had mapped it to `BridgeErrorCode.CANCELLED` — better than
`PERMISSION_DENIED`, but still an error. A dispatcher test now pins the null result,
because this is the rule most likely to be quietly reverted by someone tidying up
error handling.

## `supported()` is not `available()`

Spec 01 defines `available()` as "was it passed to `.capability(...)`". Play Services is
absent on some devices, some regions and some corporate images, so a registered
capability can still be unable to do anything.

**Settled here once, for `auth` and `pip` both** (this closes spec 07's D-3):

- `available(name)` keeps meaning *registered*. It is contract semantics affecting every
  capability, and changing it to mean "registered and functional" would make it async,
  fallible, and different per capability.
- Any capability whose device support is conditional carries its own **`supported()`**
  member. It is a normal contract method, per-capability, and it can answer honestly
  because it knows what it needs.

The host app may still register conditionally; `supported()` exists because the answer
can also change after registration — a user revoking, Play Services being disabled.

## Error taxonomy

ADR 0009 is right that collapsing these is the failure mode of every auth bridge. Six
outcomes a caller acts on differently, mapped onto the **existing** `BridgeErrorCode`
plus `BridgeError.details`, which the contract already carries and nothing has used yet.
Widening the error enum would be a breaking change to every capability for one
capability's benefit.

| Outcome | Code | `details.reason` |
| --- | --- | --- |
| User dismissed | *not an error* — resolves null | — |
| No Play Services / unsupported device | `UNAVAILABLE` | `no-provider` |
| No Google account on the device | `UNAUTHENTICATED` | `no-account` |
| Network failure | `NETWORK` | — |
| Scope refused | `PERMISSION_DENIED` | `scope-refused`, plus `scopes` |
| Access revoked since last call | `UNAUTHENTICATED` | `revoked` |

"No account on the device" is deliberately distinct from dismissal: the app can offer to
add one, which it cannot do for a deliberate refusal.

## Reconciling with task 6

**Done (2026-08-26).** Task 6 was reworked to this contract before merging, rather than
landed and revised, so `main` never carried the caching design. What became of each
piece:

| Task 6 artifact | Outcome |
| --- | --- |
| `CachingAuthCapability` (cache, single-flight, epoch, `refresh`) | **Deleted**, with its test. Contradicted "Stateless on purpose". |
| `TokenProvider` SPI | **Deleted** rather than reshaped — see below. |
| `GoogleTokenProvider` Credential Manager call | **Kept**, as `GoogleAuthCapability`. Already returned an ID token. |
| `:permetic-auth-google` as a separate module | **Kept.** It is what makes `supported() === false` a real answer on non-GMS builds. |
| Cancellation → `CANCELLED` | **Changed** to a null result, pinned by a dispatcher test. |
| Non-empty scopes → `UNAVAILABLE` | **Became** `authorize()` / `authorizeOffline()`, which answer `UNAVAILABLE` until Drive or Calendar are built. |
| Not parsing the JWT for expiry | **Kept**, and it got easier: no cache means no expiry bookkeeping at all. |
| `CapabilityException`, `Dispatcher` hardening, `AndroidSystemCapability` | **Unaffected.** |

One thing this spec expected to reshape turned out to be deletable instead. The
`TokenProvider` SPI existed to separate caching (core) from identity (module); with the
caching gone there was nothing for a middle layer to do, so `GoogleAuthCapability`
implements `AuthCapability` directly and the indirection is gone. Statelessness paid for
itself immediately.

A second finding worth recording, because it was a test passing for the wrong reason:
`DispatcherTest`'s "missing required argument" case called `getToken`, which no longer
exists — so it was really exercising the unknown-method path and would have passed
whatever argument decoding did. It now calls `authorize`, which genuinely requires its
argument.

Verified on the merged `main`: 97 JVM tests, 21 TS tests, `tsc --noEmit`, ktlint and
detekt all clean, both modules assembling. The contract parity tests are the load-bearing
ones here — they are what confirm `index.d.ts`, `manifest.json`, the Kotlin mirror and
the dispatcher all moved together.

## Contract versioning

This changed `AuthCapability`'s surface incompatibly. `CONTRACT_VERSION` stayed at 1 and
the contract was amended in place (2026-08-26): nothing had shipped against it, so a
compatibility shim would have been carried for zero consumers. **Once Tokido ships, that
stops being true** — the OTA contract check (spec 01 task 10) refuses to activate a
bundle whose `contractVersion` disagrees with the installed native side, so the next
incompatible change to any capability is a real bump rather than an edit.

## Interaction with spec 01

- **`onAccountChange`.** The frozen contract has it; ADR 0009 does not ask for it. A
  stateless capability can only emit it in response to calls the page itself made, which
  makes it redundant. Proposed: drop it, and let `account()` answer. Called out rather
  than done quietly, because it is a contract *removal*. See D-6.
- **Activity binding.** Credential Manager renders over an Activity, so this follows the
  existing rule: `UNAVAILABLE` when the weak reference is gone, never a throw.
- **`serverClientId`** is host-app configuration, supplied at registration, never by the
  page.
- **Headless host.** The frozen contract's `getToken` documents a headless variant that
  "MUST NOT show UI". `signIn()` is inherently interactive, so a headless host answers
  `UNAVAILABLE` — worth stating, since spec 01 keeps the door open for that carrier.

## Verification

`./gradlew :permetic:test` covers the parts that do not need Google: error mapping,
`supported()` gating, and that a dismissal produces a null result rather than a failure.

Everything else needs a **manual device run with a real account and a real
`serverClientId`** — the same caveat task 6 already recorded for `GoogleTokenProvider`.
An emulator without Play Services is itself a useful test: `supported()` must return
false there rather than failing at the first call.

## Open decisions

- **D-1** ~~Credential Manager or the legacy Google Sign-In SDK?~~ **Resolved
  (2026-08-26)**: Credential Manager. It is the sanctioned path, legacy Google Sign-In is
  deprecated, and the device-support tail is answered by `supported()` returning false
  rather than by a second code path to maintain.
- **D-2** Play Services' authorization client or a Custom Tab for `authorize()`? Deferred
  with `authorize()` itself. Leaning: the authorization client first, in the same
  `:permetic-auth-google` module, and a Custom Tab provider only if a non-GMS consumer
  appears — building a fallback nobody needs is how two half-tested paths happen.
- **D-3** ~~Does the capability re-issue expired tokens itself?~~ **Resolved
  (2026-08-26)**: no. Stateless; the page asks again. See "Stateless on purpose".
- **D-4** ~~One capability or two?~~ **Resolved (2026-08-26)**: one. They share almost all
  their native surface, and a second `CapabilityName` means a second dispatcher arm,
  registration and parity entry for a split no host has asked for. A host that wants no
  Google API scopes simply never calls `authorize`.
- **D-5** ~~Error taxonomy.~~ **Resolved (2026-08-26)**: see the table above — existing
  codes plus `BridgeError.details.reason`, and dismissal is not an error at all.
- **D-6** ~~Drop `onAccountChange` from the contract, or keep it?~~ **Resolved
  (2026-08-26): keep.** The argument for dropping it — that a stateless capability can
  only report changes the page itself caused — turns out to be too quick. The account
  can also change with the page doing nothing: removed on the device, or revoked at
  myaccount.google.com. Native can observe those and the page cannot, which is precisely
  what the bridge is for. And emitting "the account changed" is a notification, not the
  cached credential state statelessness rules out.
  **Follow-up:** `GoogleAuthCapability` currently only emits on changes it made itself.
  Covering device-level removal needs an `AccountManager` listener and a decision about
  `GET_ACCOUNTS`; until then those changes surface as a failure on the next call rather
  than as an event. This is the gap that most makes the member worth having, so it should
  not sit indefinitely.
- **D-7** Should `signIn()` offer a silent variant for app restart — Credential Manager's
  `setFilterByAuthorizedAccounts(true)` — or does the page always re-authenticate through
  its own Firebase session? The latter is simpler and probably right, since a Firebase
  session outlives the ID token that created it.
