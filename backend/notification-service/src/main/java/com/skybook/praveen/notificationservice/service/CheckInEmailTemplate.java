package com.skybook.praveen.notificationservice.service;

import com.skybook.praveen.common.event.CheckInEvent;
import com.skybook.praveen.notificationservice.service.AirlineLookup.AirlineBrand;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;

/**
 * Renders the check-in confirmation email from a CheckInEvent's structured
 * fields - same plain inline-CSS HTML approach as BookingEmailTemplate (no
 * template engine dependency), but centered on a single passenger's
 * boarding details rather than a whole booking's passenger table, since
 * CheckInEvent is per-passenger.
 *
 * Styled to mirror BoardingPassPdfTemplate's card: an airline-branded
 * gradient header with a monogram badge (AirlineLookup - no real logo asset
 * exists anywhere in the system), a large IATA route with a plane divider,
 * and chip-style seat/gate/boarding-time/group cards. Gradients/rounded
 * corners degrade gracefully (flat background-color fallback) in clients
 * like Outlook desktop that ignore them.
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

        AirlineBrand brand = AirlineLookup.forFlightNumber(event.getFlightNumber());
        String originCity = AirportCityLookup.cityFor(event.getOriginAirportCode());
        String destinationCity = AirportCityLookup.cityFor(event.getDestinationAirportCode());

        return """
                <!DOCTYPE html>
                <html>
                <body style="margin:0;padding:0;background:#eef1f5;font-family:Segoe UI,Helvetica,Arial,sans-serif;color:#1f2328;">
                <div style="max-width:600px;margin:0 auto;padding:24px 16px;">

                  <table width="100%%" style="background-color:%s;background-image:linear-gradient(105deg,%s,%s);border-radius:14px 14px 0 0;">
                    <tr>
                      <td style="padding:22px 24px;">
                        <table width="100%%">
                          <tr>
                            <td>
                              <div style="color:#ffffff;font-size:21px;font-weight:700;">SkyBook</div>
                              <div style="color:#ffffff;font-size:10px;letter-spacing:2px;margin-top:2px;opacity:0.85;">MOBILE BOARDING PASS</div>
                            </td>
                            <td style="text-align:right;">
                              <table style="margin-left:auto;">
                                <tr>
                                  <td style="text-align:right;padding-right:10px;">
                                    <div style="color:#ffffff;font-size:11px;font-weight:600;">%s</div>
                                    <div style="color:#ffffff;font-size:8px;letter-spacing:1px;opacity:0.8;">OPERATED BY</div>
                                  </td>
                                  <td>
                                    <table style="width:38px;">
                                      <tr><td style="width:38px;height:38px;background-color:#ffffff;border-radius:19px;text-align:center;vertical-align:middle;color:%s;font-size:12px;font-weight:700;">%s</td></tr>
                                    </table>
                                  </td>
                                </tr>
                              </table>
                            </td>
                          </tr>
                        </table>
                      </td>
                    </tr>
                  </table>

                  <div style="background:#ffffff;padding:24px;border:1px solid #e5e7eb;border-top:none;border-radius:0 0 14px 14px;">

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
                        <td style="padding:16px;text-align:center;width:33%%;">
                          <div style="font-size:28px;font-weight:700;letter-spacing:1px;">%s</div>
                          <div style="color:#57606a;font-size:12px;">%s</div>
                        </td>
                        <td style="padding:16px 6px;text-align:center;width:34%%;">
                          <div style="color:%s;font-size:11px;font-weight:700;letter-spacing:1px;">FLIGHT %s</div>
                          <table width="100%%" style="margin-top:8px;"><tr>
                            <td style="border-top:2px dashed %s;font-size:0;">&#160;</td>
                            <td style="width:0;"><div style="width:0;height:0;border-top:5px solid transparent;border-bottom:5px solid transparent;border-left:8px solid %s;"></div></td>
                          </tr></table>
                        </td>
                        <td style="padding:16px;text-align:center;width:33%%;">
                          <div style="font-size:28px;font-weight:700;letter-spacing:1px;">%s</div>
                          <div style="color:#57606a;font-size:12px;">%s</div>
                        </td>
                      </tr>
                    </table>

                    <table style="width:100%%;border-collapse:collapse;margin-top:12px;">
                      <tr>
                        <td style="width:25%%;padding-right:6px;">
                          <table width="100%%" style="background:#f6f8fa;border-radius:8px;">
                            <tr><td style="height:3px;background:%s;border-radius:8px 8px 0 0;font-size:0;">&#160;</td></tr>
                            <tr><td style="padding:10px;"><div style="color:#8b949e;font-size:10px;letter-spacing:1px;">SEAT</div><div style="font-weight:700;font-size:16px;margin-top:2px;">%s</div></td></tr>
                          </table>
                        </td>
                        <td style="width:25%%;padding:0 3px;">
                          <table width="100%%" style="background:#f6f8fa;border-radius:8px;">
                            <tr><td style="height:3px;background:%s;border-radius:8px 8px 0 0;font-size:0;">&#160;</td></tr>
                            <tr><td style="padding:10px;"><div style="color:#8b949e;font-size:10px;letter-spacing:1px;">GATE</div><div style="font-weight:700;font-size:16px;margin-top:2px;">%s</div></td></tr>
                          </table>
                        </td>
                        <td style="width:25%%;padding:0 3px;">
                          <table width="100%%" style="background:#f6f8fa;border-radius:8px;">
                            <tr><td style="height:3px;background:%s;border-radius:8px 8px 0 0;font-size:0;">&#160;</td></tr>
                            <tr><td style="padding:10px;"><div style="color:#8b949e;font-size:10px;letter-spacing:1px;">GROUP</div><div style="font-weight:700;font-size:16px;margin-top:2px;">%s</div></td></tr>
                          </table>
                        </td>
                        <td style="width:25%%;padding-left:6px;">
                          <table width="100%%" style="background:#f6f8fa;border-radius:8px;">
                            <tr><td style="height:3px;background:%s;border-radius:8px 8px 0 0;font-size:0;">&#160;</td></tr>
                            <tr><td style="padding:10px;"><div style="color:#8b949e;font-size:10px;letter-spacing:1px;">BOARDING</div><div style="font-weight:700;font-size:13px;margin-top:2px;">%s</div></td></tr>
                          </table>
                        </td>
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
                      This is an automated message from SkyBook. Reference %s &middot; Do not reply to this email.
                    </p>
                  </div>
                </div>
                </body>
                </html>
                """.formatted(
                brand.primaryColor(), brand.primaryColor(), brand.secondaryColor(),
                escape(brand.displayName()), brand.primaryColor(), escape(brand.code()),
                escape(nvl(event.getPassengerName(), "traveler")),
                escape(nvl(event.getFlightNumber(), "your flight")),
                escape(nvl(event.getBookingReference(), "-")),
                escape(nvl(event.getBoardingPassNumber(), "-")),
                escape(nvl(event.getOriginAirportCode(), "-")),
                escape(nvl(originCity, "")),
                brand.primaryColor(), escape(nvl(event.getFlightNumber(), "")),
                brand.secondaryColor(), brand.secondaryColor(),
                escape(nvl(event.getDestinationAirportCode(), "-")),
                escape(nvl(destinationCity, "")),
                brand.primaryColor(), escape(nvl(event.getSeatNumber(), "-")),
                brand.primaryColor(), escape(nvl(event.getGate(), "TBA")),
                brand.secondaryColor(), escape(nvl(event.getBoardingGroup(), "-")),
                brand.secondaryColor(),
                escape(event.getBoardingTime() != null ? event.getBoardingTime().format(DISPLAY_TIME) : "-"),
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
