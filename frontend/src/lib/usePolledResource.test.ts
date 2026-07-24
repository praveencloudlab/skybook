import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { usePolledResource } from './usePolledResource';
import { ApiError } from './errors';

/**
 * This hook is the one piece three screens depend on, and the one most likely to
 * hide a subtle bug - so the awkward cases are tested explicitly: the rate-limit
 * budget, the difference between "gave up" and "broke", and the two statuses
 * that legitimately mean "not yet".
 */
describe('usePolledResource', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  /** Advance timers inside act so React processes the resulting state updates. */
  async function advance(ms: number) {
    await act(async () => {
      await vi.advanceTimersByTimeAsync(ms);
    });
  }

  it('resolves as soon as the resource appears', async () => {
    const fetch = vi.fn().mockResolvedValue({ id: 1 });
    const { result } = renderHook(() => usePolledResource({ fetch, enabled: true }));

    await advance(0);
    expect(result.current.status).toBe('ready');
    expect(result.current.data).toEqual({ id: 1 });
    expect(fetch).toHaveBeenCalledTimes(1);
  });

  it('keeps polling while the resource is absent, then resolves', async () => {
    // Exactly the Kafka case: the consumer has not created the row yet.
    const fetch = vi
      .fn()
      .mockResolvedValueOnce(null)
      .mockResolvedValueOnce(null)
      .mockResolvedValue({ id: 7 });

    const { result } = renderHook(() => usePolledResource({ fetch, enabled: true }));

    await advance(1_000); // first backoff
    await advance(2_000); // second
    await advance(0);
    expect(result.current.status).toBe('ready');

    expect(result.current.data).toEqual({ id: 7 });
    expect(fetch).toHaveBeenCalledTimes(3);
  });

  it('treats 404 and 403 as "not yet", not as errors', async () => {
    // Before the consumer creates the row the resource is genuinely absent, and
    // an ownership-scoped endpoint answers 403 for something it cannot find to
    // own. Surfacing either as an error would show a scary message during a
    // perfectly normal wait.
    const fetch = vi
      .fn()
      .mockRejectedValueOnce(new ApiError('notFound', 404, 'nope'))
      .mockRejectedValueOnce(new ApiError('forbidden', 403, 'not yours'))
      .mockResolvedValue({ id: 3 });

    const { result } = renderHook(() => usePolledResource({ fetch, enabled: true }));

    await advance(1_000);
    await advance(2_000);
    await advance(0);
    expect(result.current.status).toBe('ready');

    expect(result.current.error).toBeNull();
  });

  it('fails fast on a real error instead of retrying it', async () => {
    // A 500 will not fix itself by asking again; retrying only delays the truth.
    const fetch = vi.fn().mockRejectedValue(new ApiError('server', 500, 'boom'));
    const { result } = renderHook(() => usePolledResource({ fetch, enabled: true }));

    await advance(0);
    expect(result.current.status).toBe('failed');
    expect(result.current.error?.status).toBe(500);
    expect(fetch).toHaveBeenCalledTimes(1);
  });

  it('reports timedOut - distinct from failed - when the deadline passes', async () => {
    const fetch = vi.fn().mockResolvedValue(null);
    const { result } = renderHook(() =>
      usePolledResource({ fetch, enabled: true, timeoutMs: 5_000 }),
    );

    await advance(20_000);

    // Nothing is known to be broken; we simply stopped waiting. The UI needs
    // that distinction to say something calm and offer "check again".
    expect(result.current.status).toBe('timedOut');
    expect(result.current.error).toBeNull();
  });

  it('backs off rather than hammering the gateway', async () => {
    // The gateway allows 100 req/min. Polling twice a second is what tripped it
    // in the e2e suite, so this asserts the budget directly.
    const fetch = vi.fn().mockResolvedValue(null);
    renderHook(() => usePolledResource({ fetch, enabled: true, timeoutMs: 60_000 }));

    await advance(60_000);

    expect(fetch.mock.calls.length).toBeLessThan(20);
  });

  it('waits for the right STATE, not merely existence', async () => {
    // A booking exists immediately; we are waiting for it to become CONFIRMED.
    const fetch = vi
      .fn()
      .mockResolvedValueOnce({ status: 'CREATED' })
      .mockResolvedValue({ status: 'CONFIRMED' });

    const { result } = renderHook(() =>
      usePolledResource<{ status: string }>({
        fetch,
        isReady: (booking) => booking.status === 'CONFIRMED',
        enabled: true,
      }),
    );

    await advance(1_000);
    await advance(0);
    expect(result.current.status).toBe('ready');
    expect(result.current.data).toEqual({ status: 'CONFIRMED' });
  });

  it('does not restart when the caller passes a new inline fetch each render', async () => {
    // Callers naturally write fetch={() => api.get(...)}. If that were an effect
    // dependency, every parent render would cancel and restart the wait, and it
    // would never finish - so the hook holds it in a ref.
    const fetch = vi.fn().mockResolvedValue(null);
    const { rerender } = renderHook(() =>
      usePolledResource({ fetch: (signal) => fetch(signal), enabled: true }),
    );

    await advance(1_000);
    const afterFirst = fetch.mock.calls.length;

    rerender();
    rerender();
    await advance(0);

    // Re-rendering must not have triggered extra immediate fetches.
    expect(fetch.mock.calls.length).toBe(afterFirst);
  });

  it('stops polling when unmounted', async () => {
    const fetch = vi.fn().mockResolvedValue(null);
    const { unmount } = renderHook(() => usePolledResource({ fetch, enabled: true }));

    await advance(1_000);
    const beforeUnmount = fetch.mock.calls.length;

    unmount();
    await advance(30_000);

    // A poll surviving unmount would keep spending the rate-limit budget for a
    // screen nobody is looking at.
    expect(fetch.mock.calls.length).toBe(beforeUnmount);
  });
});
