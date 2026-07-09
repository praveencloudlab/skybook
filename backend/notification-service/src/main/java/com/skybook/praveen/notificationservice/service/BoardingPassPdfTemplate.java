package com.skybook.praveen.notificationservice.service;

import com.skybook.praveen.common.event.CheckInEvent;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.util.Base64;

/**
 * Renders the downloadable/printable boarding pass as strict XHTML for
 * TicketPdfRenderer (same generic HTML-to-PDF renderer TicketPdfTemplate
 * uses - openhtmltopdf parses with an XML parser, so every tag must close
 * and every attribute must be quoted, and Unicode symbols like the plane
 * emoji are avoided since the base-14 PDF fonts don't cover them).
 *
 * Deliberately its own template rather than reusing TicketPdfTemplate - a
 * boarding pass is a different, simpler document (single passenger, gate/
 * seat/boarding-time focused) than a full e-ticket/itinerary.
 */
@Component
public class BoardingPassPdfTemplate {

    private static final DateTimeFormatter DISPLAY_TIME = DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm");

    public String render(CheckInEvent event, byte[] qrPng) {

        String originCity = AirportCityLookup.cityFor(event.getOriginAirportCode());
        String destinationCity = AirportCityLookup.cityFor(event.getDestinationAirportCode());

        String qrDataUri = qrPng != null
                ? "data:image/png;base64," + Base64.getEncoder().encodeToString(qrPng)
                : null;

        return """
                <html xmlns="http://www.w3.org/1999/xhtml">
                <head>
                <style>
                  @page { size: A4; margin: 30px 34px; }
                  body { font-family: Helvetica, Arial, sans-serif; color: #1f2328; font-size: 11px; }
                  table { border-collapse: collapse; }
                </style>
                </head>
                <body>

                  <table width="100%%" style="background-color:#0b3d91;">
                    <tr>
                      <td style="padding:16px 20px;color:#ffffff;font-size:22px;font-weight:bold;">SkyBook</td>
                      <td style="padding:16px 20px;color:#ffffff;font-size:11px;text-align:right;letter-spacing:2px;">BOARDING PASS</td>
                    </tr>
                  </table>

                  <table width="100%%" style="background-color:#f6f8fa;border:1px solid #d7dce1;border-top:none;">
                    <tr>
                      <td style="padding:14px 20px;">
                        <div style="font-size:10px;color:#57606a;">PASSENGER</div>
                        <div style="font-size:16px;font-weight:bold;">%s</div>
                      </td>
                      <td style="padding:14px 20px;text-align:right;">
                        <div style="font-size:10px;color:#57606a;">BOOKING REFERENCE (PNR)</div>
                        <div style="font-size:18px;font-weight:bold;letter-spacing:2px;">%s</div>
                      </td>
                    </tr>
                  </table>

                  <table width="100%%" style="margin-top:14px;border:1px solid #d7dce1;">
                    <tr>
                      <td style="padding:16px 18px;width:32%%;text-align:center;">
                        <div style="font-size:24px;font-weight:bold;letter-spacing:1px;">%s</div>
                        <div style="font-size:10px;color:#57606a;">%s</div>
                      </td>
                      <td style="padding:16px 10px;width:36%%;text-align:center;color:#0b3d91;font-size:12px;">
                        Flight %s
                        <div style="font-size:16px;margin-top:4px;">&gt;&gt;&gt;&gt;&gt;&gt;</div>
                      </td>
                      <td style="padding:16px 18px;width:32%%;text-align:center;">
                        <div style="font-size:24px;font-weight:bold;letter-spacing:1px;">%s</div>
                        <div style="font-size:10px;color:#57606a;">%s</div>
                      </td>
                    </tr>
                  </table>

                  <table width="100%%" style="margin-top:16px;border:1px solid #d7dce1;">
                    <tr style="background-color:#f6f8fa;color:#57606a;font-size:9px;">
                      <td style="padding:8px 10px;">SEAT</td>
                      <td style="padding:8px 10px;">GATE</td>
                      <td style="padding:8px 10px;">BOARDING TIME</td>
                      <td style="padding:8px 10px;">GROUP</td>
                      <td style="padding:8px 10px;">BOARDING PASS NO.</td>
                    </tr>
                    <tr>
                      <td style="padding:10px;font-size:16px;font-weight:bold;">%s</td>
                      <td style="padding:10px;font-size:16px;font-weight:bold;">%s</td>
                      <td style="padding:10px;">%s</td>
                      <td style="padding:10px;">%s</td>
                      <td style="padding:10px;">%s</td>
                    </tr>
                  </table>

                  <table width="100%%" style="margin-top:16px;">
                    <tr>
                      <td style="width:60%%;vertical-align:top;padding:14px 16px;background-color:#f6f8fa;border:1px solid #d7dce1;font-size:9px;color:#57606a;line-height:1.6;">
                        <div style="font-weight:bold;color:#1f2328;font-size:10px;margin-bottom:4px;">BOARDING NOTES</div>
                        <div>- Arrive at the gate at least 20 minutes before boarding time.</div>
                        <div>- Carry a valid photo ID matching the name on this pass.</div>
                        <div>- This pass becomes invalid once used or if the flight is cancelled.</div>
                      </td>
                      <td style="width:4%%;"></td>
                      <td style="width:36%%;vertical-align:top;text-align:center;padding:14px 16px;background-color:#f6f8fa;border:1px solid #d7dce1;">
                        %s
                        <div style="font-size:9px;color:#57606a;margin-top:6px;">Scan at the gate</div>
                      </td>
                    </tr>
                  </table>

                  <div style="margin-top:16px;font-size:9px;color:#57606a;">
                    This is a computer-generated boarding pass and does not require a signature.
                  </div>

                </body>
                </html>
                """.formatted(
                escape(nvl(event.getPassengerName(), "-")),
                escape(nvl(event.getBookingReference(), "-")),
                escape(nvl(event.getOriginAirportCode(), "-")),
                escape(nvl(originCity, "")),
                escape(nvl(event.getFlightNumber(), "-")),
                escape(nvl(event.getDestinationAirportCode(), "-")),
                escape(nvl(destinationCity, "")),
                escape(nvl(event.getSeatNumber(), "-")),
                escape(nvl(event.getGate(), "TBA")),
                escape(event.getBoardingTime() != null ? event.getBoardingTime().format(DISPLAY_TIME) : "-"),
                escape(nvl(event.getBoardingGroup(), "-")),
                escape(nvl(event.getBoardingPassNumber(), "-")),
                qrDataUri != null
                        ? "<img src=\"" + qrDataUri + "\" width=\"110\" height=\"110\" alt=\"Boarding pass QR code\"/>"
                        : "");
    }

    private static String nvl(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
