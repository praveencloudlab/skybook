import { API_BASE_URL } from './lib/config';

/**
 * Scaffold placeholder (FRONTEND_MODULE.md build-order step 2).
 *
 * Deliberately minimal: it exists to prove the toolchain (React + TypeScript +
 * Tailwind + the config module) renders and type-checks. Real screens arrive
 * from step 5, once the API client and the polling hook they all depend on are
 * in place.
 */
export default function App() {
  return (
    <main className="mx-auto max-w-3xl px-6 py-16">
      <p className="text-xs font-semibold tracking-widest text-brand-600 uppercase">
        SkyBook
      </p>
      <h1 className="mt-2 text-3xl font-semibold tracking-tight text-slate-900">
        Frontend scaffold
      </h1>
      <p className="mt-4 text-slate-600">
        Vite, React, TypeScript and Tailwind are wired up. Screens land from
        build-order step 5, after the API client and the polling hook.
      </p>

      <dl className="mt-8 divide-y divide-slate-200 border-y border-slate-200 text-sm">
        <div className="flex justify-between gap-4 py-3">
          <dt className="text-slate-500">API gateway</dt>
          <dd className="tabular font-medium text-slate-900">{API_BASE_URL}</dd>
        </div>
        <div className="flex justify-between gap-4 py-3">
          <dt className="text-slate-500">Dev server</dt>
          <dd className="tabular font-medium text-slate-900">localhost:5173</dd>
        </div>
      </dl>
    </main>
  );
}
