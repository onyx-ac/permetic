# Permetic — `auth`, and why the WebView cannot do this one at all

**For:** the Permetic repository. No spec exists for this yet; this is the request that
should produce one.
**From:** Tokido, which signs in with Google on the web today and cannot in the cage.
**Status:** a hard blocker, not an enhancement.

---

## The short version

The PiP capability existed because Android WebView does not *implement* an API. This one
exists because **Google refuses to serve one** — OAuth from an embedded WebView is
rejected with `disallowed_useragent`, deliberately, as anti-phishing policy.

That distinction matters for how it gets specified. There is no configuration, no
`WebChromeClient`, no user-agent string that makes it work, and attempting the last of
those is against Google's terms rather than merely fragile. **Sign-in has to happen
natively, outside the WebView, with only the result crossing back.**

The good news is that the result is small. Firebase's `signInWithCredential` takes a
Google ID token and needs no popup, no redirect and no third-party cookies — so the whole
bridge is "hand the page a token", and every existing sign-in path downstream of it works
unchanged.

## What breaks, precisely

Tokido signs in with `signInWithPopup` (`016`). In the cage:

| | Why it fails |
| --- | --- |
| `signInWithPopup` | needs `window.open` and a `postMessage` handshake back to the opener |
| `signInWithRedirect` | cross-origin storage; Firebase has been retreating from this flow, and WebView storage partitioning finishes it |
| Any hand-rolled OAuth in an `<iframe>` or the WebView itself | `disallowed_useragent` before the consent screen renders |

So the failure is not a broken popup that could be shimmed. Google declines to render the
authorization page at all.

## What we need

Seven members, and the first one alone unblocks the app.

| Member | Returns | Notes |
| --- | --- | --- |
| `signIn()` | a Google **ID token** | Credential Manager (`androidx.credentials`) is the current sanctioned path. The page hands this to Firebase `signInWithCredential`. |
| `authorize(scopes)` | a short-lived **access token** | Play Services' authorization client, or a Custom Tab. Called when a *feature* is switched on, never at sign-in. |
| `authorizeOffline(scopes)` | a one-time **server auth code** | The only route to a refresh token. Exchanged by our service, never in the page. |
| `grantedScopes()` | what is actually held | People revoke access at `myaccount.google.com`; without this the app can only guess. |
| `revoke(scopes?)` / `signOut()` | — | Native credential state is not the page's to clear. |
| `account()` | which Google account is in use | A phone commonly holds several. |
| `supported()` | whether any of this is possible | Play Services is absent on some devices and in some regions. |

### Incremental, not bundled

`signIn()` asks for identity and nothing else. Feature scopes — Drive, Calendar — are
requested by `authorize()` when somebody turns that feature on, which is Google's own
guidance and materially better consent: one refusal costs one feature rather than the
account, and the app learns *which* was refused.

This mirrors what the web build does, so the two platforms differ in mechanism and not in
behaviour.

## The one rule that belongs in the contract

**A refresh token must never cross the bridge.**

| Crosses | Lifetime | Why it is acceptable |
| --- | --- | --- |
| ID token | minutes | Audience-bound, and it is exchanged immediately for a Firebase session |
| Access token | ~1 hour | Short enough that leaking it leaks an hour |
| Server auth code | single use | Useless without the client secret, which lives on our server |
| **Refresh token** | **until revoked** | **Never.** |

This is not a review comment, it is an API shape: `authorizeOffline` returns a *code*, and
there is deliberately no member that returns a refresh token. A long-lived Google
credential inside a WebView is a long-lived credential inside everything that WebView will
ever render, for as long as the install exists.

Where the refresh token ends up is our problem, not Permetic's — our service exchanges the
code and holds it (`040`). The capability's job is to make sure the page never has the
option.

## Two things that will be got wrong otherwise

**Cancellation is a result, not an error.** People dismiss the account chooser constantly
— it is the most common outcome after "success". Surfacing it as a thrown error, or worse
as `PERMISSION_DENIED`, makes every call site write a `try`/`catch` that swallows a normal
interaction. It should come back as an ordinary "no account chosen".

**`supported()` is not `available()`.** Spec 01 defines `available()` as "was it
registered". Play Services can be absent on the device — some regions, some AOSP builds,
some corporate images — so a registered capability can still be unable to do anything.
This is the same split spec 07 argued for `pip`, and it should be settled once for both
rather than twice differently.

## What we do not need

- **No account UI in the capability.** The chooser is the system's; Permetic does not draw
  anything.
- **No token storage or refresh scheduling.** The page and our service own expiry. A
  capability that quietly caches tokens becomes a second source of truth about who is
  signed in, and `016` already has two it is careful about.
- **No Firebase awareness.** The capability hands over a Google ID token. That it becomes
  a Firebase session is entirely our side of the bridge.
- **No Apple sign-in.** `016` lists it as unbuilt on every platform; it is not blocked on
  this and should not be bundled into it.

## What it buys, beyond sign-in

Two things fall out that are worth stating, because they make this capability cheaper than
it looks.

**Key escrow keeps working unchanged.** Tokido authenticates to its own service with a
Firebase ID token (`047`). `signInWithCredential` produces a genuine Firebase session, so
the token is the same token — the escrow design does not acquire an Android branch.

**Drive and Calendar become possible in the cage at all**, through the same three members.
Without `authorizeOffline` there is no durable Google API access on any platform, because
Firebase issues no Google refresh token and its access token dies in an hour.

## Open decisions

- **D-1 Credential Manager or the legacy Google Sign-In SDK for `signIn()`?** Credential
  Manager is the current recommendation; the legacy path has a longer tail of device
  support. The answer probably decides `minSdk` behaviour more than anything else.
- **D-2 Play Services' authorization client or a Custom Tab for `authorize()`?** The
  former is smoother and needs Play Services; the latter works anywhere a browser does and
  is what `supported() === false` would fall back to — if a fallback is wanted at all.
- **D-3 Does the capability re-issue expired access tokens itself, or hand that back to
  the page?** Doing it silently is nicer and starts the caching this note argues against.
  Our preference is that the page asks again and the capability is stateless.
- **D-4 One capability or two?** Identity and authorization are separable, and a host that
  wants sign-in without Google API scopes should not have to carry both. They share almost
  all their native surface, which argues the other way.
- **D-5 Error taxonomy.** Cancelled, no Play Services, no account on device, network,
  scope refused, revoked-since. These are six different things a caller acts on
  differently, and collapsing them is the failure mode of every auth bridge.

## Summary

Ship `signIn()` first and alone. It is the whole difference between an app that can be
used in the wrapper and one that cannot, and it is a single native call returning a single
string.

`authorize` / `authorizeOffline` / `grantedScopes` follow when Google Drive or Calendar are
actually being built, and they carry the rule that gives this note its shape: **the page
gets tokens that expire, and never one that does not.**
