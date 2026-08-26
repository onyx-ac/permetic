// @vitest-environment jsdom
import { describe, expect, it } from 'vitest';
import { PermeticError } from '../src/runtime';
import { createMockPermetic } from '../src/mock';

describe('createMockPermetic', () => {
  it('builds every capability with reasonable defaults and available() true by default', async () => {
    const permetic = createMockPermetic();

    expect(permetic.host).toBe('webview');
    expect(permetic.available('push')).toBe(true);
    expect(permetic.available('storage')).toBe(true);
    expect(permetic.push).toBeDefined();
    expect(permetic.billing).toBeDefined();
    expect(permetic.background).toBeDefined();

    const info = await permetic.system.info();
    expect(info.appVersion).toBe('0.0.0-mock');
    expect(await permetic.auth.account()).toBeNull();
    expect(await permetic.push?.permissionState()).toBe('prompt');
    expect(await permetic.billing?.queryProducts(['sku'], 'inapp')).toEqual([]);
    expect(await permetic.background?.status('job-1')).toBeNull();
  });

  it('rejects with a PermeticError for methods that need a real backing implementation', async () => {
    const permetic = createMockPermetic();

    await expect(permetic.push?.getToken()).rejects.toBeInstanceOf(PermeticError);
    await expect(permetic.billing?.purchase('sku')).rejects.toBeInstanceOf(PermeticError);
  });

  it('reports auth as unsupported and sign-in as dismissed, rather than throwing', async () => {
    // With no host there is genuinely no Google provider, and "the user did not sign
    // in" is the honest answer - it is also the shape every call site already handles,
    // unlike a thrown error (spec 08).
    const permetic = createMockPermetic();

    expect(await permetic.auth.supported()).toBe(false);
    expect(await permetic.auth.signIn()).toBeNull();
    expect(await permetic.auth.authorize(['drive'])).toBeNull();
  });

  it('lets per-method overrides punch through while leaving the rest of the capability alone', async () => {
    const permetic = createMockPermetic({
      auth: {
        signIn: async () => 'fake-id-token',
      },
    });

    expect(await permetic.auth.signIn()).toBe('fake-id-token');
    // Untouched methods on the same capability keep their default behavior.
    expect(await permetic.auth.account()).toBeNull();
  });

  it('lets available() be overridden to test the available()===false branch', () => {
    const permetic = createMockPermetic({ available: (capability) => capability !== 'billing' });

    expect(permetic.available('push')).toBe(true);
    expect(permetic.available('billing')).toBe(false);
    // The mock still builds a billing object regardless - only available() differs
    // from the real runtime, per its own doc comment.
    expect(permetic.billing).toBeDefined();
  });

  it('subscription methods return a no-op Unsubscribe', () => {
    const permetic = createMockPermetic();
    const unsubscribe = permetic.auth.onAccountChange(() => {});

    expect(() => unsubscribe()).not.toThrow();
  });
});
