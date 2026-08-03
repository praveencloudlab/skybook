/**
 * Error mapping (FRONTEND_MODULE.md §6).
 *
 * The platform already returns a consistent, well-chosen error surface. The
 * client's job is to preserve that meaning, NOT to flatten every failure into
 * "something went wrong" - which is the default outcome if screens each invent
 * their own handling.
 */

/** The fleet-wide error body every service returns (`ErrorResponse`). */
export interface ApiErrorBody {
  timestamp?: string;
  status?: number;
  error?: string;
  message?: string;
  path?: string;
}

/**
 * What kind of failure this is, in terms the UI can act on.
 *
 * Deliberately not a bare status code: screens should branch on meaning
 * ("the seat went to someone else") rather than on numbers.
 */
export type ApiErrorKind =
  | 'validation' // 400 - field-level messages
  | 'unauthenticated' // 401 - session over, go to login
  | 'forbidden' // 403 - not yours, or not your role
  | 'notFound' // 404
  | 'conflict' // 409 - seat taken, window closed, already checked in
  | 'declined' // 422 - payment declined (NOT a system error)
  | 'rateLimited' // 429 - we polled too hard
  | 'unavailable' // 502/503/504 - a service is down
  | 'server' // 500
  | 'network'; // never reached the gateway

export class ApiError extends Error {
  readonly kind: ApiErrorKind;
  readonly status: number;
  readonly body?: ApiErrorBody;

  constructor(kind: ApiErrorKind, status: number, message: string, body?: ApiErrorBody) {
    super(message);
    this.name = 'ApiError';
    this.kind = kind;
    this.status = status;
    this.body = body;
  }

  /** True when re-trying the same request could plausibly succeed. */
  get retryable(): boolean {
    return this.kind === 'unavailable' || this.kind === 'network' || this.kind === 'rateLimited';
  }
}

export function kindForStatus(status: number): ApiErrorKind {
  switch (status) {
    case 400:
      return 'validation';
    case 401:
      return 'unauthenticated';
    case 403:
      return 'forbidden';
    case 404:
      return 'notFound';
    case 409:
      return 'conflict';
    case 422:
      return 'declined';
    case 429:
      return 'rateLimited';
    case 502:
    case 503:
    case 504:
      return 'unavailable';
    default:
      return status >= 500 ? 'server' : 'validation';
  }
}

/**
 * Copy for a failure, in the user's terms.
 *
 * Two rules worth stating, because both are easy to get wrong:
 *  - a declined card is NOT an error on our side, and must never read like one;
 *  - a 502 means WE could not reach a service, so it must never imply the user
 *    did something wrong.
 *
 * Where the server sent a specific message (validation details, a conflict
 * reason like "check-in does not open until ..."), we prefer it - it is more
 * accurate than anything we can invent client-side.
 */
export function userMessage(error: ApiError): string {
  const fromServer = error.body?.message?.trim();

  switch (error.kind) {
    case 'validation': {
      // The server sends every violation as one joined string. Repeating it
      // verbatim is unreadable, and the individual messages are already shown
      // against their own inputs - so point at the form instead.
      const count = fieldErrorCount(error);
      if (count > 1) {
        return `Please check the ${count} highlighted fields.`;
      }
      if (count === 1) {
        return 'Please check the highlighted field.';
      }
      return fromServer || 'Please check the details you entered.';
    }
    case 'unauthenticated':
      return 'Your session has expired. Please sign in again.';
    case 'forbidden':
      return "You don't have access to this.";
    case 'notFound':
      return fromServer || "We couldn't find that.";
    case 'conflict':
      return fromServer || 'That is no longer available. Please try again.';
    case 'declined':
      return fromServer || 'Your card was declined. Try a different payment method.';
    case 'rateLimited':
      return 'Too many requests just now. Give it a moment and try again.';
    case 'unavailable':
      return "We can't reach our booking system right now. Please try again shortly.";
    case 'server':
      return 'Something went wrong on our side. Please try again.';
    case 'network':
      return "We couldn't reach SkyBook. Check your connection and try again.";
  }
}

/**
 * Field-level messages from a 400.
 *
 * <p>The fleet's validation advice joins violations into ONE string:
 * {@code "passengers: At least one passenger is required, flightId: flightId is
 * required, contact.contactEmail: Contact email is required"}. So it has to be
 * taken apart to attach each message to its own input - showing that raw string
 * to a passenger would be worse than showing nothing.
 *
 * <p>Nested paths are indexed under BOTH the full path and its last segment
 * ({@code contact.contactEmail} and {@code contactEmail}), because a form's
 * field is usually named after the leaf rather than the server's object graph.
 */
export function fieldErrors(error: ApiError): Record<string, string> {
  if (error.kind !== 'validation' || !error.body?.message) {
    return {};
  }

  const result: Record<string, string> = {};
  // Split only where a new "field:" actually begins, so a message containing a
  // comma is not torn in half.
  for (const part of error.body.message.split(/,\s*(?=[A-Za-z_][\w.[\]]*\s*:)/)) {
    const separator = part.indexOf(':');
    if (separator === -1) {
      continue;
    }
    const field = part.slice(0, separator).trim();
    const message = part.slice(separator + 1).trim();
    if (!field || !message) {
      continue;
    }
    result[field] = message;

    const leaf = field.split('.').pop();
    if (leaf && !(leaf in result)) {
      result[leaf] = message;
    }
  }
  return result;
}

/**
 * How many distinct fields a validation error mentions.
 *
 * <p>Used to decide between naming the problem and pointing at the form.
 */
export function fieldErrorCount(error: ApiError): number {
  return Object.keys(fieldErrors(error)).length;
}
