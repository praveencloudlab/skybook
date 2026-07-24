import { useCallback, useEffect, useRef, useState } from 'react';
import { ASYNC_TIMEOUT_MS, POLL_BACKOFF_MS } from './config';
import { ApiError } from './errors';

/**
 * Waiting for an asynchronous journey step (FRONTEND_MODULE.md §3).
 *
 * <p>Three steps of the customer journey complete over Kafka, not over the HTTP
 * call that triggered them: a booking's payment row appears, a captured payment
 * turns the booking CONFIRMED, and a confirmed booking produces check-in records.
 * The resource genuinely <b>does not exist yet</b> when the previous request
 * returns - so this is not "a slow response", it is "not there, ask again".
 *
 * <p>Three things here are deliberate rather than incidental:
 *
 * <ul>
 *   <li><b>Backoff, not a fixed interval.</b> The gateway rate-limits at 100
 *       req/min. The e2e suite polled twice a second and tripped exactly that,
 *       producing 429s that looked like product failures - the limiter policing
 *       the client. Backing off keeps a long wait well inside the budget even
 *       with several tabs open.</li>
 *   <li><b>A deadline, and a state for reaching it.</b> "timedOut" is not the
 *       same as "failed": nothing is known to be wrong, we simply stopped
 *       waiting. It gets its own state so the UI can say something calm and
 *       offer to check again, instead of spinning forever or claiming an error
 *       that may not have happened.</li>
 *   <li><b>404 and 403 are treated as "not yet".</b> Until the Kafka consumer
 *       has created the row, the resource is genuinely absent, and an
 *       ownership-scoped endpoint answers 403 rather than 404 for something that
 *       does not exist. Both are expected mid-flight, so neither is surfaced as
 *       an error.</li>
 * </ul>
 */
export type PollStatus = 'idle' | 'working' | 'ready' | 'timedOut' | 'failed';

export interface PolledResource<T> {
  status: PollStatus;
  data: T | null;
  error: ApiError | null;
  /** Attempts made so far - useful for "still working on it" copy. */
  attempts: number;
  /** Start (or restart) polling. Safe to call from a "check again" button. */
  start: () => void;
  /** Stop and reset to idle. */
  reset: () => void;
}

export interface PollOptions<T> {
  /** Fetch the resource. Return null (or throw 404/403) when it is not there yet. */
  fetch: (signal: AbortSignal) => Promise<T | null>;
  /** Is this the state we were waiting for? Defaults to "it exists". */
  isReady?: (value: T) => boolean;
  /** Begin immediately on mount. */
  enabled?: boolean;
  timeoutMs?: number;
}

export function usePolledResource<T>(options: PollOptions<T>): PolledResource<T> {
  const { fetch, isReady, enabled = false, timeoutMs = ASYNC_TIMEOUT_MS } = options;

  const [status, setStatus] = useState<PollStatus>('idle');
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<ApiError | null>(null);
  const [attempts, setAttempts] = useState(0);
  const [runId, setRunId] = useState(0);

  // Kept in refs so changing them cannot restart an in-flight poll. Callers
  // routinely pass inline arrow functions; putting these in the effect's
  // dependencies would cancel and restart the wait on every parent render,
  // which in practice means it never completes.
  const fetchRef = useRef(fetch);
  const isReadyRef = useRef(isReady);
  fetchRef.current = fetch;
  isReadyRef.current = isReady;

  const start = useCallback(() => {
    setStatus('working');
    setData(null);
    setError(null);
    setAttempts(0);
    setRunId((id) => id + 1);
  }, []);

  const reset = useCallback(() => {
    setStatus('idle');
    setData(null);
    setError(null);
    setAttempts(0);
  }, []);

  useEffect(() => {
    if (enabled && status === 'idle') {
      start();
    }
  }, [enabled, status, start]);

  useEffect(() => {
    if (status !== 'working') {
      return;
    }

    const controller = new AbortController();
    let timer: ReturnType<typeof setTimeout> | undefined;
    let cancelled = false;
    const deadline = Date.now() + timeoutMs;
    let attempt = 0;

    const poll = async () => {
      if (cancelled) {
        return;
      }

      try {
        const value = await fetchRef.current(controller.signal);
        if (cancelled) {
          return;
        }

        const ready = value !== null && value !== undefined
          && (isReadyRef.current ? isReadyRef.current(value) : true);

        if (ready) {
          setData(value as T);
          setStatus('ready');
          return;
        }
      } catch (cause) {
        if (cancelled || (cause instanceof DOMException && cause.name === 'AbortError')) {
          return;
        }

        // Mid-flight absence, not failure: the consumer has not created it yet
        // (404), or an ownership check cannot find it to own (403).
        const notYet =
          cause instanceof ApiError && (cause.kind === 'notFound' || cause.kind === 'forbidden');

        if (!notYet) {
          setError(cause instanceof ApiError ? cause : null);
          setStatus('failed');
          return;
        }
      }

      attempt += 1;
      setAttempts(attempt);

      if (Date.now() >= deadline) {
        // Out of time - but nothing is known to be broken. Say so honestly.
        setStatus('timedOut');
        return;
      }

      const delay = POLL_BACKOFF_MS[Math.min(attempt - 1, POLL_BACKOFF_MS.length - 1)];
      timer = setTimeout(poll, delay);
    };

    void poll();

    return () => {
      cancelled = true;
      controller.abort();
      if (timer) {
        clearTimeout(timer);
      }
    };
  }, [status, runId, timeoutMs]);

  return { status, data, error, attempts, start, reset };
}
