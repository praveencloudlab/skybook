package com.skybook.praveen.notificationservice.service;

import com.skybook.praveen.common.event.BookingEvent;
import com.skybook.praveen.common.event.BookingEventPassenger;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Base64;

/**
 * Renders the downloadable e-ticket as strict XHTML for {@link TicketPdfRenderer}
 * (openhtmltopdf parses with an XML parser, unlike browsers - every tag must
 * close, every attribute must be quoted). Kept separate from
 * {@link BookingEmailTemplate}'s HTML (which targets email clients, not a PDF
 * renderer) rather than sharing markup between the two.
 *
 * Only rendered for CONFIRMED bookings (see BookingEventConsumer) - a ticket
 * for an unpaid booking isn't a real ticket yet.
 */
@Component
public class TicketPdfTemplate {

    public String render(BookingEvent event, byte[] qrPng) {

        // Multi-segment: group traveller rows per leg (a row here is one
        // COUPON of the traveller's ticket - ROUND_TRIP_MODULE.md).
        StringBuilder passengerRows = new StringBuilder();
        if (event.getSegments() != null && event.getSegments().size() > 1) {
            for (var segment : event.getSegments()) {
                passengerRows.append("""
                        <tr>
                          <td colspan="7" style="padding:7px 10px;border-top:1px solid #d7dce1;background-color:#eef2f7;font-weight:bold;color:#0b3d91;">
                            Coupon %s &#8226; %s &#8594; %s (%s)
                          </td>
                        </tr>
                        """.formatted(
                        segment.getSegmentIndex() != null ? segment.getSegmentIndex() + 1 : 1,
                        escape(nvl(segment.getOriginAirportCode(), "?")),
                        escape(nvl(segment.getDestinationAirportCode(), "?")),
                        escape(segmentLabel(segment.getSegmentIndex()))));
                if (segment.getPassengers() != null) {
                    for (BookingEventPassenger p : segment.getPassengers()) {
                        passengerRows.append(passengerRow(p, event.getCurrency()));
                    }
                }
            }
        } else if (event.getPassengers() != null) {
            for (BookingEventPassenger p : event.getPassengers()) {
                passengerRows.append(passengerRow(p, event.getCurrency()));
            }
        }

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
                      <td style="padding:16px 20px;color:#ffffff;font-size:11px;text-align:right;letter-spacing:2px;">E-TICKET / ITINERARY</td>
                    </tr>
                  </table>

                  <table width="100%%" style="background-color:#f6f8fa;border:1px solid #d7dce1;border-top:none;">
                    <tr>
                      <td style="padding:14px 20px;">
                        <div style="font-size:10px;color:#57606a;">BOOKING REFERENCE (PNR)</div>
                        <div style="font-size:22px;font-weight:bold;letter-spacing:2px;">%s</div>
                      </td>
                      <td style="padding:14px 20px;text-align:right;">
                        <div style="font-size:10px;color:#57606a;">STATUS</div>
                        <div style="font-size:14px;font-weight:bold;color:#1a7f37;">CONFIRMED</div>
                        <div style="font-size:10px;color:#57606a;margin-top:4px;">Booked %s</div>
                      </td>
                    </tr>
                  </table>

                  %s

                  <div style="margin-top:16px;font-size:11px;font-weight:bold;color:#57606a;text-transform:uppercase;letter-spacing:1px;">Travellers</div>
                  <table width="100%%" style="margin-top:6px;border:1px solid #d7dce1;">
                    <tr style="background-color:#f6f8fa;color:#57606a;font-size:9px;">
                      <td style="padding:8px 10px;">NAME</td>
                      <td style="padding:8px 10px;">E-TICKET</td>
                      <td style="padding:8px 10px;text-align:center;">SEAT</td>
                      <td style="padding:8px 10px;">CLASS</td>
                      <td style="padding:8px 10px;">FARE TYPE</td>
                      <td style="padding:8px 10px;">CHECK-IN</td>
                      <td style="padding:8px 10px;text-align:right;">FARE</td>
                    </tr>
                    %s
                  </table>

                  <table width="100%%" style="margin-top:16px;">
                    <tr>
                      <td style="width:60%%;vertical-align:top;padding:14px 16px;background-color:#f6f8fa;border:1px solid #d7dce1;">
                        <div style="color:#57606a;font-size:10px;">TOTAL FARE</div>
                        <div style="font-size:18px;font-weight:bold;">%s</div>
                        <div style="color:#57606a;font-size:10px;margin-top:6px;">Payment status: <span style="font-weight:bold;color:%s;">%s</span></div>
                      </td>
                      <td style="width:4%%;"></td>
                      <td style="width:36%%;vertical-align:top;text-align:center;padding:14px 16px;background-color:#f6f8fa;border:1px solid #d7dce1;">
                        %s
                        <div style="font-size:9px;color:#57606a;margin-top:6px;">Present this QR code at check-in</div>
                      </td>
                    </tr>
                  </table>

                  <div style="margin-top:20px;font-size:9px;color:#57606a;line-height:1.6;">
                    <div style="font-weight:bold;color:#1f2328;font-size:10px;margin-bottom:4px;">TRAVEL NOTES</div>
                    <div>- Carry a valid photo ID matching the traveller name on this ticket; it will be required at check-in.</div>
                    <div>- Check-in counters typically close 45 minutes before departure - arrive early.</div>
                    <div>- Keep your booking reference (PNR) handy for all communication about this booking.</div>
                    <div>- Baggage allowance follows the fare type selected at booking; verify limits before packing.</div>
                    <div style="margin-top:8px;">This is a computer-generated e-ticket and does not require a signature. For help with this booking, use the SkyBook booking reference above when contacting support.</div>
                  </div>

                </body>
                </html>
                """.formatted(
                escape(nvl(event.getBookingReference(), "-")),
                escape(nvl(event.getBookingDate(), "-")),
                routeTables(event),
                passengerRows,
                money(event.getTotalFare(), event.getCurrency()),
                "PAID".equals(event.getPaymentStatus()) ? "#1a7f37" : "#b45309",
                escape(nvl(event.getPaymentStatus(), "PENDING")),
                qrDataUri != null
                        ? "<img src=\"" + qrDataUri + "\" width=\"110\" height=\"110\" alt=\"Booking QR code\"/>"
                        : "");
    }

    /** One route table per segment when the event carries them, else the legacy single table. */
    private static String routeTables(BookingEvent event) {
        if (event.getSegments() != null && !event.getSegments().isEmpty()) {
            StringBuilder tables = new StringBuilder();
            for (var segment : event.getSegments()) {
                String label = event.getSegments().size() > 1
                        ? segmentLabel(segment.getSegmentIndex()) + " - Flight "
                        : "Flight ";
                tables.append(routeTable(
                        segment.getOriginAirportCode(), segment.getDepartureTime(),
                        label + nvl(segment.getFlightNumber(), "-"),
                        segment.getDestinationAirportCode(), segment.getArrivalTime(),
                        segment.getDepartureTerminal(), segment.getArrivalTerminal()));
            }
            return tables.toString();
        }
        return routeTable(event.getOriginAirportCode(), event.getDepartureTime(),
                "Flight " + nvl(event.getFlightNumber(), "-"),
                event.getDestinationAirportCode(), event.getArrivalTime(), null, null);
    }

    private static String routeTable(String origin, String departureTime,
                                     String flightLabel, String destination, String arrivalTime,
                                     String departureTerminal, String arrivalTerminal) {
        // Real terminals from the event (flight-service TerminalPolicy);
        // old events carry none and print the city alone.
        String originCity = nvl(AirportCityLookup.cityFor(origin), "");
        String destinationCity = nvl(AirportCityLookup.cityFor(destination), "");
        if (departureTerminal != null && !departureTerminal.isBlank()) {
            originCity = originCity + " - Terminal " + departureTerminal;
        }
        if (arrivalTerminal != null && !arrivalTerminal.isBlank()) {
            destinationCity = destinationCity + " - Terminal " + arrivalTerminal;
        }
        return """
                  <table width="100%%" style="margin-top:14px;border:1px solid #d7dce1;">
                    <tr>
                      <td style="padding:16px 18px;width:32%%;text-align:center;">
                        <div style="font-size:24px;font-weight:bold;letter-spacing:1px;">%s</div>
                        <div style="font-size:10px;color:#57606a;">%s</div>
                        <div style="font-size:10px;color:#57606a;margin-top:6px;">Departs<br/>%s</div>
                      </td>
                      <td style="padding:16px 10px;width:36%%;text-align:center;color:#0b3d91;font-size:12px;">
                        %s
                        <div style="font-size:16px;margin-top:4px;">&gt;&gt;&gt;&gt;&gt;&gt;</div>
                      </td>
                      <td style="padding:16px 18px;width:32%%;text-align:center;">
                        <div style="font-size:24px;font-weight:bold;letter-spacing:1px;">%s</div>
                        <div style="font-size:10px;color:#57606a;">%s</div>
                        <div style="font-size:10px;color:#57606a;margin-top:6px;">Arrives<br/>%s</div>
                      </td>
                    </tr>
                  </table>
                """.formatted(
                escape(nvl(origin, "-")),
                escape(originCity),
                escape(nvl(departureTime, "-")),
                escape(flightLabel),
                escape(nvl(destination, "-")),
                escape(destinationCity),
                escape(nvl(arrivalTime, "-")));
    }

    /** Displayed 125-XXXXXXXXXX from the raw 13 digits; "-" pre-ticketing. */
    private static String prettyTicket(String ticketNumber) {
        if (ticketNumber == null || ticketNumber.isBlank()) {
            return "-";
        }
        return ticketNumber.length() > 3
                ? ticketNumber.substring(0, 3) + "-" + ticketNumber.substring(3)
                : ticketNumber;
    }

    private static String segmentLabel(Integer segmentIndex) {
        if (segmentIndex == null || segmentIndex == 0) {
            return "Outbound";
        }
        return segmentIndex == 1 ? "Return" : "Leg " + (segmentIndex + 1);
    }

    private static String passengerRow(BookingEventPassenger p, String currency) {
        return """
                <tr>
                  <td style="padding:8px 10px;border-top:1px solid #d7dce1;">%s</td>
                  <td style="padding:8px 10px;border-top:1px solid #d7dce1;">%s</td>
                  <td style="padding:8px 10px;border-top:1px solid #d7dce1;text-align:center;">%s</td>
                  <td style="padding:8px 10px;border-top:1px solid #d7dce1;">%s</td>
                  <td style="padding:8px 10px;border-top:1px solid #d7dce1;">%s</td>
                  <td style="padding:8px 10px;border-top:1px solid #d7dce1;">%s</td>
                  <td style="padding:8px 10px;border-top:1px solid #d7dce1;text-align:right;">%s</td>
                </tr>
                """.formatted(
                escape(p.getName()),
                escape(prettyTicket(p.getTicketNumber())),
                escape(nvl(p.getSeatNumber(), "-")),
                escape(pretty(p.getTravelClass())),
                escape(pretty(p.getFareType())),
                escape(pretty(nvl(p.getCheckInStatus(), "NOT_OPEN"))),
                money(p.getFare(), currency));
    }

    private static String money(BigDecimal amount, String currency) {
        if (amount == null) return "-";
        String symbol = "GBP".equals(currency) ? "&#163;" : "USD".equals(currency) ? "US$" : (currency != null ? currency + " " : "");
        return symbol + amount;
    }

    private static String pretty(String enumName) {
        if (enumName == null) return "-";
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
