/**
 * permetic — the contract for scoped native access from a web app.
 *
 * Any change here MUST be mirrored on the Kotlin side in the same commit. The
 * dispatcher is generated from this file; drift is a build failure.
 */

export const CONTRACT_VERSION = 1;

export type HostKind = 'webview' | 'headless';

export type CapabilityName =
  | 'auth'
  | 'push'
  | 'billing'
  | 'background'
  | 'storage'
  | 'system';

/* -------------------------------------------------------------------------- */
/* Transport                                                                   */
/* -------------------------------------------------------------------------- */

export interface BridgeRequest {
  v: typeof CONTRACT_VERSION;
  /** Correlation id, unique per in-flight request. */
  id: string;
  capability: CapabilityName;
  method: string;
  args: readonly unknown[];
}

export type BridgeResponse =
  | { v: 1; id: string; ok: true; value: unknown }
  | { v: 1; id: string; ok: false; error: BridgeError };

/** Unsolicited native -> JS message. */
export interface BridgeEvent {
  v: typeof CONTRACT_VERSION;
  capability: CapabilityName;
  subscription: string;
  payload: unknown;
}

/**
 * The only host-specific code in the system. WebView: WebMessageListener.
 * Headless: one bound Zipline suspending function. Everything above is identical.
 *
 * `subscribeChanges` can't cross as a Promise<BridgeResponse> - it's a live push
 * subscription, not a request/response call - so it isn't dispatched through the
 * envelope the way every other StorageCapability method is. It's attached directly
 * on the carrier object instead, mirroring StorageCapability's own split between
 * envelope-dispatched methods and the three direct passthroughs
 * (subscribeChanges/getAttachment/putAttachment).
 */
export interface Carrier {
  (req: BridgeRequest): Promise<BridgeResponse>;
  subscribeChanges?(db: string, since: number, listener: (c: StoredDoc) => void): Unsubscribe;
}

export type BridgeErrorCode =
  | 'UNAVAILABLE'
  | 'NOT_FOUND'
  | 'CONFLICT'
  | 'UNAUTHENTICATED'
  | 'PERMISSION_DENIED'
  | 'CANCELLED'
  | 'NETWORK'
  | 'INVALID_ARGUMENT'
  | 'INTERNAL';

export interface BridgeError {
  code: BridgeErrorCode;
  message: string;
  /** Never contains a stack trace in release builds. */
  details?: Record<string, unknown>;
}

export type Unsubscribe = () => void;

/* -------------------------------------------------------------------------- */
/* Root                                                                        */
/* -------------------------------------------------------------------------- */

export interface Permetic {
  readonly host: HostKind;
  readonly contractVersion: number;

  /**
   * Feature detection. ALWAYS call before use. Truthful per-app, not per-host:
   * false if the capability artifact was not registered by the embedding app.
   */
  available(capability: CapabilityName): boolean;

  readonly system: SystemCapability;
  readonly auth: AuthCapability;
  readonly push?: PushCapability;
  readonly billing?: BillingCapability;
  readonly background?: BackgroundCapability;
  /** Consumed by @docstack/pouchdb-adapter-native, not by application code. */
  readonly storage?: StorageCapability;
}

declare global {
  interface Window {
    permetic: Permetic;
  }
  /** Also a bare global — the headless host has no `window`. */
  const permetic: Permetic;
}

/* -------------------------------------------------------------------------- */
/* auth                                                                        */
/* -------------------------------------------------------------------------- */

/**
 * See spec 08. Google refuses OAuth from an embedded WebView by policy
 * (`disallowed_useragent`), so sign-in happens natively and only the result crosses.
 *
 * Deliberately **stateless**: nothing here is cached. A capability that quietly holds
 * tokens becomes a second source of truth about who is signed in, competing with the
 * page's own session. Expiry belongs to the page; when a token dies, ask again.
 *
 * Identity and authorization are separate on purpose. `signIn` asks for identity and
 * nothing else; feature scopes are requested by `authorize` when the user switches
 * that feature on, so one refusal costs one feature rather than the account.
 */
export interface AuthCapability {
  /** Whether this device can do any of this — Play Services is not everywhere. */
  supported(): Promise<boolean>;

  /**
   * A Google ID token for Firebase `signInWithCredential`.
   *
   * Resolves to **null when the user dismissed the chooser**. That is an outcome, not
   * an error: dismissal is the most common result after success, and throwing makes
   * every call site wrap it in a try/catch that swallows a normal interaction.
   *
   * `nonce` is optional but recommended — pass a random value and check it comes back
   * in the token's claims, so a token captured elsewhere cannot be replayed here.
   */
  signIn(options?: { nonce?: string }): Promise<string | null>;

  /** Short-lived access token for Google APIs. Null if the user refused. */
  authorize(scopes: readonly string[]): Promise<AuthorizationResult | null>;

  /**
   * A one-time server auth code — the only route to a refresh token, exchanged by the
   * app's own service. There is deliberately no member returning a refresh token: a
   * credential that lives until revoked must never be inside the WebView.
   */
  authorizeOffline(scopes: readonly string[]): Promise<string | null>;

  /** What is actually held right now; people revoke at myaccount.google.com. */
  grantedScopes(): Promise<readonly string[]>;

  revoke(scopes?: readonly string[]): Promise<void>;
  signOut(): Promise<void>;
  account(): Promise<{ id: string; email?: string } | null>;

  /**
   * The signed-in Google account changed. Worth subscribing to even though the page
   * initiates most changes: the account can also be removed on the device or revoked
   * at myaccount.google.com, neither of which the page can observe for itself.
   */
  onAccountChange(listener: (id: string | null) => void): Unsubscribe;
}

export interface AuthorizationResult {
  accessToken: string;
  /** Epoch millis, or null when the provider does not report one. */
  expiresAt: number | null;
  /** What was actually granted — consent can be partial, and the app must learn which. */
  grantedScopes: readonly string[];
}

/* -------------------------------------------------------------------------- */
/* push — webview only                                                         */
/* -------------------------------------------------------------------------- */

export interface PushMessage {
  messageId: string;
  data: Record<string, string>;
  notification?: { title?: string; body?: string };
  sentAt?: number;
}

export interface PushCapability {
  /** Requests POST_NOTIFICATIONS on Android 13+. */
  requestPermission(): Promise<'granted' | 'denied'>;
  permissionState(): Promise<'granted' | 'denied' | 'prompt'>;
  getToken(): Promise<string>;
  onToken(listener: (token: string) => void): Unsubscribe;
  onMessage(listener: (msg: PushMessage) => void): Unsubscribe;
  /** The notification that launched the app, if any. Consumed once. */
  initialMessage(): Promise<PushMessage | null>;
}

/* -------------------------------------------------------------------------- */
/* billing — webview only                                                      */
/* -------------------------------------------------------------------------- */

export interface Product {
  id: string;
  type: 'inapp' | 'subs';
  title: string;
  description: string;
  /** Display only. Never do arithmetic on this. */
  formattedPrice: string;
  priceMicros: number;
  currencyCode: string;
  offerToken?: string;
}

export interface Purchase {
  purchaseToken: string;
  productIds: readonly string[];
  state: 'purchased' | 'pending';
  acknowledged: boolean;
  purchasedAt: number;
}

export interface BillingCapability {
  queryProducts(ids: readonly string[], type: 'inapp' | 'subs'): Promise<Product[]>;
  /** Launches the Play purchase sheet. Rejects CANCELLED if dismissed. */
  purchase(productId: string, offerToken?: string): Promise<Purchase>;
  /** Entitlements owned now. Verify server-side before granting anything. */
  queryPurchases(): Promise<Purchase[]>;
  acknowledge(purchaseToken: string): Promise<void>;
  consume(purchaseToken: string): Promise<void>;
  onPurchaseUpdate(listener: (purchases: readonly Purchase[]) => void): Unsubscribe;
}

/* -------------------------------------------------------------------------- */
/* background — schedules the native sync worker                               */
/* -------------------------------------------------------------------------- */

export interface BackgroundJobSpec {
  /** Stable id. Re-registering the same id replaces the schedule. */
  id: string;
  /** Android floors periodic work at 15 minutes. */
  everyMinutes: number;
  requiresNetwork?: boolean;
  requiresCharging?: boolean;
  input?: Record<string, string>;
}

export interface BackgroundJobStatus {
  id: string;
  state: 'enqueued' | 'running' | 'succeeded' | 'failed' | 'cancelled';
  lastRunAt?: number;
  lastError?: BridgeError;
}

export interface BackgroundCapability {
  schedule(spec: BackgroundJobSpec): Promise<void>;
  cancel(id: string): Promise<void>;
  status(id: string): Promise<BackgroundJobStatus | null>;
  onStatusChange(id: string, listener: (s: BackgroundJobStatus) => void): Unsubscribe;
}

/* -------------------------------------------------------------------------- */
/* storage — the document store                                                */
/*                                                                             */
/* Consumed only by @docstack/pouchdb-adapter-native. Native stores revision    */
/* trees as OPAQUE blobs and never parses them: merge semantics live in         */
/* pouchdb-merge, in JS. See ADR-0001.                                          */
/* -------------------------------------------------------------------------- */

export interface StoreInfo {
  docCount: number;
  updateSeq: number;
}

/** A revision tree as PouchDB serialised it. Native treats this as bytes. */
export type OpaqueRevTree = string;

export interface StoredDoc {
  id: string;
  rev: string;
  seq: number;
  deleted: boolean;
  /** Absent when the body was compacted away or the doc is deleted. */
  body?: Record<string, unknown>;
}

export interface RevTreeEntry {
  id: string;
  tree: OpaqueRevTree | null;
  winningRev: string | null;
  seq: number;
  deleted: boolean;
}

export interface WriteOp {
  id: string;
  rev: string;
  /** Full tree after the JS-side merge. Replaces whatever is stored. */
  tree: OpaqueRevTree;
  winningRev: string;
  deleted: boolean;
  body?: Record<string, unknown>;
  /** Digests referenced by this revision. Native maintains refcounts. */
  attachmentDigests?: readonly string[];
  /**
   * The document's winning rev when the JS-side merge that produced [tree] was
   * computed, or omitted if the caller believed the document didn't exist yet.
   * bulkWrite rejects (per-op, not batch-wide) an op whose current winning rev has
   * since moved on from this - a lost race between two concurrent writers to the
   * same id, rather than the second writer's merge silently overwriting the first's.
   */
  expectedPrevWinningRev?: string;
}

export interface WriteResult {
  id: string;
  rev: string;
  seq: number;
}

export interface AllDocsOptions {
  startkey?: string;
  endkey?: string;
  inclusiveEnd?: boolean;
  keys?: readonly string[];
  limit?: number;
  skip?: number;
  descending?: boolean;
  includeBody?: boolean;
  /** Include non-winning leaf revisions so JS can report conflicts. */
  includeConflicts?: boolean;
  deleted?: boolean;
}

export interface AllDocsResult {
  totalRows: number;
  offset: number;
  rows: ReadonlyArray<StoredDoc & { conflicts?: readonly string[] }>;
}

export interface ChangesOptions {
  since?: number;
  limit?: number;
  descending?: boolean;
  includeBody?: boolean;
  docIds?: readonly string[];
}

export interface ChangesResult {
  lastSeq: number;
  results: readonly StoredDoc[];
}

export interface BulkGetRequest {
  id: string;
  rev?: string;
}

export interface StorageCapability {
  info(db: string): Promise<StoreInfo>;

  /** rev omitted -> winning revision. */
  getDoc(db: string, id: string, rev?: string): Promise<StoredDoc>;

  /** Read phase of _bulkDocs. One crossing for the whole batch. */
  getRevTrees(db: string, ids: readonly string[]): Promise<RevTreeEntry[]>;

  /**
   * Write phase of _bulkDocs. One crossing: ops whose WriteOp.expectedPrevWinningRev
   * still matches the document's current winning rev commit together, as one atomic
   * transaction. Any op that's gone stale (a concurrent writer got there first) is
   * skipped and reported as null at that position - positionally aligned with [ops]
   * - rather than failing the whole call. Matches CouchDB's own per-doc
   * partial-failure _bulkDocs semantics.
   */
  bulkWrite(db: string, ops: readonly WriteOp[]): Promise<Array<WriteResult | null>>;

  allDocs(db: string, options: AllDocsOptions): Promise<AllDocsResult>;
  changes(db: string, options: ChangesOptions): Promise<ChangesResult>;
  subscribeChanges(db: string, since: number,
                   listener: (c: StoredDoc) => void): Unsubscribe;

  /**
   * Overridden rather than emulated by PouchDB core: replication calls these
   * constantly and the default implementations are O(n) round trips.
   */
  revsDiff(db: string, map: Record<string, readonly string[]>):
    Promise<Record<string, { missing: string[]; possibleAncestors?: string[] }>>;
  bulkGet(db: string, requests: readonly BulkGetRequest[]): Promise<StoredDoc[]>;

  /** Deletes bodies for the given revisions and rewrites the stored tree. */
  compact(db: string, id: string, revs: readonly string[],
          tree: OpaqueRevTree): Promise<void>;

  /** null when no such local doc exists. Includes [rev] - PouchDB's `_getLocal`
   *  callback must report `_rev` so a subsequent put can carry it forward. */
  getLocal(db: string, id: string):
    Promise<{ rev: string; body: Record<string, unknown> } | null>;
  /**
   * [prevRev] omitted -> the doc must not already exist. Throws CONFLICT if
   * [prevRev] doesn't match the currently stored rev (including "doc doesn't
   * exist but a rev was expected"). Returns the new rev, formatted "0-N".
   */
  putLocal(db: string, id: string, doc: Record<string, unknown>,
           prevRev?: string): Promise<string>;
  /**
   * Throws CONFLICT if [prevRev] doesn't match the currently stored rev,
   * NOT_FOUND if no such local doc exists at all.
   */
  removeLocal(db: string, id: string, prevRev: string): Promise<void>;

  /**
   * Attachment bodies use a binary side-channel, not the JSON envelope:
   * base64 costs ~33% on payloads that can be megabytes.
   * WebView -> WebMessageCompat byte array. Headless -> ByteArray over Zipline.
   */
  getAttachment(db: string, digest: string): Promise<Uint8Array>;
  putAttachment(db: string, digest: string, data: Uint8Array): Promise<void>;

  destroy(db: string): Promise<void>;
  close(db: string): Promise<void>;
}

/* -------------------------------------------------------------------------- */
/* system                                                                      */
/* -------------------------------------------------------------------------- */

export interface SystemCapability {
  info(): Promise<{
    host: HostKind;
    appVersion: string;
    appVersionCode: number;
    osVersion: number;
    locale: string;
    lifecycle: 'foreground' | 'background';
  }>;
  log(level: 'debug' | 'info' | 'warn' | 'error', message: string): void;
  share(payload: { title?: string; text?: string; url?: string }): Promise<void>;
  openUrl(url: string): Promise<void>;
  onLifecycle(listener: (s: 'foreground' | 'background') => void): Unsubscribe;
}
