import { describe, expect, it } from 'vitest';
import { Project } from 'ts-morph';
import path from 'node:path';
import manifest from '../contract/manifest.json';

/**
 * TS-side half of the contract-freeze parity check. Both this test and
 * `ContractParityTest.kt` (JVM) assert against the same
 * `packages/permetic-web/contract/manifest.json` — change `index.d.ts` alone, or the
 * Kotlin mirror alone, without updating the manifest, and the side that didn't
 * change fails its own test. See spec 01, task 1.
 */

const project = new Project();
const sourceFile = project.addSourceFileAtPath(
  path.resolve(import.meta.dirname, '../src/index.d.ts'),
);

function getCapabilityNames(): string[] {
  const typeAlias = sourceFile.getTypeAliasOrThrow('CapabilityName');
  return typeAlias
    .getType()
    .getUnionTypes()
    .map((t) => t.getLiteralValueOrThrow() as string);
}

function getMethodNames(interfaceName: string): string[] {
  return sourceFile
    .getInterfaceOrThrow(interfaceName)
    .getMethods()
    .map((m) => m.getName());
}

const interfaceByCapability: Record<string, string> = {
  auth: 'AuthCapability',
  push: 'PushCapability',
  billing: 'BillingCapability',
  background: 'BackgroundCapability',
  storage: 'StorageCapability',
  system: 'SystemCapability',
};

describe('contract parity: index.d.ts vs manifest.json', () => {
  it('CapabilityName union matches the manifest keys', () => {
    expect(new Set(getCapabilityNames())).toEqual(new Set(Object.keys(manifest)));
  });

  it('every manifest key has a matching *Capability interface mapped', () => {
    expect(new Set(Object.keys(manifest))).toEqual(new Set(Object.keys(interfaceByCapability)));
  });

  for (const [capability, interfaceName] of Object.entries(interfaceByCapability)) {
    it(`${capability} methods match the manifest`, () => {
      const methods = getMethodNames(interfaceName);
      const expected = (manifest as Record<string, string[]>)[capability];
      expect(new Set(methods)).toEqual(new Set(expected));
    });
  }
});
