package com.skybook.praveen.notificationservice.service;

import com.skybook.praveen.common.event.CheckInEvent;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;

/**
 * Renders the check-in confirmation email from a CheckInEvent's structured
 * fields - same plain inline-CSS HTML approach as BookingEmailTemplate (no
 * template engine dependency), but centered on a single passenger's
 * boarding details rather than a whole booking's passenger table, since
 * CheckInEvent is per-passenger.
 *
 * Only rendered for BOARDING_PASS_GENERATED (design doc for the email
 * feature: PASSENGER_CHECKED_IN alone would produce a duplicate email for
 * the same check-in, since both fire in the same CheckInFacade.checkIn()
 * call - BOARDING_PASS_GENERATED is the richer event and the one actually
 * carrying pass details).
 */
@Component
public class CheckInEmailTemplate {

    /** Content-ID under which the consumer attaches the inline QR PNG. */
    public static final String QR_CID = "skybook-boarding-qr";

    private static final DateTimeFormatter DISPLAY_TIME = DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm");

    public String render(CheckInEvent event) {

        String originCity = AirportCityLookup.cityFor(event.getOriginAirportCode());
        String destinationCity = AirportCityLookup.cityFor(event.getDestinationAirportCode());

        return """
                <!DOCTYPE html>
                <html>
                <body style="margin:0;padding:0;background:#f4f5f7;font-family:Segoe UI,Helvetica,Arial,sans-serif;color:#1f2328;">
                <div style="max-width:600px;margin:0 auto;padding:24px 16px;">

                  <div style="background:#0b3d91;border-radius:10px 10px 0 0;padding:20px 24px;">
                    <span style="color:#ffffff;font-size:20px;font-weight:700;">✈ SkyBook</span>
                  </div>

                  <div style="background:#ffffff;padding:24px;border:1px solid #e5e7eb;border-top:none;border-radius:0 0 10px 10px;">

                    <div style="background:#dafbe1;color:#1a7f37;display:inline-block;padding:4px 14px;border-radius:14px;font-size:13px;font-weight:600;">
                      Checked in
                    </div>

                    <h2 style="margin:14px 0 4px;font-size:18px;">Hello %s,</h2>
                    <p style="margin:0 0 20px;color:#57606a;font-size:14px;">You're checked in. Here's your boarding pass for %s.</p>

                    <table style="width:100%%;border-collapse:collapse;background:#f6f8fa;border:1px solid #e5e7eb;border-radius:8px;font-size:14px;">
                      <tr>
                        <td style="padding:14px 16px;">
                          <div style="color:#57606a;font-size:12px;">BOOKING REFERENCE (PNR)</div>
                          <div style="font-size:20px;font-weight:700;letter-spacing:2px;">%s</div>
                        </td>
                        <td style="padding:14px 16px;text-align:right;">
                          <div style="color:#57606a;font-size:12px;">BOARDING PASS</div>
                          <div style="font-weight:600;">%s</div>
                        </td>
                      </tr>
                    </table>

                    <table style="width:100%%;border-collapse:collapse;margin-top:12px;background:#f6f8fa;border:1px solid #e5e7eb;border-radius:8px;font-size:14px;">
                      <tr>
                        <td style="padding:14px 16px;text-align:center;width:33%%;">
                          <div style="font-size:26px;font-weight:700;letter-spacing:1px;">%s</div>
                          <div style="color:#57606a;font-size:12px;">%s</div>
                        </td>
                        <td style="padding:14px 8px;text-align:center;color:#0b3d91;font-size:18px;">
                          ────── ✈ ──────
                          <div style="color:#57606a;font-size:12px;margin-top:2px;">%s</div>
                        </td>
                        <td style="padding:14px 16px;text-align:center;width:33%%;">
                          <div style="font-size:26px;font-weight:700;letter-spacing:1px;">%s</div>
                          <div style="color:#57606a;font-size:12px;">%s</div>
                        </td>
                      </tr>
                    </table>

                    <table style="width:100%%;border-collapse:collapse;margin-top:16px;font-size:14px;border:1px solid #e5e7eb;border-radius:8px;">
                      <tr style="background:#f6f8fa;color:#57606a;font-size:12px;">
                        <td style="padding:8px 12px;">SEAT</td>
                        <td style="padding:8px 12px;">GATE</td>
                        <td style="padding:8px 12px;">BOARDING TIME</td>
                        <td style="padding:8px 12px;">GROUP</td>
                      </tr>
                      <tr>
                        <td style="padding:8px 12px;border-top:1px solid #e5e7eb;font-weight:700;">%s</td>
                        <td style="padding:8px 12px;border-top:1px solid #e5e7eb;">%s</td>
                        <td style="padding:8px 12px;border-top:1px solid #e5e7eb;">%s</td>
                        <td style="padding:8px 12px;border-top:1px solid #e5e7eb;">%s</td>
                      </tr>
                    </table>

                    <table style="width:100%%;border-collapse:collapse;margin-top:16px;">
                      <tr>
                        <td style="padding:16px;background:#f6f8fa;border:1px solid #e5e7eb;border-radius:8px;text-align:center;">
                          <img src="cid:%s" width="140" height="140" alt="Boarding pass QR code" style="display:block;margin:0 auto 8px;">
                          <span style="color:#57606a;font-size:12px;">Show this QR code at the gate. A printable boarding pass is attached to this email.</span>
                        </td>
                      </tr>
                    </table>

                    <p style="margin:22px 0 0;color:#8b949e;font-size:12px;">
                      This is an automated message from SkyBook. Reference %s · Do not reply to this email.
                    </p>
                  </div>
                </div>
                </body>
                </html>
                """.formatted(
                escape(nvl(event.getPassengerName(), "traveler")),
                escape(nvl(event.getFlightNumber(), "your flight")),
                escape(nvl(event.getBookingReference(), "—")),
                escape(nvl(event.getBoardingPassNumber(), "—")),
                escape(nvl(event.getOriginAirportCode(), "—")),
                escape(nvl(originCity, "")),
                escape(nvl(event.getFlightNumber(), "")),
                escape(nvl(event.getDestinationAirportCode(), "—")),
                escape(nvl(destinationCity, "")),
                escape(nvl(event.getSeatNumber(), "—")),
                escape(nvl(event.getGate(), "TBA")),
                escape(event.getBoardingTime() != null ? event.getBoardingTime().format(DISPLAY_TIME) : "—"),
                escape(nvl(event.getBoardingGroup(), "—")),
                QR_CID,
                escape(nvl(event.getBookingReference(), "")));
    }

    private static String nvl(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
