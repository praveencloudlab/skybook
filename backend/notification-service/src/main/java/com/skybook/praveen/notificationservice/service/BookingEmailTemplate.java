package com.skybook.praveen.notificationservice.service;

import com.skybook.praveen.common.event.BookingEvent;
import com.skybook.praveen.common.event.BookingEventPassenger;
import com.skybook.praveen.common.time.AirportTimeZones;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Renders the booking notification email from a BookingEvent's structured
 * fields. Plain inline-CSS HTML - no template engine dependency; email
 * clients ignore external stylesheets anyway.
 *
 * Degrades gracefully: events without flight context skip the route card;
 * events without structured details never reach this class (the consumer
 * falls back to plain text).
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

        // Multi-segment bookings group passenger rows under a per-leg header
        // (ROUND_TRIP_MODULE.md §6); old/single-leg events keep the flat list.
        StringBuilder passengers = new StringBuilder();
        if (event.getSegments() != null && event.getSegments().size() > 1) {
            for (var segment : event.getSegments()) {
                passengers.append("""
                        <tr>
                          <td colspan="5" style="padding:8px 12px;border-top:1px solid #e5e7eb;background:#eef2f7;font-weight:600;color:#0b3d91;">
                            %s &middot; %s &rarr; %s
                          </td>
                        </tr>
                        """.formatted(
                        escape(segmentLabel(segment.getSegmentIndex())),
                        escape(nvl(segment.getOriginAirportCode(), "?")),
                        escape(nvl(segment.getDestinationAirportCode(), "?"))));
                if (segment.getPassengers() != null) {
                    for (BookingEventPassenger p : segment.getPassengers()) {
                        passengers.append(passengerRow(p, event.getCurrency()));
                    }
                }
            }
        } else if (event.getPassengers() != null) {
            for (BookingEventPassenger p : event.getPassengers()) {
                passengers.append(passengerRow(p, event.getCurrency()));
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
                          <div style="font-weight:600;">%s</div>
                          <div style="color:#57606a;font-size:12px;margin-top:4px;">Booked %s</div>
                        </td>
                      </tr>
                    </table>

                    %s

                    <h3 style="font-size:14px;margin:22px 0 8px;color:#57606a;text-transform:uppercase;letter-spacing:.04em;">Passengers</h3>
                    <table style="width:100%%;border-collapse:collapse;font-size:14px;border:1px solid #e5e7eb;border-radius:8px;">
                      <tr style="background:#f6f8fa;color:#57606a;font-size:12px;">
                        <td style="padding:8px 12px;">NAME</td>
                        <td style="padding:8px 12px;text-align:center;">SEAT</td>
                        <td style="padding:8px 12px;">CLASS · FARE</td>
                        <td style="padding:8px 12px;">CHECK-IN</td>
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
                escape(event.getFlightNumber() != null ? event.getFlightNumber()
                        : (event.getFlightId() != null ? "#" + event.getFlightId() : "—")),
                escape(nvl(event.getBookingDate(), "—")),
                routeCard(event),
                passengers,
                money(event.getTotalFare(), event.getCurrency()),
                "PAID".equals(event.getPaymentStatus()) ? "#1a7f37" : "#b45309",
                escape(nvl(event.getPaymentStatus(), "PENDING")),
                includeQr ? qrBlock() : "",
                escape(nvl(event.getBookingReference(), "")));
    }

    private static String passengerRow(BookingEventPassenger p, String currency) {
        return """
                <tr>
                  <td style="padding:8px 12px;border-top:1px solid #e5e7eb;">%s</td>
                  <td style="padding:8px 12px;border-top:1px solid #e5e7eb;text-align:center;"><b>%s</b></td>
                  <td style="padding:8px 12px;border-top:1px solid #e5e7eb;">%s · %s</td>
                  <td style="padding:8px 12px;border-top:1px solid #e5e7eb;">%s</td>
                  <td style="padding:8px 12px;border-top:1px solid #e5e7eb;text-align:right;">%s</td>
                </tr>
                """.formatted(
                escape(p.getName()),
                escape(nvl(p.getSeatNumber(), "—")),
                escape(pretty(p.getTravelClass())),
                escape(pretty(p.getFareType())),
                escape(pretty(nvl(p.getCheckInStatus(), "NOT_OPEN"))),
                money(p.getFare(), currency));
    }

    private static String segmentLabel(Integer segmentIndex) {
        if (segmentIndex == null || segmentIndex == 0) {
            return "Outbound";
        }
        return segmentIndex == 1 ? "Return" : "Leg " + (segmentIndex + 1);
    }

    /**
     * Route + times: one card per segment when the event carries them
     * (ROUND_TRIP_MODULE.md §6), else the single legacy card from the
     * top-level flight fields.
     */
    private static String routeCard(BookingEvent event) {
        if (event.getSegments() != null && !event.getSegments().isEmpty()) {
            StringBuilder cards = new StringBuilder();
            for (var segment : event.getSegments()) {
                cards.append(card(
                        event.getSegments().size() > 1 ? segmentLabel(segment.getSegmentIndex()) : "",
                        segment.getFlightNumber(),
                        segment.getOriginAirportCode(), segment.getDestinationAirportCode(),
                        segment.getDepartureTime(), segment.getArrivalTime(),
                        segment.getDepartureTerminal(), segment.getArrivalTerminal()));
            }
            return cards.toString();
        }
        return card("", event.getFlightNumber(),
                event.getOriginAirportCode(), event.getDestinationAirportCode(),
                event.getDepartureTime(), event.getArrivalTime(), null, null);
    }

    private static String card(String label, String flightNumber,
                               String origin, String destination,
                               String departureTime, String arrivalTime,
                               String departureTerminal, String arrivalTerminal) {
        if (origin == null || destination == null) {
            return "";
        }
        // Real terminals ride the event since the terminals feature; older
        // events have none and simply show the city alone.
        //
        // Escaped here, as the parts go together, rather than at the format
        // call: the separator is a &middot; entity, so escaping the finished
        // string rewrites its ampersand and the traveller reads a literal
        // "London &middot; Terminal 5". City and terminal are still escaped
        // individually - both arrive from the event, neither is ours to trust.
        String originCity = escape(nvl(AirportCityLookup.cityFor(origin), ""));
        String destinationCity = escape(nvl(AirportCityLookup.cityFor(destination), ""));
        if (departureTerminal != null && !departureTerminal.isBlank()) {
            originCity = originCity + " &middot; Terminal " + escape(departureTerminal);
        }
        if (arrivalTerminal != null && !arrivalTerminal.isBlank()) {
            destinationCity = destinationCity + " &middot; Terminal " + escape(arrivalTerminal);
        }
        String duration = flightDuration(departureTime, arrivalTime, origin, destination);
        String durationLabel = duration.isEmpty() ? escape(nvl(flightNumber, "")) : duration;
        String labelRow = label == null || label.isEmpty() ? "" : """
                  <tr>
                    <td colspan="3" style="padding:8px 16px 0;color:#0b3d91;font-size:12px;font-weight:700;text-transform:uppercase;letter-spacing:.06em;">%s</td>
                  </tr>
                """.formatted(escape(label));

        String checkInOpens = checkInOpens(departureTime);
        String checkInRow = checkInOpens.isEmpty() ? "" : """
                  <tr>
                    <td colspan="3" style="border-top:1px solid #e5e7eb;padding:9px 16px;text-align:center;color:#57606a;font-size:12px;">
                      &#128336; Online check-in opens 24 hours before departure &mdash; around <b>%s</b>.
                    </td>
                  </tr>
                """.formatted(escape(checkInOpens));

        // Email-safe route line: a plane centred between two rules drawn with
        // cell border-bottom (box-drawing characters render inconsistently across
        // mail clients, which is what made the old line look broken).
        return """
                <table style="width:100%%;border-collapse:collapse;margin-top:12px;background:#f6f8fa;border:1px solid #e5e7eb;border-radius:8px;font-size:14px;">
                  %s
                  <tr>
                    <td style="padding:14px 12px;text-align:center;width:33%%;">
                      <div style="font-size:26px;font-weight:700;letter-spacing:1px;">%s</div>
                      <div style="color:#57606a;font-size:12px;">%s</div>
                      <div style="color:#57606a;font-size:12px;">Departs<br>%s</div>
                    </td>
                    <td style="padding:14px 4px;text-align:center;width:34%%;">
                      <div style="color:#57606a;font-size:12px;margin-bottom:5px;">%s</div>
                      <table style="width:100%%;border-collapse:collapse;">
                        <tr>
                          <td style="border-bottom:2px solid #c8d0da;font-size:0;line-height:0;">&nbsp;</td>
                          <td style="padding:0 6px;color:#0b3d91;font-size:17px;white-space:nowrap;vertical-align:middle;">&#9992;</td>
                          <td style="border-bottom:2px solid #c8d0da;font-size:0;line-height:0;">&nbsp;</td>
                        </tr>
                      </table>
                      <div style="color:#8a94a6;font-size:11px;margin-top:5px;">%s &middot; Direct</div>
                    </td>
                    <td style="padding:14px 12px;text-align:center;width:33%%;">
                      <div style="font-size:26px;font-weight:700;letter-spacing:1px;">%s</div>
                      <div style="color:#57606a;font-size:12px;">%s</div>
                      <div style="color:#57606a;font-size:12px;">Arrives<br>%s</div>
                    </td>
                  </tr>
                  %s
                </table>
                """.formatted(
                labelRow,
                escape(origin),
                originCity,
                escape(nvl(departureTime, "—")),
                durationLabel,
                escape(nvl(flightNumber, "")),
                escape(destination),
                destinationCity,
                escape(nvl(arrivalTime, "—")),
                checkInRow);
    }

    /** Parses the event's loosely-formatted local time ("yyyy-MM-dd[ T]HH:mm[...]"). */
    private static java.time.LocalDateTime parseTime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            String iso = value.trim().replace(' ', 'T');
            if (iso.length() > 16) {
                iso = iso.substring(0, 16);
            }
            return java.time.LocalDateTime.parse(iso);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * "8h 10m" in the air, or "" if either time cannot be read.
     *
     * <p>Both times are wall clocks at their OWN airport, so subtracting them
     * directly measures nothing: LHR 21:25 to SIN 10:30 is thirteen hours of
     * flying and reads as thirteen hours only by the accident of the two zones
     * cancelling out. Each end is therefore resolved to a real instant on its
     * own zone first, and the elapsed time taken between those.
     */
    private static String flightDuration(String departureTime, String arrivalTime,
                                         String origin, String destination) {
        java.time.LocalDateTime dep = parseTime(departureTime);
        java.time.LocalDateTime arr = parseTime(arrivalTime);
        if (dep == null || arr == null || origin == null || destination == null) {
            return "";
        }
        long minutes = AirportTimeZones.elapsedBetween(origin, dep, destination, arr).toMinutes();
        if (minutes <= 0) {
            return "";
        }
        long hours = minutes / 60;
        long mins = minutes % 60;
        return mins == 0 ? hours + "h" : hours + "h " + mins + "m";
    }

    /** When online check-in opens (24h before departure), or "" if unknown. */
    private static String checkInOpens(String departureTime) {
        java.time.LocalDateTime dep = parseTime(departureTime);
        if (dep == null) {
            return "";
        }
        java.time.LocalDateTime open = dep.minusHours(24);
        return String.format("%s %02d:%02d", open.toLocalDate(), open.getHour(), open.getMinute());
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
        String symbol = "GBP".equals(currency) ? "&#163;" : "USD".equals(currency) ? "US$" : (currency != null ? currency + " " : "");
        return symbol + amount;
    }

    /** "PREMIUM_ECONOMY" -> "Premium economy", "NOT_OPEN" -> "Not open" */
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
