package com.skybook.praveen.notificationservice.service;

import com.skybook.praveen.common.event.BookingEvent;
import com.skybook.praveen.common.event.BookingEventPassenger;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Renders the booking notification email from a BookingEvent's structured
 * fields. Plain inline-CSS HTML - no template engine dependency; email
 * clients ignore external stylesheets anyway.
 *
 * Only called when the event carries structured details (passengers != null);
 * older/lean events fall back to the plain-text message in the consumer.
 */
@Component
public class BookingEmailTemplate {

    /** Content-ID under which the consumer attaches the inline QR PNG. */
    public static final String QR_CID = "skybook-qr";

    public String render(BookingEvent event) {
        return render(event, false);
    }

    public String render(BookingEvent event, boolean includeQr) {

        String statusColor = switch (event.getType()) {
            case CONFIRMED, COMPLETED -> "#1a7f37";
            case CANCELLED, EXPIRED -> "#b42318";
            default -> "#b45309"; // CREATED - awaiting payment
        };
        String statusBg = switch (event.getType()) {
            case CONFIRMED, COMPLETED -> "#dafbe1";
            case CANCELLED, EXPIRED -> "#ffe5e0";
            default -> "#fff8e6";
        };

        StringBuilder passengers = new StringBuilder();
        if (event.getPassengers() != null) {
            for (BookingEventPassenger p : event.getPassengers()) {
                passengers.append("""
                        <tr>
                          <td style="padding:8px 12px;border-top:1px solid #e5e7eb;">%s</td>
                          <td style="padding:8px 12px;border-top:1px solid #e5e7eb;text-align:center;"><b>%s</b></td>
                          <td style="padding:8px 12px;border-top:1px solid #e5e7eb;">%s · %s</td>
                          <td style="padding:8px 12px;border-top:1px solid #e5e7eb;text-align:right;">%s</td>
                        </tr>
                        """.formatted(
                        escape(p.getName()),
                        escape(nvl(p.getSeatNumber(), "—")),
                        escape(pretty(p.getTravelClass())),
                        escape(pretty(p.getFareType())),
                        money(p.getFare(), event.getCurrency())));
            }
        }

        return """
                <!DOCTYPE html>
                <html>
                <body style="margin:0;padding:0;background:#f4f5f7;font-family:Segoe UI,Helvetica,Arial,sans-serif;color:#1f2328;">
                <div style="max-width:600px;margin:0 auto;padding:24px 16px;">

                  <div style="background:#0b3d91;border-radius:10px 10px 0 0;padding:20px 24px;">
                    <span style="color:#ffffff;font-size:20px;font-weight:700;">✈ SkyBook</span>
                  </div>

                  <div style="background:#ffffff;padding:24px;border:1px solid #e5e7eb;border-top:none;border-radius:0 0 10px 10px;">

                    <div style="background:%s;color:%s;display:inline-block;padding:4px 14px;border-radius:14px;font-size:13px;font-weight:600;">
                      %s
                    </div>

                    <h2 style="margin:14px 0 4px;font-size:18px;">Hello %s,</h2>
                    <p style="margin:0 0 20px;color:#57606a;font-size:14px;">%s</p>

                    <table style="width:100%%;border-collapse:collapse;background:#f6f8fa;border:1px solid #e5e7eb;border-radius:8px;font-size:14px;">
                      <tr>
                        <td style="padding:14px 16px;">
                          <div style="color:#57606a;font-size:12px;">BOOKING REFERENCE (PNR)</div>
                          <div style="font-size:24px;font-weight:700;letter-spacing:2px;">%s</div>
                        </td>
                        <td style="padding:14px 16px;text-align:right;">
                          <div style="color:#57606a;font-size:12px;">FLIGHT</div>
                          <div style="font-weight:600;">#%s</div>
                          <div style="color:#57606a;font-size:12px;margin-top:4px;">Booked %s</div>
                        </td>
                      </tr>
                    </table>

                    <h3 style="font-size:14px;margin:22px 0 8px;color:#57606a;text-transform:uppercase;letter-spacing:.04em;">Passengers</h3>
                    <table style="width:100%%;border-collapse:collapse;font-size:14px;border:1px solid #e5e7eb;border-radius:8px;">
                      <tr style="background:#f6f8fa;color:#57606a;font-size:12px;">
                        <td style="padding:8px 12px;">NAME</td>
                        <td style="padding:8px 12px;text-align:center;">SEAT</td>
                        <td style="padding:8px 12px;">CLASS · FARE</td>
                        <td style="padding:8px 12px;text-align:right;">PRICE</td>
                      </tr>
                      %s
                    </table>

                    <table style="width:100%%;border-collapse:collapse;margin-top:16px;font-size:14px;">
                      <tr>
                        <td style="padding:10px 12px;background:#f6f8fa;border:1px solid #e5e7eb;border-radius:8px;">
                          <span style="color:#57606a;">Total fare</span>
                          <span style="float:right;font-weight:700;font-size:16px;">%s</span><br>
                          <span style="color:#57606a;font-size:12px;">Payment: <b style="color:%s;">%s</b></span>
                        </td>
                      </tr>
                    </table>

                    %s

                    <p style="margin:22px 0 0;color:#8b949e;font-size:12px;">
                      This is an automated message from SkyBook. Reference %s · Do not reply to this email.
                    </p>
                  </div>
                </div>
                </body>
                </html>
                """.formatted(
                statusBg, statusColor,
                "Booking " + pretty(event.getType().name()),
                escape(nvl(event.getContactName(), "traveler")),
                escape(nvl(event.getMessage(), "")),
                escape(nvl(event.getBookingReference(), "—")),
                event.getFlightId() != null ? event.getFlightId() : "—",
                escape(nvl(event.getBookingDate(), "—")),
                passengers,
                money(event.getTotalFare(), event.getCurrency()),
                "PAID".equals(event.getPaymentStatus()) ? "#1a7f37" : "#b45309",
                escape(nvl(event.getPaymentStatus(), "PENDING")),
                includeQr ? qrBlock() : "",
                escape(nvl(event.getBookingReference(), "")));
    }

    private static String qrBlock() {
        return """
                <table style="width:100%%;border-collapse:collapse;margin-top:16px;">
                  <tr>
                    <td style="padding:16px;background:#f6f8fa;border:1px solid #e5e7eb;border-radius:8px;text-align:center;">
                      <img src="cid:%s" width="140" height="140" alt="Booking QR code" style="display:block;margin:0 auto 8px;">
                      <span style="color:#57606a;font-size:12px;">Show this QR code at check-in</span>
                    </td>
                  </tr>
                </table>
                """.formatted(QR_CID);
    }

    private static String money(BigDecimal amount, String currency) {
        if (amount == null) return "—";
        return (currency != null ? currency + " " : "") + amount;
    }

    /** "PREMIUM_ECONOMY" -> "Premium economy" */
    private static String pretty(String enumName) {
        if (enumName == null) return "—";
        String s = enumName.replace('_', ' ').toLowerCase();
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String nvl(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
