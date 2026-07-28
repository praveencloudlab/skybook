package com.skybook.praveen.notificationservice.service;

import com.skybook.praveen.common.event.CheckInEvent;
import org.springframework.stereotype.Component;

import java.util.Base64;

/**
 * Renders the emailed boarding pass as strict XHTML for TicketPdfRenderer
 * (openhtmltopdf parses with an XML parser, so every tag must close and every
 * attribute must be quoted; the base-14 PDF fonts carry no plane/arrow/circled
 * glyphs, so decorations use &amp;middot; runs and CSS border triangles).
 *
 * <p>This is the SAME design as the frontend pass (BoardingPassCard/printable):
 * the red SkyBook ticket - red 56px headers with the cabin badge, uppercase
 * passenger + red PNR, light-blue airport boxes with code + full name, a
 * two-row operational grid (FLIGHT/DATE/DEPARTS/BOARDING and
 * GATE/SEAT/CABIN/BOARDING GROUP), the gate-arrival notice with the issue
 * stamp, and a dashed tear-off stub carrying the QR. Field values, the derived
 * boarding clock and the TBA gate all come from {@link BoardingDisplay} so the
 * email matches what the passenger sees on screen, value for value.
 */
@Component
public class BoardingPassPdfTemplate {

    private static final String RED = "#e11b22";
    private static final String BADGE_RED = "#ea484e";
    private static final String BLUE = "#cfe0f5";
    private static final String INK = "#0f172a";
    private static final String LABEL = "#94a3b8";

    public String render(CheckInEvent event, byte[] qrPng) {

        String pnr = nvl(event.getBookingReference(), "-");
        String passenger = nvl(event.getPassengerName(), "-").toUpperCase();
        String fromCode = nvl(event.getOriginAirportCode(), "-");
        String toCode = nvl(event.getDestinationAirportCode(), "-");
        String cabin = BoardingDisplay.cabinLabel(event.getTravelClass());
        String departDate = BoardingDisplay.date(event.getDepartureTime());
        String departTime = BoardingDisplay.clock(event.getDepartureTime());
        String boardTime = BoardingDisplay.clock(BoardingDisplay.effectiveBoarding(event));
        String gateBy = BoardingDisplay.clock(BoardingDisplay.gateBy(event));
        String gate = nvl(event.getGate(), "TBA");
        String group = nvl(event.getBoardingGroup(), "-");
        String issued = BoardingDisplay.stamp(event.getIssuedAt() != null ? event.getIssuedAt() : event.getOccurredAt());
        String passNumber = nvl(event.getBoardingPassNumber(), "-");

        String qrImg = qrPng != null
                ? "<img src=\"data:image/png;base64," + Base64.getEncoder().encodeToString(qrPng)
                + "\" width=\"112\" height=\"112\" alt=\"Boarding pass QR code\"/>"
                : "";

        return """
                <html xmlns="http://www.w3.org/1999/xhtml">
                <head>
                <style>
                  @page { size: A4; margin: 26px 26px; }
                  body { font-family: Helvetica, Arial, sans-serif; color: %s; font-size: 11px; margin: 0; }
                  table { border-collapse: collapse; }
                  .lbl { font-size: 8px; font-weight: bold; letter-spacing: 0.5px; color: %s; }
                  .val { font-size: 13px; font-weight: bold; color: %s; margin-top: 2px; }
                  .mono { font-family: Courier, monospace; }
                </style>
                </head>
                <body>

                  <table width="100%%" style="border:1px solid #e5e7eb;border-radius:16px;">
                    <tr>
                      <!-- Main coupon -->
                      <td style="width:70%%;vertical-align:top;padding:0;">
                        <table width="100%%" style="background-color:%s;border-radius:15px 0 0 0;">
                          <tr>
                            <td style="height:56px;vertical-align:middle;padding:0 20px;color:#ffffff;font-size:21px;font-weight:bold;font-style:italic;">SkyBook</td>
                            <td style="height:56px;vertical-align:middle;text-align:center;color:#ffffff;font-size:13px;font-weight:bold;letter-spacing:3px;">BOARDING PASS</td>
                            <td style="height:56px;vertical-align:middle;text-align:right;padding:0 20px;">
                              <span style="background-color:%s;border-radius:5px;padding:4px 11px;font-size:10px;font-weight:bold;letter-spacing:1px;color:#ffffff;">%s</span>
                            </td>
                          </tr>
                        </table>

                        <table width="100%%">
                          <tr>
                            <td style="padding:16px 20px 4px;vertical-align:bottom;">
                              <div class="lbl" style="letter-spacing:1px;">PASSENGER</div>
                              <div style="font-size:21px;font-weight:bold;letter-spacing:1px;color:%s;">%s</div>
                            </td>
                            <td style="padding:16px 20px 4px;text-align:right;vertical-align:bottom;">
                              <div class="lbl" style="letter-spacing:1px;">BOOKING REF (PNR)</div>
                              <div class="mono" style="font-size:18px;font-weight:bold;color:%s;">%s</div>
                            </td>
                          </tr>
                        </table>

                        <table width="100%%" style="margin-top:8px;">
                          <tr>
                            <td style="padding:0 20px;">
                              <table width="100%%">
                                <tr>
                                  <td style="width:45%%;background-color:%s;border-radius:8px;padding:9px 13px;">
                                    <div class="mono" style="font-size:21px;font-weight:bold;color:%s;">%s</div>
                                    <div style="font-size:10px;color:#334155;margin-top:3px;">%s</div>
                                  </td>
                                  <td style="width:10%%;text-align:center;">
                                    <div style="width:0;height:0;border-top:6px solid transparent;border-bottom:6px solid transparent;border-left:10px solid %s;margin:0 auto;"></div>
                                  </td>
                                  <td style="width:45%%;background-color:%s;border-radius:8px;padding:9px 13px;">
                                    <div class="mono" style="font-size:21px;font-weight:bold;color:%s;">%s</div>
                                    <div style="font-size:10px;color:#334155;margin-top:3px;">%s</div>
                                  </td>
                                </tr>
                              </table>
                            </td>
                          </tr>
                        </table>

                        <table width="100%%" style="margin-top:14px;">
                          <tr>
                            <td style="padding:0 20px;">
                              <table width="100%%">
                                <tr>
                                  <td style="width:25%%;"><div class="lbl">FLIGHT</div><div class="val mono">%s</div></td>
                                  <td style="width:25%%;"><div class="lbl">DATE</div><div class="val">%s</div></td>
                                  <td style="width:25%%;"><div class="lbl">DEPARTS</div><div class="val">%s</div></td>
                                  <td style="width:25%%;"><div class="lbl">BOARDING</div><div class="val" style="color:%s;">%s</div></td>
                                </tr>
                                <tr><td colspan="4" style="height:13px;"></td></tr>
                                <tr>
                                  <td><div class="lbl">GATE</div><div class="val">%s</div></td>
                                  <td><div class="lbl">SEAT</div><div class="val mono">%s</div></td>
                                  <td><div class="lbl">CABIN</div><div class="val">%s</div></td>
                                  <td><div class="lbl">BOARDING GROUP</div><div class="val">%s</div></td>
                                </tr>
                              </table>
                            </td>
                          </tr>
                        </table>

                        <table width="100%%" style="margin-top:14px;border-top:1px solid #eef1f5;">
                          <tr>
                            <td style="padding:10px 20px;" class="mono">
                              <span style="color:%s;font-weight:bold;font-size:10px;">NOTICE:</span>
                              <span style="font-size:10px;color:#64748b;">Please arrive at the boarding gate by %s, 30 minutes before boarding begins at %s. The gate closes before departure and late passengers may be offloaded.</span>
                            </td>
                            <td style="padding:10px 20px;text-align:right;white-space:nowrap;vertical-align:top;" class="mono">
                              <span style="font-size:10px;color:#64748b;">Issued %s</span>
                            </td>
                          </tr>
                        </table>
                      </td>

                      <!-- Perforated tear-off stub -->
                      <td style="width:30%%;vertical-align:top;padding:0;border-left:2px dashed #cbd5e1;">
                        <table width="100%%" style="background-color:%s;border-radius:0 15px 0 0;">
                          <tr>
                            <td style="height:56px;vertical-align:middle;text-align:center;color:#ffffff;font-size:12px;font-weight:bold;letter-spacing:1px;">&#183;&#183;&#183; BOARDING PASS &#183;&#183;&#183;</td>
                          </tr>
                        </table>
                        <table width="100%%">
                          <tr>
                            <td style="padding:14px 16px;">
                              <div style="font-size:7px;font-weight:bold;color:%s;letter-spacing:0.5px;">PASSENGER</div>
                              <div style="font-size:15px;font-weight:bold;color:%s;margin-bottom:10px;">%s</div>

                              <table width="100%%" style="margin-bottom:10px;">
                                <tr>
                                  <td style="vertical-align:middle;">
                                    <div style="font-size:7px;font-weight:bold;color:%s;">FLIGHT</div>
                                    <div class="mono" style="font-size:12px;font-weight:bold;color:%s;">%s</div>
                                  </td>
                                  <td style="text-align:right;vertical-align:middle;">
                                    <table style="float:right;"><tr>
                                      <td class="mono" style="font-size:17px;font-weight:bold;color:%s;">%s</td>
                                      <td style="padding:0 4px;"><div style="width:0;height:0;border-top:5px solid transparent;border-bottom:5px solid transparent;border-left:8px solid %s;"></div></td>
                                      <td class="mono" style="font-size:17px;font-weight:bold;color:%s;">%s</td>
                                    </tr></table>
                                  </td>
                                </tr>
                              </table>

                              <table width="100%%" style="margin-bottom:12px;">
                                <tr>
                                  <td><div style="font-size:7px;font-weight:bold;color:%s;">SEAT</div><div class="mono" style="font-size:12px;font-weight:bold;color:%s;">%s</div></td>
                                  <td><div style="font-size:7px;font-weight:bold;color:%s;">GRP</div><div style="font-size:12px;font-weight:bold;color:%s;">%s</div></td>
                                  <td><div style="font-size:7px;font-weight:bold;color:%s;">BOARD</div><div style="font-size:12px;font-weight:bold;color:%s;">%s</div></td>
                                </tr>
                              </table>

                              <div style="text-align:center;">%s</div>
                              <div class="mono" style="text-align:center;font-size:9px;letter-spacing:1px;color:#334155;margin-top:6px;">%s</div>
                              <div style="text-align:center;font-size:9px;color:#64748b;margin-top:2px;">PNR <b>%s</b></div>
                            </td>
                          </tr>
                        </table>
                      </td>
                    </tr>
                  </table>

                  <div style="margin-top:12px;font-size:9px;color:%s;text-align:center;">
                    This is a computer-generated boarding pass and does not require a signature.
                  </div>

                </body>
                </html>
                """.formatted(
                INK, LABEL, INK,
                // main header
                RED, BADGE_RED, escape(cabin.toUpperCase()),
                // passenger + pnr
                INK, escape(passenger), RED, escape(pnr),
                // airports
                BLUE, INK, escape(fromCode), escape(BoardingDisplay.airportLabel(fromCode)),
                RED,
                BLUE, INK, escape(toCode), escape(BoardingDisplay.airportLabel(toCode)),
                // grid row 1
                escape(nvl(event.getFlightNumber(), "-")), escape(departDate), escape(departTime), RED, escape(boardTime),
                // grid row 2
                escape(gate), escape(nvl(event.getSeatNumber(), "-")), escape(cabin), escape(group),
                // notice + issued
                RED, escape(gateBy), escape(boardTime), escape(issued),
                // stub
                RED,
                LABEL, INK, escape(passenger),
                LABEL, INK, escape(nvl(event.getFlightNumber(), "-")),
                INK, escape(fromCode), RED, INK, escape(toCode),
                LABEL, INK, escape(nvl(event.getSeatNumber(), "-")),
                LABEL, INK, escape(group),
                LABEL, RED, escape(boardTime),
                qrImg, escape(passNumber), escape(pnr),
                LABEL);
    }

    private static String nvl(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
