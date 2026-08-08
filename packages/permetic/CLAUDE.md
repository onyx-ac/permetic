# permetic — WebView host

Read `@../../specs/01-permetic-webview.md` before changing anything here.

## What this module is

A WebView that runs the web app and grants it scoped, declared access to native
features. The name is the promise: nothing is permitted that wasn't registered.

## Non-negotiables

- Transport is `WebViewCompat.addWebMessageListener`, never `@JavascriptInterface`
  except as a feature-checked fallback exposing exactly one method.
- Content is served by `WebViewAssetLoader` over
  `https://appassets.androidplatform.net`. Never `file://`.
- Origin allowlist is explicit and passed at build time. No wildcards.
- `available()` is derived from the registry, never hardcoded. An unregistered
  capability rejects `UNAVAILABLE`; it is never a silent no-op stub.
- Settings lockdown per spec 01. Debugging only under `BuildConfig.DEBUG`.

## Structure

```
ac.onyx.permetic
  PermeticController        public entry point, built via PermeticController.Builder
  PermeticWebViewClient     asset loading, navigation policy
  transport/                envelope encode/decode, correlation, cancellation
  capability/               capability interfaces, generated from the contract
  internal/                 nothing public, no stable API guarantee
```

## Rules

- Keep Android types out of `transport/` so it tests on the JVM.
- The Activity is held through a nullable weak binding, set in `onCreate` and
  cleared in `onDestroy`. Capabilities needing it return `UNAVAILABLE` when absent.
- In-flight requests resolve `CANCELLED` on teardown. Never dropped silently.
- Subscriptions are keyed by subscription id and re-attached across configuration
  changes, not recreated.
- Instrumented tests drive the real global from a fixture page in
  `androidTest/assets/`. Asserting against a mocked `permetic` object tests nothing.
