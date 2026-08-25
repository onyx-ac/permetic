# 07 — Picture-in-Picture

Status: **draft, awaiting review** · Owner: Onyx
Modules: `permetic` (new `pip` capability), `permetic-web`

## Goal

Let the caged web app keep playing video — or keep showing any small live surface —
in a floating window when the user leaves the app. Spec 01 owns the host and the
capability registry; this spec owns the `pip` capability that plugs into it.

## Non-goals

- Playback itself. Permetic does not own the player, the codec, or the media
  pipeline. The `<video>` element stays the web app's.
- A native media notification / lock-screen controls. That is `MediaSession`
  territory and a separate decision (see D-2).
- Android TV. TV's PiP is a different surface with different rules; the contract
  does not foreclose it, do not build it.
- iOS, consistent with everything else here.

## Why this needs a capability at all

**Android WebView does not implement the Web Picture-in-Picture API.**
`HTMLVideoElement.requestPictureInPicture()` and `document.pictureInPictureElement`
are absent, and Document PiP (`window.documentPictureInPicture`) is desktop-Chrome
only. A web app that works in Chrome on Android will therefore silently do nothing
inside the WebView.

The only mechanism available is **Activity** Picture-in-Picture —
`Activity.enterPictureInPictureMode(PictureInPictureParams)`. That is native API,
reachable only from Kotlin, which is exactly the shape Permetic exists to bridge.

## The part that is not an RPC call

Activity PiP shrinks **the whole Activity**, and therefore the whole WebView. The
web app does not get a floating video element — it gets its entire UI rendered at
roughly a quarter of the screen. Left alone that looks broken: navigation bars,
headers and controls, all shrunk to illegibility around the video.

So `pip` is a **mode, not a call**. `enter()` is the smaller half; the load-bearing
half is the web app knowing it is in PiP so it can strip itself down to just the
media. That makes `onModeChange()` mandatory, not a convenience.

Two channels report the same transition, deliberately:

- **CSS media queries fire on their own.** The window really is resized, so the
  viewport changes and the app's own breakpoints re-evaluate with no bridge round
  trip. This is the fast path and should carry the *layout*.
- **`onModeChange()` is authoritative.** A tiny viewport and a PiP window are not
  the same fact, and only the bridge can tell them apart. This should carry
  *behaviour*: pausing polling, suppressing overlays and dialogs, disabling
  interactions that cannot work in a window with no keyboard and no touch targets.

Relying on the bridge event alone means a visible frame or two of full UI at PiP
size. Relying on the media query alone means a phone in a narrow layout gets treated
as PiP. Use both.

## Contract (proposed, not yet landed)

`pip` is a new `CapabilityName`, so landing this is a contract change, with the full
drift dance spec 01 task 1 set up: edit `packages/permetic-web/src/index.d.ts` and
`packages/permetic-web/contract/manifest.json`, mirror the interface in
`capability/`, and add the `CapabilityName` entry — at which point `Dispatcher`'s
`when` stops compiling until its arm exists, which is the point.

```ts
export interface PipCapability {
  /** Device, manifest and user settings all permit PiP right now. See "Availability". */
  supported(): Promise<boolean>;

  /**
   * Enters PiP. Must be called while the app is still interactive — entering from
   * an already-backgrounded Activity throws on the native side. To survive the user
   * pressing home, pre-arm with setParams({ autoEnter: true }) instead.
   */
  enter(options?: PipOptions): Promise<void>;

  /**
   * Updates aspect ratio, actions or auto-enter. Valid both while in PiP and,
   * importantly, before it — this is how autoEnter gets armed.
   */
  setParams(options: PipOptions): Promise<void>;

  onModeChange(listener: (inPip: boolean) => void): Unsubscribe;

  /** A button in the PiP window was tapped; the payload is PipAction['id']. */
  onAction(listener: (actionId: string) => void): Unsubscribe;
}

export interface PipOptions {
  /** Constrained by Android to between 1:2.39 and 2.39:1. */
  aspectRatio?: { width: number; height: number };
  /** CSS-pixel rect of the element being PiP'd, for the enter animation. */
  sourceRect?: { x: number; y: number; width: number; height: number };
  /** API 31+. Ignored below it — see "API-level reality". */
  autoEnter?: boolean;
  actions?: readonly PipAction[];
}

export interface PipAction {
  id: string;
  /** A closed set, not an arbitrary icon. See D-1. */
  kind: 'play' | 'pause' | 'next' | 'previous' | 'replay';
  label: string;
  enabled?: boolean;
}
```

`onModeChange`/`onAction` are ordinary `onXxx` subscriptions and cancel through the
reserved `"unsubscribe"` wire method established in spec 01 task 4.

## What the embedding app must do

None of this is settable from a library, and all of it is required:

```xml
<activity
    android:name=".MainActivity"
    android:supportsPictureInPicture="true"
    android:resizeableActivity="true"
    android:configChanges="screenSize|smallestScreenSize|screenLayout|orientation" />
```

`configChanges` is the one that bites. Without it the Activity is **recreated** on
the PiP transition, which tears down and rebuilds the WebView: the page reloads,
playback stops, and every bit of JS state is lost — precisely at the moment the user
asked to keep watching. Spec 01's "subscriptions are re-attached, not recreated" rule
does not rescue this, because the problem is the page, not the subscriptions. The fix
is to not have the configuration change at all.

The capability can detect a misconfigured host rather than failing mysteriously:
`PackageManager.getActivityInfo(...).configChanges` exposes the declared bits, and
`ActivityInfo.FLAG_SUPPORTS_PICTURE_IN_PICTURE` exposes the first attribute. Both
should be checked at registration and reported loudly.

One more host-side trap: an app that calls `webView.onPause()` from
`Activity.onPause()` — common, and correct without PiP — will **freeze the video the
instant PiP starts**, because entering PiP pauses the Activity while leaving it
visible. Gate that call on `isInPictureInPictureMode`.

## API-level reality

Against spec 01's `minSdk 24`:

| Need | Available from |
| --- | --- |
| PiP on handhelds at all | **26** (API 24's PiP was Android TV only) |
| `enterPictureInPictureMode(PictureInPictureParams)`, `setSourceRectHint`, actions | 26 |
| `setAutoEnterEnabled` | 31 |
| `setExpandedAspectRatio` | 33 |

So `supported()` is false below 26 regardless of anything else.

The gap between 26 and 31 is the awkward one. Before `setAutoEnterEnabled`, entering
PiP as the user leaves means calling `enterPictureInPictureMode()` from
`onUserLeaveHint()` — **which is not reliably delivered under gesture navigation**.
That is the entire reason the API 31 flag exists. See D-4.

## Availability, and why `supported()` exists alongside `available('pip')`

Spec 01 defines `available()` as "was it passed to `.capability(...)`", and that
should not change. But PiP has two further gates the registration cannot express:

- **Static**: API level, `FEATURE_PICTURE_IN_PICTURE`, and the manifest attributes.
  Known at startup, so the host app can simply not register the capability when they
  fail, keeping `available('pip')` honest.
- **Runtime**: the user can switch PiP off for the app in system settings. Checkable
  via `AppOpsManager.OPSTR_PICTURE_IN_PICTURE` on API 29+, and below that only
  observable as `enterPictureInPictureMode()` returning false.

So `enter()` must still be able to fail after `supported()` said yes. Map the user's
own refusal to `PERMISSION_DENIED`, not `UNAVAILABLE` — it is a permission, and the
web app may reasonably want to say so.

## Actions, and the two things that must not be got wrong

PiP windows take up to `Activity.getMaxNumPictureInPictureActions()` buttons
(three in practice). Each is a `RemoteAction` wrapping a `PendingIntent`, and a tap
has to come back across the bridge as an `onAction` event.

- **The `PendingIntent` must be immutable and explicit**, targeting a receiver in
  the app that is not exported. Mutable or implicit, it becomes a way for another app
  to drive the web app's playback controls. `FLAG_IMMUTABLE` is mandatory from API 31
  anyway; treat it as mandatory everywhere.
- **Icons are a closed set, not web-app-supplied bitmaps.** The web app cannot ship
  an Android drawable, and letting it hand over arbitrary images puts attacker-shaped
  content into a system surface. `PipAction['kind']` maps to drawables bundled in
  `permetic`. See D-1.

`sourceRect` also needs care: JS measures in CSS pixels via
`getBoundingClientRect()`, and Android wants device pixels relative to the window, so
the capability has to scale by the WebView's current factor and offset by its
position on screen. Getting it wrong degrades the enter animation without failing, so
it will rot unnoticed unless it is checked on a device.

## Interaction with spec 01

- **Lifecycle.** PiP does not cross the foreground/background boundary: the Activity
  is paused but stays visible and started, so `system.onLifecycle()` is expected to
  keep reporting `foreground`. That is why PiP needs its own signal. **Verify this on
  a device before relying on it** — it is asserted here from the documented lifecycle,
  not yet measured.
- **Activity binding.** `enterPictureInPictureMode` needs the Activity, so this
  capability follows the existing rule: `UNAVAILABLE` when the weak reference is gone,
  never a throw.
- **Navigation policy (task 11).** Opening an external link from a PiP window would
  yank the user out of the window they just chose. Task 11 should suppress
  `system.openUrl` while in PiP, or the hardening pass should record why not.

## Verification

`./gradlew :permetic:test` for the pure parts — aspect-ratio clamping, CSS-to-device
rect conversion, action-list truncation, and the manifest/API-level gating, all of
which are ordinary functions and should not need a device.

Instrumented, on a real device or emulator: entering PiP from the fixture page,
`onModeChange` firing on both transitions, and the WebView still rendering afterwards.
A manual run is still needed for the things a test cannot judge — the enter animation
with and without `sourceRect`, auto-enter on the home gesture, and a tap on a PiP
action arriving back in JS.

The negative case matters as much: an Activity **without** `configChanges` declared
should be detected and reported, not left to fail as a mysterious page reload.

## Open decisions

- **D-1** Action icons: the closed `kind` enum proposed above, or web-app-supplied
  images? The enum covers essentially every real media case and keeps arbitrary
  content out of a system surface; images would only matter for non-media PiP.
- **D-2** Do actions stay an explicit list, or get derived from a native
  `MediaSession` fed by the page's `navigator.mediaSession` metadata? The latter
  would also buy lock-screen and headset controls for free, but it is a much larger
  integration and it is not established how much of `navigator.mediaSession` WebView
  actually propagates. Worth measuring before choosing.
- **D-3** Should `available('pip')` stay purely "registered" (with the host app
  registering conditionally), or become the first capability whose availability is
  computed? Changing it is a contract-semantics change affecting every capability, so
  the bar is high.
- **D-4** Below API 31, implement the `onUserLeaveHint()` auto-enter fallback despite
  it being unreliable under gesture navigation, or declare auto-enter an API 31+
  feature and let 26–30 have explicit `enter()` only? Shipping a feature that works
  perhaps half the time is arguably worse than not shipping it.
- **D-5** Does `pip` belong in `permetic-core` or its own optional module? It carries
  no heavy dependency the way push and billing do, which argues for core — but it does
  impose manifest requirements on every host, which argues against.
