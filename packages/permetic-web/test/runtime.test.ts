import { describe, expect, it } from 'vitest';
import type { BridgeError, BridgeEvent, BridgeRequest, BridgeResponse, Carrier } from '../src/index';
import { CONTRACT_VERSION, PermeticError, buildPermetic } from '../src/runtime';

interface Responder {
  matcher: (req: BridgeRequest) => boolean;
  respond: (req: BridgeRequest) => BridgeResponse;
}

function byMethod(capability: string, method: string) {
  return (req: BridgeRequest) => req.capability === capability && req.method === method;
}

function createFakeCarrier() {
  const responders: Responder[] = [];
  const requests: BridgeRequest[] = [];

  const carrier: Carrier = async (req) => {
    requests.push(req);
    const responder = responders.find((r) => r.matcher(req));
    if (!responder) {
      throw new Error(`no fake responder registered for ${req.capability}.${req.method}`);
    }
    return responder.respond(req);
  };

  return {
    carrier,
    requests,
    resolveWith(matcher: (req: BridgeRequest) => boolean, value: unknown) {
      responders.push({ matcher, respond: (req) => ({ v: CONTRACT_VERSION, id: req.id, ok: true, value }) });
    },
    rejectWith(matcher: (req: BridgeRequest) => boolean, error: BridgeError) {
      responders.push({ matcher, respond: (req) => ({ v: CONTRACT_VERSION, id: req.id, ok: false, error }) });
    },
  };
}

function noEvents() {
  return () => () => {};
}

describe('buildPermetic', () => {
  it('round-trips a call through the carrier', async () => {
    const fake = createFakeCarrier();
    fake.resolveWith(byMethod('system', 'info'), {
      host: 'webview',
      appVersion: '1.0.0',
      appVersionCode: 1,
      osVersion: 34,
      locale: 'en-US',
      lifecycle: 'foreground',
    });
    const permetic = buildPermetic(fake.carrier, noEvents(), 'webview', new Set(['system', 'auth']));

    const info = await permetic.system.info();

    expect(info.appVersion).toBe('1.0.0');
    expect(fake.requests[0]).toMatchObject({ v: CONTRACT_VERSION, capability: 'system', method: 'info' });
  });

  it('available("push") is false and permetic.push is undefined when omitted from the availability set', () => {
    const fake = createFakeCarrier();
    const permetic = buildPermetic(fake.carrier, noEvents(), 'webview', new Set(['system', 'auth']));

    expect(permetic.available('push')).toBe(false);
    expect(permetic.push).toBeUndefined();
  });

  it('available("push") is true and permetic.push is defined when included in the availability set', () => {
    const fake = createFakeCarrier();
    const permetic = buildPermetic(
      fake.carrier,
      noEvents(),
      'webview',
      new Set(['system', 'auth', 'push']),
    );

    expect(permetic.available('push')).toBe(true);
    expect(permetic.push).toBeDefined();
  });

  it('permetic.auth still exists and forwards even with nothing registered natively', async () => {
    const fake = createFakeCarrier();
    fake.rejectWith(byMethod('auth', 'signIn'), {
      code: 'UNAVAILABLE',
      message: 'no auth capability registered',
    });
    // available() intentionally omits 'auth' here too - auth is always built
    // regardless, per the non-optional Permetic.auth field in the contract.
    const permetic = buildPermetic(fake.carrier, noEvents(), 'webview', new Set());

    expect(permetic.auth).toBeDefined();
    await expect(permetic.auth.signIn()).rejects.toBeInstanceOf(PermeticError);
    await expect(permetic.auth.signIn()).rejects.toMatchObject({ code: 'UNAVAILABLE' });
  });

  it('routes a subscription event to the registered listener and stops after Unsubscribe()', async () => {
    const fake = createFakeCarrier();
    fake.resolveWith(byMethod('auth', 'onAccountChange'), 'sub-1');
    fake.resolveWith(byMethod('auth', 'unsubscribe'), null);

    let capturedListener: ((event: BridgeEvent) => void) | undefined;
    const onEvent = (listener: (event: BridgeEvent) => void) => {
      capturedListener = listener;
      return () => {
        capturedListener = undefined;
      };
    };

    const permetic = buildPermetic(fake.carrier, onEvent, 'webview', new Set(['auth']));
    const received: Array<string | null> = [];
    const unsubscribe = permetic.auth.onAccountChange((id) => received.push(id));

    // The subscription id resolves asynchronously - flush the microtask queue.
    await Promise.resolve();
    await Promise.resolve();

    capturedListener?.({ v: CONTRACT_VERSION, capability: 'auth', subscription: 'sub-1', payload: 'user-1' });
    expect(received).toEqual(['user-1']);

    unsubscribe();
    await Promise.resolve();
    await Promise.resolve();

    capturedListener?.({ v: CONTRACT_VERSION, capability: 'auth', subscription: 'sub-1', payload: 'user-2' });
    expect(received).toEqual(['user-1']);

    const unsubscribeRequest = fake.requests.find((r) => r.method === 'unsubscribe');
    expect(unsubscribeRequest?.args).toEqual(['sub-1']);
  });

  it('cancels a subscription immediately if Unsubscribe() runs before the subscribe call resolves', async () => {
    const fake = createFakeCarrier();
    fake.resolveWith(byMethod('system', 'onLifecycle'), 'sub-2');
    fake.resolveWith(byMethod('system', 'unsubscribe'), null);

    const permetic = buildPermetic(fake.carrier, noEvents(), 'webview', new Set(['system', 'auth']));
    const unsubscribe = permetic.system.onLifecycle(() => {});
    unsubscribe();

    await Promise.resolve();
    await Promise.resolve();

    const unsubscribeRequest = fake.requests.find((r) => r.method === 'unsubscribe');
    expect(unsubscribeRequest?.args).toEqual(['sub-2']);
  });

  it('throws a clear error on a contract version mismatch instead of silently misinterpreting the response', async () => {
    const carrier: Carrier = async (req) =>
      ({ v: 99, id: req.id, ok: true, value: 'whatever' }) as unknown as BridgeResponse;
    const permetic = buildPermetic(carrier, noEvents(), 'webview', new Set(['system', 'auth']));

    await expect(permetic.auth.signOut()).rejects.toThrow(/contract version mismatch/);
  });
});
