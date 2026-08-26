import type {
  AuthCapability,
  BackgroundCapability,
  BillingCapability,
  CapabilityName,
  HostKind,
  Permetic,
  PushCapability,
  SystemCapability,
  Unsubscribe,
} from './index';
import { CONTRACT_VERSION, PermeticError } from './runtime';

export interface MockPermeticOverrides {
  host?: HostKind;
  available?: (capability: CapabilityName) => boolean;
  system?: Partial<SystemCapability>;
  auth?: Partial<AuthCapability>;
  push?: Partial<PushCapability>;
  billing?: Partial<BillingCapability>;
  background?: Partial<BackgroundCapability>;
}

const noSubscription = (): Unsubscribe => () => {};

function unavailable(what: string): PermeticError {
  return new PermeticError({
    code: 'UNAVAILABLE',
    message: `createMockPermetic(): no ${what} override supplied`,
  });
}

function defaultSystem(): SystemCapability {
  return {
    info: async () => ({
      host: 'webview',
      appVersion: '0.0.0-mock',
      appVersionCode: 0,
      osVersion: 0,
      locale: 'en-US',
      lifecycle: 'foreground',
    }),
    log: (level, message) => {
      console[level === 'debug' ? 'log' : level](`[permetic mock] ${message}`);
    },
    share: async () => {},
    openUrl: async (url) => {
      if (typeof window !== 'undefined') window.open(url, '_blank');
    },
    onLifecycle: () => noSubscription(),
  };
}

function defaultAuth(): AuthCapability {
  return {
    // The mock exists so the app runs with no host at all, so "supported" is false
    // and sign-in returns null - the same shape as a user dismissing the chooser,
    // which every call site already has to handle.
    supported: async () => false,
    signIn: async () => null,
    authorize: async () => null,
    authorizeOffline: async () => null,
    grantedScopes: async () => [],
    revoke: async () => {},
    signOut: async () => {},
    account: async () => null,
    onAccountChange: () => noSubscription(),
  };
}

function defaultPush(): PushCapability {
  return {
    requestPermission: async () => 'denied',
    permissionState: async () => 'prompt',
    getToken: async () => {
      throw unavailable('push');
    },
    onToken: () => noSubscription(),
    onMessage: () => noSubscription(),
    initialMessage: async () => null,
  };
}

function defaultBilling(): BillingCapability {
  return {
    queryProducts: async () => [],
    purchase: async () => {
      throw unavailable('billing');
    },
    queryPurchases: async () => [],
    acknowledge: async () => {},
    consume: async () => {},
    onPurchaseUpdate: () => noSubscription(),
  };
}

function defaultBackground(): BackgroundCapability {
  return {
    schedule: async () => {},
    cancel: async () => {},
    status: async () => null,
    onStatusChange: () => noSubscription(),
  };
}

/**
 * A standalone `Permetic` for the web app's browser-mode dev server — no Android
 * host, no carrier. Every method has a reasonable no-op or rejecting default (never
 * `UNAVAILABLE`-by-construction the way an unregistered native capability is: the
 * mock's whole purpose is letting the app run standalone, which is the opposite
 * intent of that rule), overridable per-method via [overrides].
 *
 * `available()` defaults to always `true` — unlike the real runtime, where it
 * reflects native's registry, a dev exercising UI code paths locally usually wants
 * every capability object present. Override it to test `available() === false`
 * branches.
 */
export function createMockPermetic(overrides: MockPermeticOverrides = {}): Permetic {
  return {
    host: overrides.host ?? 'webview',
    contractVersion: CONTRACT_VERSION,
    available: overrides.available ?? (() => true),
    system: { ...defaultSystem(), ...overrides.system },
    auth: { ...defaultAuth(), ...overrides.auth },
    push: { ...defaultPush(), ...overrides.push },
    billing: { ...defaultBilling(), ...overrides.billing },
    background: { ...defaultBackground(), ...overrides.background },
  };
}
