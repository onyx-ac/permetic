# permetic-web

npm `permetic` — the capability contract (`src/index.d.ts`) and the JS runtime that
builds `window.permetic` from a `Carrier`, including `createMockPermetic()` for the web
app's browser-mode dev server.

`src/index.d.ts` is the source of truth for the bridge protocol. Any change here must be
mirrored on the Kotlin side in the same commit — see `../../CLAUDE.md`.

Read `../../specs/01-permetic-webview.md` (task 4) before changing anything here.
