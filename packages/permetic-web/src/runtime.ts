import type {
  AuthCapability,
  AuthorizationResult,
  BackgroundCapability,
  BackgroundJobSpec,
  BackgroundJobStatus,
  BillingCapability,
  BridgeError,
  BridgeEvent,
  BridgeRequest,
  BridgeResponse,
  Carrier,
  CapabilityName,
  HostKind,
  Permetic,
  Product,
  Purchase,
  PushCapability,
  PushMessage,
  SystemCapability,
  Unsubscribe,
} from './index';

/**
 * Mirrors `CONTRACT_VERSION` in `index.d.ts`. `index.d.ts` is a pure `.d.ts` file —
 * it emits no JS, so `CONTRACT_VERSION` there is a literal *type*, not an importable
 * runtime value. This constant has to be kept in sync by hand, the same way the
 * Kotlin side's `CONTRACT_VERSION` does (packages/permetic/.../transport/Envelope.kt).
 */
export const CONTRACT_VERSION = 1;

/** Thrown for every native-reported failure. `code` matches `BridgeErrorCode`. */
export class PermeticError extends Error {
  public readonly code: BridgeError['code'];
  public readonly details?: BridgeError['details'];

  constructor(error: BridgeError) {
    super(error.message);
    this.name = 'PermeticError';
    this.code = error.code;
    this.details = error.details;
  }
}

type SubscriptionMap = Map<string, (payload: unknown) => void>;

function newRequestId(): string {
  return crypto.randomUUID();
}

function unwrap<T>(response: BridgeResponse): T {
  if (response.v !== CONTRACT_VERSION) {
    throw new Error(
      `permetic: contract version mismatch (native sent v${response.v}, runtime expects v${CONTRACT_VERSION})`,
    );
  }
  if (!response.ok) {
    throw new PermeticError(response.error);
  }
  return response.value as T;
}

function call<T>(
  carrier: Carrier,
  capability: CapabilityName,
  method: string,
  args: readonly unknown[],
): Promise<T> {
  const request: BridgeRequest = {
    v: CONTRACT_VERSION,
    id: newRequestId(),
    capability,
    method,
    args,
  };
  return carrier(request).then((response) => unwrap<T>(response));
}

/**
 * Registers an `onXxx`-shaped subscription. The initial subscribe call goes through
 * the normal envelope like any other method, resolving with the subscription id
 * native allocated; that id is *not* part of the public contract, it's this
 * runtime's own bookkeeping key into [subscriptions]. Cancellation reuses the same
 * envelope with the reserved method name `"unsubscribe"` under the same capability —
 * a convention established here (not literally spelled out in `index.d.ts`, which
 * only names `subscribeChanges` as a carrier-level special case for storage) that
 * the native `Dispatcher` (spec 01 task 5) needs to honor for every capability that
 * has an `onXxx` method.
 */
function subscribe<TPayload>(
  carrier: Carrier,
  subscriptions: SubscriptionMap,
  capability: CapabilityName,
  method: string,
  args: readonly unknown[],
  listener: (payload: TPayload) => void,
): Unsubscribe {
  let subscriptionId: string | undefined;
  let unsubscribed = false;

  call<string>(carrier, capability, method, args).then((id) => {
    if (unsubscribed) {
      // Unsubscribe() ran before native finished registering this subscription —
      // cancel it immediately instead of leaking it.
      void call(carrier, capability, 'unsubscribe', [id]);
      return;
    }
    subscriptionId = id;
    subscriptions.set(id, (payload) => listener(payload as TPayload));
  });

  return () => {
    unsubscribed = true;
    if (subscriptionId !== undefined) {
      subscriptions.delete(subscriptionId);
      void call(carrier, capability, 'unsubscribe', [subscriptionId]);
    }
  };
}

function routeEvent(subscriptions: SubscriptionMap, event: BridgeEvent): void {
  subscriptions.get(event.subscription)?.(event.payload);
}

function buildSystem(carrier: Carrier, subscriptions: SubscriptionMap): SystemCapability {
  return {
    info: () => call(carrier, 'system', 'info', []),
    log: (level, message) => {
      call(carrier, 'system', 'log', [level, message]).catch(() => {});
    },
    share: (payload) => call(carrier, 'system', 'share', [payload]),
    openUrl: (url) => call(carrier, 'system', 'openUrl', [url]),
    onLifecycle: (listener) => subscribe(carrier, subscriptions, 'system', 'onLifecycle', [], listener),
  };
}

function buildAuth(carrier: Carrier, subscriptions: SubscriptionMap): AuthCapability {
  return {
    supported: () => call<boolean>(carrier, 'auth', 'supported', []),
    // The contract takes an options object for room to grow; the wire is positional,
    // so only the nonce crosses.
    signIn: (options) => call<string | null>(carrier, 'auth', 'signIn', [options?.nonce ?? null]),
    authorize: (scopes) => call<AuthorizationResult | null>(carrier, 'auth', 'authorize', [scopes]),
    authorizeOffline: (scopes) => call<string | null>(carrier, 'auth', 'authorizeOffline', [scopes]),
    grantedScopes: () => call<readonly string[]>(carrier, 'auth', 'grantedScopes', []),
    revoke: (scopes) => call<void>(carrier, 'auth', 'revoke', [scopes ?? []]),
    signOut: () => call<void>(carrier, 'auth', 'signOut', []),
    account: () => call(carrier, 'auth', 'account', []),
    onAccountChange: (listener) => subscribe(carrier, subscriptions, 'auth', 'onAccountChange', [], listener),
  };
}

function buildPush(carrier: Carrier, subscriptions: SubscriptionMap): PushCapability {
  return {
    requestPermission: () => call(carrier, 'push', 'requestPermission', []),
    permissionState: () => call(carrier, 'push', 'permissionState', []),
    getToken: () => call<string>(carrier, 'push', 'getToken', []),
    onToken: (listener) => subscribe(carrier, subscriptions, 'push', 'onToken', [], listener),
    onMessage: (listener) => subscribe(carrier, subscriptions, 'push', 'onMessage', [], listener),
    initialMessage: () => call<PushMessage | null>(carrier, 'push', 'initialMessage', []),
  };
}

function buildBilling(carrier: Carrier, subscriptions: SubscriptionMap): BillingCapability {
  return {
    queryProducts: (ids, type) => call<Product[]>(carrier, 'billing', 'queryProducts', [ids, type]),
    purchase: (productId, offerToken) => call<Purchase>(carrier, 'billing', 'purchase', [productId, offerToken]),
    queryPurchases: () => call<Purchase[]>(carrier, 'billing', 'queryPurchases', []),
    acknowledge: (purchaseToken) => call<void>(carrier, 'billing', 'acknowledge', [purchaseToken]),
    consume: (purchaseToken) => call<void>(carrier, 'billing', 'consume', [purchaseToken]),
    onPurchaseUpdate: (listener) =>
      subscribe(carrier, subscriptions, 'billing', 'onPurchaseUpdate', [], listener),
  };
}

function buildBackground(carrier: Carrier, subscriptions: SubscriptionMap): BackgroundCapability {
  return {
    schedule: (spec: BackgroundJobSpec) => call<void>(carrier, 'background', 'schedule', [spec]),
    cancel: (id) => call<void>(carrier, 'background', 'cancel', [id]),
    status: (id) => call<BackgroundJobStatus | null>(carrier, 'background', 'status', [id]),
    onStatusChange: (id, listener) =>
      subscribe(carrier, subscriptions, 'background', 'onStatusChange', [id], listener),
  };
}

/**
 * Builds `window.permetic` from an already-adapted [carrier]. Host-specific: the
 * WebView-side glue that turns the injected `window.PermeticNative` postMessage/
 * onmessage object into this shape (correlating replies to requests, and telling
 * [onEvent]-registered listeners apart from replies) is a separate, not-yet-built
 * piece — this function only assumes it already exists, per ADR-0002 ("the only
 * host-specific code in the system... Everything above is identical").
 *
 * [onEvent] registers a listener for unsolicited [BridgeEvent]s and returns an
 * unsubscribe for that registration; it's how this function receives subscription
 * push updates, since [Carrier] itself is just a request/response function with no
 * generic push channel (`subscribeChanges` on `Carrier` is a storage-only special
 * case, not a general mechanism).
 *
 * [availableCapabilities] is supplied explicitly rather than read from a global —
 * whatever constructs [carrier] is expected to have already learned this from
 * native (e.g. a bootstrap-injected JSON literal) and pass it straight through, so
 * this function stays a pure, fully unit-testable building block.
 */
export function buildPermetic(
  carrier: Carrier,
  onEvent: (listener: (event: BridgeEvent) => void) => Unsubscribe,
  host: HostKind,
  availableCapabilities: ReadonlySet<CapabilityName>,
): Permetic {
  const subscriptions: SubscriptionMap = new Map();
  onEvent((event) => routeEvent(subscriptions, event));

  const available = (capability: CapabilityName): boolean => availableCapabilities.has(capability);

  return {
    host,
    contractVersion: CONTRACT_VERSION,
    available,
    // system and auth forward every call through the envelope unconditionally,
    // regardless of availableCapabilities — if nothing is registered natively, the
    // call still goes out and the dispatcher is what returns UNAVAILABLE. This
    // runtime never stubs a capability itself; see spec 01 task 5's registry.
    system: buildSystem(carrier, subscriptions),
    auth: buildAuth(carrier, subscriptions),
    push: available('push') ? buildPush(carrier, subscriptions) : undefined,
    billing: available('billing') ? buildBilling(carrier, subscriptions) : undefined,
    background: available('background') ? buildBackground(carrier, subscriptions) : undefined,
    // storage is out of scope: this build never registers a storage capability (no
    // DocStack integration), so it's left undefined regardless of
    // availableCapabilities rather than building a StorageCapability forwarding
    // object nothing on the native side backs or tests yet.
  };
}
