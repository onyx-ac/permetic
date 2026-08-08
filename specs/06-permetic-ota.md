# 06 — permetic-ota: OTA publishing CLI

Status: **draft, concept-level** · Owner: Onyx
Modules: `permetic-ota` (new, npm)

## Goal

A Node.js CLI, published as npm `permetic-ota`, that builds, signs, and publishes OTA
bundles of the web app for the on-device resolver to pick up. It is the write side of
the OTA mechanism spec 01 describes; spec 01 owns the read/verify/atomic-swap side on
the device. This spec stays conceptual — surface and data shapes to guide the eventual
implementation, not a locked contract.

## Non-goals

- Building or bundling the web app itself. `permetic-ota` packages an already-built
  output directory (`dist/` or equivalent); it doesn't run webpack/vite/whatever the
  web app uses.
- Server-side rollout orchestration — staged percentage rollout, a kill-switch
  dashboard. Out of scope for v1; the CLI publishes one bundle to one channel at a
  time. Revisit as a hosted service only if a real need shows up.
- Owning the distribution transport. `publish` targets object storage
  (S3/GCS/equivalent) through a pluggable adapter, not a bespoke server.
- iOS. Consistent with the rest of Permetic — the contract allows a host later, not
  built now.

## What it produces

A **bundle** = the web app's static output plus a signed manifest, laid out exactly as
`WebViewAssetLoader` will serve it:

```
bundle/
  manifest.json      # {contractVersion, bundleVersion, files: [...], signature}
  <web app files>     # unchanged tree, this is what gets served
```

`manifest.json` (concept, not final):

- `contractVersion` — must equal `permetic-web`'s `CONTRACT_VERSION` at build time. The
  CLI refuses to publish a mismatch; spec 01 makes this handshake load-bearing on the
  device, so catching it earlier at publish time is cheap insurance, not the safety
  boundary itself.
- `bundleVersion` — monotonic, CLI-assigned.
- `files` — every shipped file's digest, so the on-device verifier can check the whole
  tree before the staging directory becomes eligible to go live (spec 01: "verify
  before the staging directory is eligible to become live").
- `signature` — over the manifest as a whole (file list + both versions), not
  per-file. One signature to verify at boot, not one per asset.

## CLI surface (concept)

```
permetic-ota build    <dist-dir> --contract-version <n> --out <bundle-dir>
permetic-ota sign     <bundle-dir> --key <path-or-kms-ref>
permetic-ota publish  <bundle-dir> --channel <name> --target <s3://...|gcs://...>
permetic-ota rollback --channel <name> --to <bundleVersion>
permetic-ota status   --channel <name>
```

- **Channels** exist so a debug/staging bundle can diverge from production without a
  second CLI or a second repo.
- **`rollback`** republishes a pointer to a previously-signed bundle; it never
  re-signs. Re-signing an old tree under current key material would be
  indistinguishable, from the device's point of view, from a compromise producing a
  forged "old" bundle.
- **`publish`** is the only subcommand that touches the network. `build` and `sign`
  are local/CI steps, so signing key material and publish credentials don't have to
  sit in the same environment or the same hands.

## Signing model

Spec 01 (D-4) leaves open whether OTA signing reuses the app's Play signing identity or
a separate content key. `permetic-ota` is written key-agnostic: `sign` takes a signer
interface — a raw private key file for local dev, a KMS/HSM reference for production —
not an assumption about which key wins. That's deliberate: it's the CLI that should
absorb whichever way D-4 resolves, so the decision doesn't ripple into the on-device
verifier's contract, which only ever needs a public key and a manifest signature
format.

## Relationship to the on-device resolver (spec 01)

1. `permetic-ota publish` pushes bundle + manifest to `--target`.
2. The app's update check — mechanism unspecified here; could be a WorkManager poll, a
   push-triggered wakeup, or an on-launch check — downloads the manifest, verifies the
   signature, verifies every file digest, and only then treats the staging directory as
   eligible. This mirrors spec 01's atomic-swap-with-rollback requirement; this spec
   does not re-litigate it.
3. `contractVersion` is checked twice, deliberately: `permetic-ota build` refuses to
   produce a bundle with a missing or stale version (catches mistakes at publish time),
   and the on-device resolver separately refuses to activate a bundle whose
   `contractVersion` outruns the installed native capabilities (the check that actually
   matters for safety — the CLI check is convenience, not the boundary).

## Open decisions

- **D-1** Where does update-check scheduling live — the `background` capability
  (WorkManager), a `permetic-push` wakeup, or both? Affects staleness bound, not this
  spec's surface.
- **D-2** One publish-target type at v1 (plain object storage), or does `publish` need
  a target-adapter interface from day one? Lean toward the interface now — retrofitting
  it once a wire format is baked into `publish` is the expensive path.
- **D-3** Does `rollback` need to be atomic across a device fleet, or is eventual
  consistency acceptable given the on-device fallback to the bundled APK version?
  Likely acceptable; record the reasoning here once decided.
- Inherits spec 01's **D-4** (signing key ownership) and **D-5** (next-launch-only vs.
  hot-swap-on-resume) — this spec doesn't resolve either, only documents where the CLI
  plugs into whichever answer they land on.
