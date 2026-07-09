package com.skybook.praveen.notificationservice.service;

import com.skybook.praveen.common.event.CheckInEvent;
import com.skybook.praveen.notificationservice.service.AirlineLookup.AirlineBrand;
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
 * Styled as a modern "mobile boarding pass" card: airline-branded gradient
 * header with a monogram badge (AirlineLookup - no real logo asset exists
 * anywhere in the system), large IATA route type, chip-style
 * seat/gate/boarding-time/group cards, a decorative faux-barcode strip next
 * to the real QR code, and a dashed tear-line to read as a physical pass
 * stub rather than a plain document.
 *
 * Deliberately its own template rather than reusing TicketPdfTemplate - a
 * boarding pass is a different, simpler document (single passenger, gate/
 * seat/boarding-time focused) than a full e-ticket/itinerary.
 */
@Component
public class BoardingPassPdfTemplate {

    private static final DateTimeFormatter DISPLAY_TIME = DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm");

    public String render(CheckInEvent event, byte[] qrPng) {

        AirlineBrand brand = AirlineLookup.forFlightNumber(event.getFlightNumber());
        String originCity = AirportCityLookup.cityFor(event.getOriginAirportCode());
        String destinationCity = AirportCityLookup.cityFor(event.getDestinationAirportCode());

        String qrDataUri = qrPng != null
                ? "data:image/png;base64," + Base64.getEncoder().encodeToString(qrPng)
                : null;

        return """
                <html xmlns="http://www.w3.org/1999/xhtml">
                <head>
                <style>
                  @page { size: A4; margin: 28px 34px; }
                  body { font-family: Helvetica, Arial, sans-serif; color: #1f2328; font-size: 11px; }
                  table { border-collapse: collapse; }
                </style>
                </head>
                <body>

                  <table width="100%%" style="background-image: linear-gradient(100deg, %s, %s); border-radius: 14px 14px 0 0;">
                    <tr>
                      <td style="padding:20px 24px;width:60%%;">
                        <div style="color:#ffffff;font-size:23px;font-weight:bold;letter-spacing:0.5px;">SkyBook</div>
                        <div style="color:#ffffff;font-size:10px;letter-spacing:3px;margin-top:2px;opacity:0.85;">MOBILE BOARDING PASS</div>
                      </td>
                      <td style="padding:20px 24px;width:40%%;text-align:right;vertical-align:middle;">
                        <table style="float:right;">
                          <tr>
                            <td style="text-align:right;padding-right:12px;">
                              <div style="color:#ffffff;font-size:12px;font-weight:bold;">%s</div>
                              <div style="color:#ffffff;font-size:8px;letter-spacing:1px;opacity:0.8;">OPERATED BY</div>
                            </td>
                            <td>
                              <table style="width:42px;height:42px;background-color:#ffffff;border-radius:21px;">
                                <tr><td style="text-align:center;vertical-align:middle;height:42px;color:%s;font-size:14px;font-weight:bold;">%s</td></tr>
                              </table>
                            </td>
                          </tr>
                        </table>
                      </td>
                    </tr>
                  </table>

                  <table width="100%%" style="background-color:#ffffff;border:1px solid #d7dce1;border-top:none;border-radius:0 0 14px 14px;">
                    <tr>
                      <td style="padding:20px 24px 4px;">

                        <table width="100%%">
                          <tr>
                            <td>
                              <div style="font-size:9px;color:#8b949e;letter-spacing:1px;">PASSENGER</div>
                              <div style="font-size:17px;font-weight:bold;">%s</div>
                            </td>
                            <td style="text-align:right;">
                              <div style="font-size:9px;color:#8b949e;letter-spacing:1px;">BOOKING REFERENCE</div>
                              <div style="font-size:19px;font-weight:bold;letter-spacing:3px;color:%s;">%s</div>
                            </td>
                          </tr>
                        </table>

                        <table width="100%%" style="margin-top:20px;">
                          <tr>
                            <td style="width:32%%;text-align:center;">
                              <div style="font-size:30px;font-weight:bold;letter-spacing:1px;color:#1f2328;">%s</div>
                              <div style="font-size:10px;color:#57606a;margin-top:2px;">%s</div>
                            </td>
                            <td style="width:36%%;text-align:center;vertical-align:middle;">
                              <div style="font-size:10px;color:%s;font-weight:bold;letter-spacing:1px;">FLIGHT %s</div>
                              <table width="100%%" style="margin-top:8px;">
                                <tr>
                                  <td style="border-top:2px dashed %s;font-size:0;line-height:0;">&#160;</td>
                                  <td style="width:0;">
                                    <div style="width:0;height:0;border-top:6px solid transparent;border-bottom:6px solid transparent;border-left:9px solid %s;"></div>
                                  </td>
                                </tr>
                              </table>
                            </td>
                            <td style="width:32%%;text-align:center;">
                              <div style="font-size:30px;font-weight:bold;letter-spacing:1px;color:#1f2328;">%s</div>
                              <div style="font-size:10px;color:#57606a;margin-top:2px;">%s</div>
                            </td>
                          </tr>
                        </table>

                        <table width="100%%" style="margin-top:20px;">
                          <tr>
                            <td style="width:20%%;padding:0 4px 0 0;">
                              <table width="100%%" style="background-color:#f6f8fa;border-radius:8px;">
                                <tr><td style="height:3px;background-color:%s;border-radius:8px 8px 0 0;"></td></tr>
                                <tr><td style="padding:9px 10px;">
                                  <div style="font-size:8px;color:#8b949e;letter-spacing:1px;">SEAT</div>
                                  <div style="font-size:16px;font-weight:bold;margin-top:2px;">%s</div>
                                </td></tr>
                              </table>
                            </td>
                            <td style="width:20%%;padding:0 4px;">
                              <table width="100%%" style="background-color:#f6f8fa;border-radius:8px;">
                                <tr><td style="height:3px;background-color:%s;border-radius:8px 8px 0 0;"></td></tr>
                                <tr><td style="padding:9px 10px;">
                                  <div style="font-size:8px;color:#8b949e;letter-spacing:1px;">GROUP</div>
                                  <div style="font-size:16px;font-weight:bold;margin-top:2px;">%s</div>
                                </td></tr>
                              </table>
                            </td>
                            <td style="width:20%%;padding:0 4px;">
                              <table width="100%%" style="background-color:#f6f8fa;border-radius:8px;">
                                <tr><td style="height:3px;background-color:%s;border-radius:8px 8px 0 0;"></td></tr>
                                <tr><td style="padding:9px 10px;">
                                  <div style="font-size:8px;color:#8b949e;letter-spacing:1px;">GATE</div>
                                  <div style="font-size:16px;font-weight:bold;margin-top:2px;">%s</div>
                                </td></tr>
                              </table>
                            </td>
                            <td style="width:40%%;padding:0 0 0 4px;">
                              <table width="100%%" style="background-color:#f6f8fa;border-radius:8px;">
                                <tr><td style="height:3px;background-color:%s;border-radius:8px 8px 0 0;"></td></tr>
                                <tr><td style="padding:9px 10px;">
                                  <div style="font-size:8px;color:#8b949e;letter-spacing:1px;">BOARDING TIME</div>
                                  <div style="font-size:16px;font-weight:bold;margin-top:2px;">%s</div>
                                </td></tr>
                              </table>
                            </td>
                          </tr>
                        </table>

                      </td>
                    </tr>
                    <tr>
                      <td style="padding:18px 0;">
                        <table width="100%%">
                          <tr>
                            <td style="width:6%%;"></td>
                            <td style="border-top:2px dashed #d7dce1;font-size:0;line-height:0;">&#160;</td>
                            <td style="width:6%%;"></td>
                          </tr>
                        </table>
                      </td>
                    </tr>
                    <tr>
                      <td style="padding:0 24px 22px;">
                        <table width="100%%">
                          <tr>
                            <td style="width:56%%;vertical-align:middle;">
                              <div style="font-size:8px;color:#8b949e;letter-spacing:1px;margin-bottom:8px;">BOARDING PASS NO. %s</div>
                              <table>%s</table>
                            </td>
                            <td style="width:6%%;"></td>
                            <td style="width:38%%;text-align:center;vertical-align:top;padding:10px 12px;background-color:#f6f8fa;border-radius:8px;">
                              %s
                              <div style="font-size:8px;color:#57606a;margin-top:6px;">Scan at the gate</div>
                            </td>
                          </tr>
                        </table>
                      </td>
                    </tr>
                  </table>

                  <div style="margin-top:14px;padding:12px 16px;background-color:#f6f8fa;border:1px solid #e5e7eb;border-radius:8px;font-size:9px;color:#57606a;line-height:1.6;">
                    <div style="font-weight:bold;color:#1f2328;font-size:10px;margin-bottom:4px;">BOARDING NOTES</div>
                    <div>- Arrive at the gate at least 20 minutes before boarding time.</div>
                    <div>- Carry a valid photo ID matching the name on this pass.</div>
                    <div>- This pass becomes invalid once used or if the flight is cancelled.</div>
                  </div>

                  <div style="margin-top:12px;font-size:9px;color:#8b949e;text-align:center;">
                    This is a computer-generated boarding pass and does not require a signature.
                  </div>

                </body>
                </html>
                """.formatted(
                brand.primaryColor(), brand.secondaryColor(),
                escape(brand.displayName()), brand.primaryColor(), escape(brand.code()),
                escape(nvl(event.getPassengerName(), "-")),
                brand.primaryColor(), escape(nvl(event.getBookingReference(), "-")),
                escape(nvl(event.getOriginAirportCode(), "-")),
                escape(nvl(originCity, "")),
                brand.primaryColor(), escape(nvl(event.getFlightNumber(), "-")),
                brand.secondaryColor(),
                brand.secondaryColor(),
                escape(nvl(event.getDestinationAirportCode(), "-")),
                escape(nvl(destinationCity, "")),
                brand.primaryColor(), escape(nvl(event.getSeatNumber(), "-")),
                brand.secondaryColor(), escape(nvl(event.getBoardingGroup(), "-")),
                brand.primaryColor(), escape(nvl(event.getGate(), "TBA")),
                brand.secondaryColor(),
                escape(event.getBoardingTime() != null ? event.getBoardingTime().format(DISPLAY_TIME) : "-"),
                escape(nvl(event.getBoardingPassNumber(), "-")),
                barcodeCells(nvl(event.getBoardingPassNumber(), event.getBookingReference())),
                qrDataUri != null
                        ? "<img src=\"" + qrDataUri + "\" width=\"104\" height=\"104\" alt=\"Boarding pass QR code\"/>"
                        : "");
    }

    /**
     * Purely decorative faux-barcode (the QR code carries the real payload) -
     * a row of <td> bars with deterministic pseudo-random widths derived
     * from the boarding pass number, so the same pass always renders the
     * same "barcode". Built from plain table cells rather than a CSS
     * repeating-gradient since openhtmltopdf's support for
     * repeating-linear-gradient is unreliable, while table/background-color
     * is guaranteed to render.
     */
    private static String barcodeCells(String seed) {
        StringBuilder cells = new StringBuilder("<tr>");
        long state = 0;
        for (int i = 0; i < seed.length(); i++) {
            state = state * 31 + seed.charAt(i);
        }
        if (state < 0) {
            state = -state;
        }
        for (int i = 0; i < 40; i++) {
            state = (state * 1103515245L + 12345L) & 0x7FFFFFFFL;
            int width = 1 + (int) (state % 3);
            boolean bar = (state % 5) != 0;
            cells.append("<td style=\"width:").append(width)
                    .append("px;height:34px;background-color:")
                    .append(bar ? "#1f2328" : "#ffffff")
                    .append(";\"></td>");
        }
        cells.append("</tr>");
        return cells.toString();
    }

    private static String nvl(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
