/**
 * Package entry point. Named `main.ts`, not `index.ts`: this directory also has a
 * hand-written `index.d.ts` (the frozen contract), and `tsc` with `declaration: true`
 * would try to emit its own generated `dist/index.d.ts` from an `index.ts` here,
 * colliding with the frozen one. `export *` still pulls in `index.d.ts`'s `declare
 * global` block (Window.permetic) for anyone who imports from this package, since
 * ambient augmentations activate for any module transitively reachable from the
 * compilation, re-exported or not.
 */
export * from './index';
export { buildPermetic, CONTRACT_VERSION, PermeticError } from './runtime';
export { createMockPermetic } from './mock';
export type { MockPermeticOverrides } from './mock';
