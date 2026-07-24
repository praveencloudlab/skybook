/// <reference types="vite/client" />

/**
 * Typed environment variables (FRONTEND_MODULE.md §8).
 *
 * Declared so `import.meta.env.VITE_API_BASE_URL` is checked rather than being
 * an implicit `any` - a typo in an env var name should be a compile error, not a
 * runtime request to `undefined/api/...`.
 */
interface ImportMetaEnv {
  /** API gateway origin. Defaults to http://localhost:8080 when unset. */
  readonly VITE_API_BASE_URL?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
