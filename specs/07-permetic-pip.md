# 07 — Picture-in-Picture

Status: **draft, awaiting review** · Owner: Onyx
Modules: `permetic` (new `pip` capability), `permetic-web`

## Goal

Let the caged web app keep playing video — or keep showing any small live surface —
in a floating window when the user leaves the app. Spec 01 owns the host and the
capability registry; this spec owns the `pip` capability that plugs into it.

## Non-goals

- Playback itself. Permetic does not own the player, the codec, or the media
  pipeline. The `<video>` element stays the web app's. Fullscreen *handling* is in
  scope, though — see below; it is the mechanism PiP is built on, not a separate
  feature.
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

## Fullscreen first: what actually ends up in the PiP window

The PiP window shows **whatever the Activity's window contains**. That one fact
decides the design, and it cuts both ways.

Enter PiP while the WebView is showing its ordinary page and the user gets the entire
web app rendered at roughly a quarter of the screen — navigation, headers and controls
shrunk to illegibility around the video. That is the bad path, and it is the one you
get by doing nothing special.

The good path goes through fullscreen. When a page calls `video.requestFullscreen()`,
WebView hands the app a **native `View` containing only the fullscreened element**,
through `WebChromeClient.onShowCustomView`. Attach that view over the WebView and the
Activity's content *is* the video — so entering PiP from there yields a window with
just the video in it, needing no cooperation from the page's layout at all.

```
video.requestFullscreen()
  -> WebChromeClient.onShowCustomView(videoView, callback)
  -> attach videoView over the WebView, WebView hidden underneath
  -> enterPictureInPictureMode(params)
  -> the PiP window contains videoView, and nothing else
```

The order is **fullscreen, then PiP** — not PiP directly. `pip.enter()` called without
anything in fullscreen still works, but it takes the shrink-everything path below.

### This does not work today

Permetic sets a `WebViewClient` and no `WebChromeClient` at all.
`onShowCustomView`'s default implementation does nothing, so **HTML5 fullscreen video
is currently broken in Permetic outright** — a page calling `requestFullscreen()` gets
no fullscreen and no error. That is a gap independent of PiP, and a prerequisite for
it: task 12 has to land fullscreen handling before it can land PiP. See D-6.

## The fallback, and when it is the only option

Not every PiP-worthy surface is a single fullscreenable element. A video call
compositing local and remote streams with its own controls, a live map, a running
dashboard — for these the page may genuinely want its whole document in the window.
Then the Activity does shrink wholesale and the page has to re-lay-out for it.

That is why `onModeChange()` stays in the contract rather than being an artifact of
the shrink path. In the fullscreen path it carries *behaviour* only — pause polling,
suppress dialogs and toasts, drop interactions that cannot work in a window with no
keyboard. In the fallback path it carries layout too, and there the split matters:

- **CSS media queries fire on their own.** The window really is resized, so the page's
  own breakpoints re-evaluate with no bridge round trip. Fast path, layout.
- **`onModeChange()` is authoritative.** A narrow phone viewport and a PiP window are
  not the same fact, and only the bridge can tell them apart. Behaviour.

Relying on the event alone means a visible frame or two of full UI at PiP size;
relying on the media query alone means a narrow phone gets treated as PiP.

## Measured: JavaScript keeps running in PiP

ADR 0011 raises the question this spec turns on for the tracker case, and it is not a
detail there. A `<video>` decodes in the media pipeline with JS nowhere in the
per-frame loop, so throttling barely touches it. A **canvas stream has no native
producer** — every frame is a JS paint, so if timers stop, the PiP window shows a
frozen clock, which is worse than showing nothing because it looks like it is working.

Measured on the `Medium_Phone_API_36.1` emulator (API 36), 48 seconds of steady-state
PiP, sampled every 4s, against a 100ms `setInterval` and a `requestAnimationFrame`
loop both painting a canvas:

| | foreground | in PiP (worst of 12 samples) |
| --- | --- | --- |
| `setInterval(100ms)` | 8.3/s | **10.0/s** |
| `requestAnimationFrame` | 35.3/s | **57.3/s** |

Neither is throttled — both run *faster* in PiP than the foreground baseline, which
only looks odd until you notice the baseline window included page startup.

The mechanism is the part worth keeping: **`document.visibilityState` stays
`"visible"` throughout PiP.** Chromium's timer throttling is gated on the page being
hidden, and in PiP it never is. That is why nothing is throttled, and it is a much
better thing to rely on than a measured rate.

Two consequences for ADR 0011:

- **Canvas-streamed PiP is viable on Android.** The escalation path it describes —
  `OffscreenCanvas` in a Worker — is not needed.
- **The move off `requestAnimationFrame` onto an interval was not necessary here.**
  rAF keeps running at ~58/s. It is harmless to keep, and still right for a hidden
  page, but on this path it buys nothing.

Reproduce with `PipProbeActivity` in the `androidTest` source set:

```
./gradlew :permetic:assembleDebugAndroidTest
adb install -r -t packages/permetic/build/outputs/apk/androidTest/debug/permetic-debug-androidTest.apk
adb shell am start -n ac.onyx.permetic.test/ac.onyx.permetic.pip.PipProbeActivity
adb logcat -d -s PipProbe:I
```

Two caveats, both real. This is an **emulator**, so it answers "does AOSP WebView do
this" — an OEM build could differ, and a real-device run is the confirmation. And it
measures the WebView *visible in the PiP window*; once fullscreen handling exists, the
custom-view path needs measuring again, because there the WebView is covered by the
video view and may not stay `visible`.

It also could not be driven from `ActivityScenario`: under instrumentation the activity
never gained window focus and the system refused PiP ten times out of ten. Launched
normally it enters first try. Worth knowing before anyone tries to make this a
conventional instrumented test.

## Contract (proposed, not yet landed)

`pip` is a new `CapabilityName`, so landing this is a contract change, with the full
drift dance spec 01 task 1 set up: edit `packages/permetic-web/src/index.d.ts` and
`packages/permetic-web/contract/manifest.json`, mirror the interface in
`capability/`, and add the `CapabilityName` entry — at which point `Dispatcher`'s
`when` stops compiling until its arm exists, which is the point.

Three members. ADR 0011 asked for exactly these and explicitly did not want the rest;
nothing else has a consumer, so nothing else ships.

```ts
export interface PipCapability {
  /** Device, manifest and user settings all permit PiP right now. See "Availability". */
  supported(): Promise<boolean>;

  /**
   * Enters PiP. If an element is currently fullscreen, the window contains just that
   * element; otherwise it contains the whole document and the page must re-lay-out.
   * Call video.requestFullscreen() first for the former.
   *
   * Must be called while the app is still interactive — the system refuses PiP from
   * an Activity that is not visible and focused.
   */
  enter(options?: PipOptions): Promise<void>;

  onModeChange(listener: (inPip: boolean) => void): Unsubscribe;
}

export interface PipOptions {
  /** Constrained by Android to between 1:2.39 and 2.39:1. */
  aspectRatio?: { width: number; height: number };
}
```

`onModeChange` is an ordinary `onXxx` subscription and cancels through the reserved
`"unsubscribe"` wire method established in spec 01 task 4.

### Deferred, with the analysis kept

Not cut because they are bad ideas — cut because nothing needs them, and each carries
a cost worth not paying twice.

| Deferred | Why it can wait |
| --- | --- |
| **PiP actions** (`RemoteAction`, `PendingIntent`) | Tokido's controls live in a notification, which is a better surface for pause/stop anyway. Skipping them also skips the `PendingIntent` immutability requirement and the "arbitrary content in a system surface" icon problem entirely. Closes **D-1**. |
| **`MediaSession` integration** | Only worth it alongside actions, and it is a much larger integration whose payoff (lock-screen and headset controls) nothing is asking for. Closes **D-2**. |
| **`autoEnter` / `onUserLeaveHint`** | The user toggles the flyout explicitly, so the API 26–30 gesture-navigation unreliability never arises and no half-working fallback has to ship. Closes **D-4**. |
| **`sourceRect`** | Only needed on the non-fullscreen path. Removes the CSS→device-pixel conversion this spec flagged as the kind that "rots unnoticed". |
| **`setParams()`** | Existed to arm `autoEnter` and update actions. With both gone it has no job. |

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

One more host-side rule, and for a canvas-streamed surface it is fatal rather than
merely ugly: an app that calls `webView.onPause()` from `Activity.onPause()` — common,
and correct without PiP — **freezes frame production the instant PiP starts**, because
entering PiP pauses the Activity while leaving it visible. Everything measured above
depends on this call not happening. Gate it on `isInPictureInPictureMode`.

Everything *else* is Permetic's job, not the host's: the `WebChromeClient`, attaching
and detaching the custom view, and calling `enterPictureInPictureMode`. The host
declares the manifest attributes and stays out of it.

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

## If actions are ever revived

Recorded so the analysis is not redone from scratch. PiP windows take up to
`Activity.getMaxNumPictureInPictureActions()` buttons (three in practice), each a
`RemoteAction` wrapping a `PendingIntent`, with taps coming back over the bridge.

- **The `PendingIntent` must be immutable and explicit**, targeting a non-exported
  receiver. Mutable or implicit, it is a way for another app to drive the web app's
  controls. `FLAG_IMMUTABLE` is mandatory from API 31 anyway; treat it as mandatory
  everywhere.
- **Icons must be a closed set, not web-app-supplied bitmaps.** The page cannot ship an
  Android drawable, and accepting arbitrary images puts page-controlled content into a
  system surface.

Likewise `sourceRect`, if the non-fullscreen path ever needs a good animation: JS
measures in CSS pixels, Android wants device pixels relative to the window, so it has
to be scaled by the WebView's factor and offset by its on-screen position. It degrades
the animation without failing anything, so it rots unnoticed unless checked on a
device.

## Interaction with spec 01

- **Lifecycle.** PiP does not cross the foreground/background boundary: the Activity
  is paused but stays visible and started, so `system.onLifecycle()` keeps reporting
  `foreground`. Measured, not assumed — see above, where the page also stays
  `document.visibilityState === "visible"`. That is exactly why PiP needs its own
  signal: nothing in the existing lifecycle contract changes when it starts.
- **Activity binding.** `enterPictureInPictureMode` needs the Activity, so this
  capability follows the existing rule: `UNAVAILABLE` when the weak reference is gone,
  never a throw. The custom view is attached to that Activity's content view, so it is
  bound to the same lifetime and must be detached in `onDestroy()`.
- **`attach()` currently sets only a `WebViewClient`.** Adding a `WebChromeClient`
  there is the concrete change task 12 starts with. It is also where any future
  file-chooser and permission-prompt handling lands (task 11), so the two tasks touch
  the same seam and should agree on who owns it.
- **Navigation policy (task 11).** Opening an external link from a PiP window would
  yank the user out of the window they just chose. Task 11 should suppress
  `system.openUrl` while in PiP, or the hardening pass should record why not.

## Can `onModeChange` say who ended PiP?

ADR 0011 asks, because on the web it treats dismissing the PiP window as "pause the
activity", and that rule cannot cross unchanged: on Android the *system* can reclaim a
window — another app pops one out, or memory pressure — and pausing somebody's tracked
time because of that would be indefensible.

The short answer is **no, not the distinction that matters**, and it is worth being
precise about why. Android reports *that* the mode changed, never why. Two of the three
cases can be separated with effort: on `onPictureInPictureModeChanged(false, …)` the
Activity resuming means the user expanded it back, while the Activity stopping means
the window went away. But **"the user closed it" and "the system reclaimed it" both
look like the second case**, and that is exactly the pair the pause rule turns on.

So ADR 0011's plan is the right one: Android keeps its own semantics and the
notification stays the source of truth. `onModeChange` carries a boolean and nothing
else. Anything richer would be a guess dressed as a fact, and the failure mode is
silently pausing time a user is still tracking.

## Verification

`./gradlew :permetic:test` for the pure parts — aspect-ratio clamping, CSS-to-device
rect conversion, action-list truncation, and the manifest/API-level gating, all of
which are ordinary functions and should not need a device.

Instrumented, on a real device or emulator: `video.requestFullscreen()` from the
fixture page actually reaching `onShowCustomView` and the custom view being attached
(that is the prerequisite, and it is testable on its own before any PiP exists);
entering PiP from there; `onModeChange` firing on both transitions; and the WebView
still rendering afterwards.

A manual run is still needed for what a test cannot judge — chiefly that the PiP window
contains **only the video** rather than the shrunken page.

And re-run `PipProbeActivity` **on a real device**, and again once fullscreen handling
exists: the numbers above were taken on an emulator, with the WebView itself in the PiP
window. Under the custom-view path the WebView is covered by the video view, and
whether the page stays `visible` there is the same question all over again with a
different answer possible.

The negative case matters as much: an Activity **without** `configChanges` declared
should be detected and reported, not left to fail as a mysterious page reload.

## Open decisions

- **D-1** ~~Action icons: closed enum or web-app-supplied images?~~ **Closed
  (2026-08-25)**: PiP actions are deferred entirely — ADR 0011's controls live in a
  notification. Revisit only with a consumer.
- **D-2** ~~Explicit action list, or derived from a native `MediaSession`?~~ **Closed
  (2026-08-25)**: moot while actions are deferred.
- **D-3** Should `available('pip')` stay purely "registered" (with the host app
  registering conditionally), or become the first capability whose availability is
  computed? Changing it is a contract-semantics change affecting every capability, so
  the bar is high. **Still open** — `supported()` sidesteps it for now.
- **D-4** ~~Implement the `onUserLeaveHint()` auto-enter fallback below API 31?~~
  **Closed (2026-08-25)**: auto-enter is deferred; the flyout is toggled explicitly, so
  the unreliable path never has to ship.
- **D-5** Does `pip` belong in `permetic-core` or its own optional module? It carries
  no heavy dependency the way push and billing do, which argues for core — but it does
  impose manifest requirements on every host, which argues against.
- **D-6** Does fullscreen handling ship as part of task 12, or ahead of it as its own
  change? HTML5 fullscreen video is broken in Permetic today with or without PiP, so
  fixing it stands on its own merits and is far easier to verify in isolation. Bundling
  it into task 12 means the first thing that exercises it is also the most complicated
  thing that could go wrong. Leaning toward splitting it out.
