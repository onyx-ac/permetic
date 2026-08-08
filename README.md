# permetic

Permetic runs a web app in an Android WebView and grants it scoped, declared access to
native features. The web app runs unchanged.

See [`CLAUDE.md`](./CLAUDE.md) for the module table, conventions, and workflow, and
[`specs/`](./specs) for the architecture and ADRs.

## Packages

- [`packages/permetic`](./packages/permetic) — WebView host (`ac.onyx.permetic:permetic-core`)
- [`packages/permetic-push`](./packages/permetic-push) — FCM capability, optional
- [`packages/permetic-billing`](./packages/permetic-billing) — Play Billing capability, optional
- [`packages/permetic-web`](./packages/permetic-web) — contract types + JS runtime (npm `permetic`)

DocStack's native storage side (`docstack-store`, `docstack-permetic`,
`docstack-headless`, `@docstack/pouchdb-adapter-native`) lives in the separate
[`docstack`](https://github.com/onyx-og/docstack) repo and consumes the contract
published from `packages/permetic-web`.
