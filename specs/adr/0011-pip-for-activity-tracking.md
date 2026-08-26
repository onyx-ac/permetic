# Permetic — what an activity tracker needs from `pip`

**For:** the Permetic repository, against `specs/07-permetic-pip.md`.
**From:** Tokido, which has the web half of this working and measured.
**Status:** a request and a warning, not a design. Spec 07 is the design, and it is good.

---

## The short version

Spec 07 is written for **video**, and almost all of it is right for us too. But we are
not playing a video — we are *generating* one, frame by frame, in JavaScript. That single
difference moves one of its unverified assumptions from a footnote to the thing the
feature lives or dies on, and it makes four of its open decisions unnecessary.

We need three things and none of the rest.

## What Tokido is doing

A running activity is drawn to a 2D canvas — elapsed time, a countdown ring, a label —
and streamed into a hidden `<video>`, which is the only thing the browser accepts for
Picture-in-Picture:

```
2D canvas ──captureStream(30)──▶ MediaStream ──srcObject──▶ <video> ──▶ requestPictureInPicture()
```

Built and verified in Chromium: `document.pictureInPictureElement` becomes the video, the
stream carries real 320×180 frames, and two samples 2.5 seconds apart differ — live
frames, not a still. It survives in-app navigation, because the video lives on
`document.body`, outside the view tree.

Inside Android WebView spec 07 already states the blocker correctly: **the Web PiP API is
absent.** So the last arrow does not exist, and that is what we need Permetic for.

## What we need

**1. A `WebChromeClient` with `onShowCustomView` / `onHideCustomView`.**

Not for PiP directly — for `requestFullscreen()`. Spec 07's central insight is that the
PiP window contains whatever the Activity's window contains, and that fullscreening one
element is how you get a window containing only that element. Our video *is* a single
fullscreenable element by construction, so the good path is available to us for free.

Spec 07 notes Permetic sets only a `WebViewClient` today, so HTML5 fullscreen is broken
outright, PiP or no PiP. **Ship this on its own first** — spec 07's D-6, and we agree with
its leaning. It is a standalone bug, far easier to verify in isolation, and a prerequisite
for everything else here.

**2. The manifest attributes, validated at registration.**

```xml
android:supportsPictureInPicture="true"
android:resizeableActivity="true"
android:configChanges="screenSize|smallestScreenSize|screenLayout|orientation"
```

`configChanges` is not a nicety for us. Without it the Activity is recreated on the PiP
transition, the WebView is torn down and the page reloads — **destroying the state of a
session the user just asked to keep watching.** Spec 07's proposal to detect a
misconfigured host through `getActivityInfo().configChanges` and report it loudly is
exactly right; please keep it.

**3. An activator: `enter()` with an aspect ratio, and `onModeChange`.**

Our face is 320×180 — 16:9, comfortably inside Android's 1:2.39–2.39:1 clamp. We need to
know when we are in PiP and when we have left it, to keep the tracker's own controls in
step with the window.

## What we do not need

Dropping these closes four of spec 07's open decisions outright:

| Not needed | Closes |
| --- | --- |
| PiP actions (`RemoteAction`, `PendingIntent`) | **D-1**, and with it the whole "arbitrary content in a system surface" problem |
| `MediaSession` integration | **D-2** |
| `autoEnter` / `onUserLeaveHint` | **D-4** — the user toggles flyout mode explicitly, so the API 26–30 gesture-navigation unreliability never arises |
| `sourceRect` | Needed only on the non-fullscreen path, which we do not take. Removes the CSS→device-pixel conversion spec 07 says "rots unnoticed" |

Our controls live in a **notification**, not in the PiP window. That is a better surface
for pause and stop anyway, and it is what makes the next section survivable.

## The warning: our frames come from JavaScript

Spec 07 says, of PiP not crossing the foreground/background boundary:

> *"Verify this on a device before relying on it — it is asserted here from the documented
> lifecycle, not yet measured."*

For a `<video>` that is a detail. Decode continues in the media pipeline with JS nowhere in
the per-frame loop, which is why YouTube keeps playing in PiP under hostile throttling.
**A canvas stream has no native producer.** Every frame is a JS paint, so if the WebView's
JS is paused or throttled to a stop, the PiP window shows a *frozen clock* — worse than
showing nothing, because it looks like it is working.

That makes two things load-bearing rather than incidental:

- **Gate `webView.onPause()` on `isInPictureInPictureMode`.** Spec 07 flags this for video;
  for us it is fatal rather than annoying. Entering PiP pauses the Activity while leaving
  it visible, and a host that pauses the WebView freezes our frame production at exactly
  the moment the window appears.
- **Measure whether timers keep running in PiP.** We have already moved the repaint off
  `requestAnimationFrame` onto an interval, because rAF is stopped outright for a hidden
  page while interval timers are only throttled — with a floor near 1Hz, and a clock needs
  1Hz. If Android's WebView stops timers too, the escalation is `OffscreenCanvas` in a
  Worker; if that also stops, canvas-streamed PiP is not viable there and the notification
  is the whole answer.

We could not settle this from the desktop: under automation the page never actually
backgrounds — `document.hidden` stayed false with **and** without PiP — so our measurement
was inconclusive rather than reassuring. It wants a real device.

## One semantic question, and why it is yours

On the web we treat **dismissing the PiP window as "pause the activity"**: pressing close
is deliberate, and minimising is available for merely getting it out of the way.

That rule cannot cross to Android unchanged, because there the *system* can reclaim a PiP
window — another app pops one out, or memory pressure — and pausing somebody's tracked time
because of that would be indefensible. So either `onModeChange` says who caused the change,
or Android keeps its own semantics with the notification as the source of truth. **We are
planning for the latter**, so `onModeChange` needs no new information; if distinguishing is
cheap on your side, it would let the two platforms behave alike.

## Summary

Ship fullscreen handling on its own. Then a `pip` capability of three members —
`supported()`, `enter({ aspectRatio })`, `onModeChange()` — plus manifest validation at
registration. No actions, no MediaSession, no auto-enter, no source rect.

And before any of it is relied on, measure one thing on a device: **does JavaScript keep
running in a WebView whose Activity is in PiP?** For a video app that is a detail. For us
it is the feature.
