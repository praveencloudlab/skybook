import { api } from './client';

/**
 * Payments (FRONTEND_MODULE.md §5 screen 6).
 *
 * <p>Two things here are shaped by how the platform actually behaves rather than
 * by what a payment API usually looks like:
 *
 * <ul>
 *   <li><b>The payment row is created for us, over Kafka.</b> Creating a booking
 *       publishes an event that payment-service consumes; the row appears a
 *       moment later. So the flow is "wait for it, then authorise", not "create
 *       a payment". {@link paymentsApi.create} exists only as a fallback.</li>
 *   <li><b>Authorise and capture are separate.</b> Authorising reserves the
 *       money and can be DECLINED; capturing takes it and issues the invoice.
 *       Collapsing them in the UI would lose the one moment where a decline is
 *       recoverable without touching the booking.</li>
 * </ul>
 */

export type PaymentStatus =
  | 'PENDING'
  | 'AUTHORIZATION_FAILED'
  | 'AUTHORIZED'
  | 'CAPTURE_FAILED'
  | 'CAPTURED'
  | 'PARTIALLY_REFUNDED'
  | 'REFUNDED'
  | 'CANCELLED';

export type PaymentMethod = 'CARD' | 'UPI' | 'BANK_TRANSFER' | 'APPLE_PAY' | 'GOOGLE_PAY' | 'PAYPAL';

export const PAYMENT_METHOD_LABELS: Record<PaymentMethod, string> = {
  CARD: 'Card',
  UPI: 'UPI',
  BANK_TRANSFER: 'Bank transfer',
  APPLE_PAY: 'Apple Pay',
  GOOGLE_PAY: 'Google Pay',
  PAYPAL: 'PayPal',
};

export interface Payment {
  id: number;
  paymentReference: string;
  bookingId: number;
  bookingReference: string;
  amount: string | number;
  currency: string;
  capturedAmount?: string | number;
  status: PaymentStatus;
  method: PaymentMethod;
  gatewayReference?: string;
}

export interface Invoice {
  id: number;
  invoiceNumber: string;
  paymentReference: string;
  bookingReference: string;
  subtotal: string | number;
  taxAmount: string | number;
  discount: string | number;
  totalAmount?: string | number;
}

export const paymentsApi = {
  /**
   * The payment for a booking, or null while the consumer has not created it.
   *
   * <p>Returning null on 404 rather than throwing is what lets this be polled
   * directly: "not there yet" is the expected answer for the first second or so
   * after a booking is made, not an error.
   */
  async forBooking(bookingId: number, signal?: AbortSignal): Promise<Payment | null> {
    try {
      return await api.get<Payment>(`/api/payments/booking/${bookingId}`, { signal });
    } catch (cause) {
      if (cause instanceof Error && 'kind' in cause && cause.kind === 'notFound') {
        return null;
      }
      throw cause;
    }
  },

  authorize(paymentId: number, signal?: AbortSignal): Promise<Payment> {
    return api.patch<Payment>(`/api/payments/${paymentId}/authorize`, undefined, { signal });
  },

  capture(paymentId: number, signal?: AbortSignal): Promise<Payment> {
    return api.patch<Payment>(`/api/payments/${paymentId}/capture`, undefined, { signal });
  },

  invoice(paymentId: number, signal?: AbortSignal): Promise<Invoice> {
    return api.get<Invoice>(`/api/payments/${paymentId}/invoice`, { signal });
  },
};

/**
 * Is this status a dead end for the passenger?
 *
 * <p>AUTHORIZATION_FAILED is recoverable - they can try again. CANCELLED is not.
 */
export function isDeclined(status: PaymentStatus): boolean {
  return status === 'AUTHORIZATION_FAILED' || status === 'CAPTURE_FAILED';
}
