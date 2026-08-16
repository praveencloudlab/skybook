package com.skybook.praveen.notificationservice.service;

import com.skybook.praveen.common.event.BookingEvent;
import com.skybook.praveen.common.event.BookingEventPassenger;
import com.skybook.praveen.common.event.BookingEventSegment;
import com.skybook.praveen.common.time.AirportTimeZones;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders the emailed e-ticket as strict XHTML for {@link TicketPdfRenderer}
 * (openhtmltopdf parses with an XML parser - every tag closed, every
 * attribute quoted; base-14 fonts carry no plane/arrow glyphs, so separators
 * are dashes and dot runs).
 *
 * <p>This is the SAME design as the frontend's downloadable e-ticket
 * (printable.ts, the user-chosen "Style C ticket-office ledger"): maroon
 * header band with the tagline, passenger + barcode block, the ELECTRONIC
 * TICKET RECEIPT band, the grey itinerary table with per-segment detail rows
 * (class/baggage/fare-basis, NVB/NVA, duration), the PASSENGER(S) table, and
 * the monospace FARE CALCULATION ledger with dotted leaders. Field values
 * come from the booking event so the attachment matches the download, value
 * for value.
 *
 * Only rendered for CONFIRMED bookings (see BookingEventConsumer) - a ticket
 * for an unpaid booking isn't a real ticket yet.
 */
@Component
public class TicketPdfTemplate {

    private static final String MAROON = "#5a1836";

    /** The app's brand plane mark, white on the header maroon (SVG rasterized
     * once - openhtmltopdf renders no inline SVG, and base-14 fonts carry no
     * plane glyph). */
    private static final String PLANE_PNG = "iVBORw0KGgoAAAANSUhEUgAAAGAAAABgCAYAAADimHc4AAAABHNCSVQICAgIfAhkiAAAAAFzUkdCAK7OHOkAAAPpSURBVHic7Zy/ixVXFMe/V2Jgkdi6gqUQt7NKUhps3AhhQ1IEMZCAhU2w9j+wE6y229ZGVsSsEiLZIs0GQSt/oJ0xsUyyoGiEj80deRl8vnff3DvnvnnnA69Z7j3n3PPdud+ZN/NGchzHcRzHcRzHqRjgO+AK8DR+rgCnresaPMAycIvx/AwcsK5zsAC/vaf5Db9Y1zlIgDNTNL/he+t6U9ljXcAUfJMw9uuCdSwmwN8JR8BT63pTCdYFTAIgZXwIofo1jTIPW9CgcQGMcQGMcQGMcQGMcQGMcQGMcQGMcQGMcQGMcQGMcQGMcQGMcQGMcQGMcQGMcQGMcQGMcQGMcQGMcQGMqVYA4BCwOcO8TeBQmaoWAGAPcA7YTXgeqM1ujFHtP1iVAEeBOx0a3+YOcNR6XdUD7AMuAq8zNr/hdYy9z3qdVQKcBJ4UaHybJ8BJ6/VWQ2OyPTS+zWKbdCaT7cpimnQBk+3KYph0YZPtyrBNukeT7cqwTNrQZLsy3yZdicl2pTeTzvprkmhoG5KGYmx3Jf0QQrhbKkEWhRuTlXR7QM1XXMvtkibd+QiIxrUuaX73zen4Q9LZEMJPOYPOfASMfF18fQGar7jG67lNOlmAxmQl3Ze0lquQOWJN0v1cJp20BQ3QZLvS2aSnUrBnk0XSjqTzklZmmL8S5+7EWCUpbtJ9Xcm+BG4CZ4HlVv4kWnOXY8ybMUdJZrqSnrgFtReVkV1JNyRtStoKIfybI/+4X8oD+yV9IekrSauSPpq18Fnyjx0/aUBmAf6SdE3SVUm3Qgj/5c4/TQOAvZKOR0P9UtLBlBxd8/9v/KQBGQR4GBt+VdJOCCEpXgkBWvGDpE+jGGuSPk6Z3zV/CQGQ9HvT9BDCg8T5nfJ3fVkHcGREjE9SzxStBHgl6deRpj9LKSJD/rfkfFtKPCFoxPhc0od95m+KGMc/wGXg22hwRUg9FSlYx/641stx7f3kb8X/E1gHTkQjK04tArRq2ht7sB57UlSAB8AF4LNoWL1SowCt+kLszQUg2e+qf7tUalP9jVlOEi6AMS6AMS6AMS6AMS6AMS6AMS6AMS6AMS6AMS6AMdUKACwBGzPM2wCWylS1IAArwL3Ub0JHuAfM8kiLA5wCnndofsNz4JT1euaGZsvJ0Pg2viVNIsOWMwnfksaRccuZRJVbkvndI3q+jVjbHbNqT0MXhQ+sC0jghaTt+Dzpjfi31fg5Jmkujdb8cJywBT0eafh2COHFmBhLUYRGkMPjAta2BZnzDqPcAn4ExjZxipiHY4yttsHnrX4AAI+AS8BqifP1eH2xGnM8yh3fcRzHcRzHcRwnlTfCZNtPx6UvfQAAAABJRU5ErkJggg==";
    private static final String INK = "#1a1a1a";
    private static final String LABEL = "#8a93a3";
    private static final String[] MONTHS = {"Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"};

    /** Ticketing labels for the booking TaxPolicy's codes. */
    private static final Map<String, String> TAX_LABELS = Map.of(
            "GB", "UK AIR PASSENGER DUTY",
            "UB", "UK PASSENGER SERVICE CHARGE",
            "AE", "UAE PASSENGER FACILITY CHARGE",
            "IN", "INDIA UDF & K3",
            "XT", "INTL AIRPORT CHARGES");
    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");
    private static final int LEDGER_DESC_WIDTH = 40;

    /** Default max baggage allowance per cabin - mirrors printable.ts BAGGAGE. */
    private static String baggageFor(String travelClass) {
        if (travelClass == null) {
            return "25 kg checked + 7 kg cabin";
        }
        return switch (travelClass) {
            case "PREMIUM_ECONOMY" -> "30 kg checked + 7 kg cabin";
            case "BUSINESS" -> "40 kg checked + 10 kg cabin";
            case "FIRST" -> "50 kg checked + 10 kg cabin";
            default -> "25 kg checked + 7 kg cabin";
        };
    }

    /**
     * qrPng is accepted for signature stability but NOT rendered: the ledger
     * design (like the on-screen download) carries the Code-128-style barcode
     * of the PNR instead - the QR belongs to the boarding pass, where it is
     * actually scanned.
     */
    public String render(BookingEvent event, byte[] qrPng) {

        String pnr = nvl(event.getBookingReference(), "-");
        String symbol = "USD".equalsIgnoreCase(event.getCurrency()) ? "US$" : "£";

        List<BookingEventSegment> segments = event.getSegments() != null && !event.getSegments().isEmpty()
                ? event.getSegments()
                : List.of(fallbackSegment(event));
        boolean multi = segments.size() > 1;

        // ---- travellers: one PASSENGER(S) row per ticket (rows are coupons) --
        Map<String, List<BookingEventPassenger>> byTraveller = new LinkedHashMap<>();
        List<BookingEventPassenger> allRows = new ArrayList<>();
        for (BookingEventSegment segment : segments) {
            if (segment.getPassengers() == null) {
                continue;
            }
            for (BookingEventPassenger p : segment.getPassengers()) {
                allRows.add(p);
                String key = p.getTicketNumber() != null ? p.getTicketNumber() : nvl(p.getName(), "?");
                byTraveller.computeIfAbsent(key, k -> new ArrayList<>()).add(p);
            }
        }
        if (allRows.isEmpty() && event.getPassengers() != null) {
            for (BookingEventPassenger p : event.getPassengers()) {
                allRows.add(p);
                String key = p.getTicketNumber() != null ? p.getTicketNumber() : nvl(p.getName(), "?");
                byTraveller.computeIfAbsent(key, k -> new ArrayList<>()).add(p);
            }
        }

        BookingEventPassenger first = allRows.isEmpty() ? null : allRows.get(0);
        String cabinLabel = first != null ? cabin(first.getTravelClass()) : "Economy";
        String fareBasis = first != null && first.getTravelClass() != null && first.getFareType() != null
                ? (first.getTravelClass().charAt(0) + first.getFareType().substring(0, Math.min(3, first.getFareType().length())) + pnr).toUpperCase()
                : "";
        String classCode = first != null && first.getFareType() != null ? first.getFareType().substring(0, 1) : "";

        // ---- header names / ticket numbers ------------------------------
        StringBuilder paxNames = new StringBuilder();
        StringBuilder ticketNos = new StringBuilder();
        for (List<BookingEventPassenger> rows : byTraveller.values()) {
            BookingEventPassenger p = rows.get(0);
            if (paxNames.length() > 0) {
                paxNames.append("<br/>");
                ticketNos.append("<br/>");
            }
            paxNames.append(escape(nvl(p.getName(), "-"))).append(" (ADT)");
            ticketNos.append(p.getTicketNumber() != null
                    ? escape(p.getTicketNumber().substring(0, 3) + "-" + p.getTicketNumber().substring(3))
                    : "-");
        }

        // ---- itinerary rows ----------------------------------------------
        StringBuilder itinerary = new StringBuilder();
        int segIdx = 0;
        for (BookingEventSegment segment : segments) {
            itinerary.append(segmentRows(segment, segIdx, multi, cabinLabel, classCode, fareBasis,
                    segments.get(0).getOriginAirportCode()));
            if (segIdx + 1 < segments.size()) {
                itinerary.append(connectionRow(segment, segments.get(segIdx + 1)));
            }
            segIdx++;
        }

        // ---- PASSENGER(S) rows -------------------------------------------
        StringBuilder passengerRows = new StringBuilder();
        int rowIdx = 0;
        for (List<BookingEventPassenger> rows : byTraveller.values()) {
            BookingEventPassenger p = rows.get(0);
            BigDecimal farePaid = rows.stream()
                    .map(BookingEventPassenger::getFare)
                    .filter(java.util.Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            String seats = multi
                    ? seatFor(rows, 0) + " / " + seatFor(rows, 1)
                    : seatFor(rows, 0);
            String ticket = p.getTicketNumber() != null
                    ? p.getTicketNumber().substring(0, 3) + "-" + p.getTicketNumber().substring(3)
                    : "-";
            passengerRows.append("""
                    <tr%s>
                      <td style="padding:10px 10px;"><b>%s</b> <span style="color:#64748b;">(ADT)</span></td>
                      <td style="padding:10px 10px;font-family:Courier,monospace;"><b>%s</b></td>
                      <td style="padding:10px 10px;font-family:Courier,monospace;"><b>%s</b></td>
                      <td style="padding:10px 10px;">%s &#183; <b>%s</b></td>
                      <td style="padding:10px 10px;text-align:right;font-family:Courier,monospace;"><b>%s</b></td>
                    </tr>
                    """.formatted(
                    rowIdx % 2 == 1 ? " style=\"background-color:#f6f2f4;\"" : "",
                    escape(ledgerName(p)),
                    escape(ticket),
                    escape(seats),
                    escape(p.getTravelClass() != null ? p.getTravelClass().substring(0, 1) : "-"),
                    escape(nvl(p.getFareType(), "-")),
                    money(symbol, farePaid)));
            rowIdx++;
        }

        // ---- FARE CALCULATION ledger -------------------------------------
        List<String> ledger = new ArrayList<>();
        boolean firstFareLine = true;
        for (BookingEventSegment segment : segments) {
            List<BookingEventPassenger> legRows = segment.getPassengers() != null
                    ? segment.getPassengers() : List.of();
            if (legRows.isEmpty()) {
                continue;
            }
            BigDecimal legBase = legRows.stream()
                    .map(p -> p.getBaseFare() != null ? p.getBaseFare() : nz(p.getFare()))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal perHead = legRows.get(0).getBaseFare() != null
                    ? legRows.get(0).getBaseFare() : nz(legRows.get(0).getFare());
            String route = nvl(segment.getOriginAirportCode(), "?") + "-"
                    + nvl(segment.getDestinationAirportCode(), "?");
            ledger.add(ledgerLine(firstFareLine ? "FARE" : "",
                    route + " " + legRows.size() + " X " + money(symbol, perHead),
                    money(symbol, legBase), false));
            firstFareLine = false;
        }
        BigDecimal seatCharges = allRows.stream().map(p -> nz(p.getSeatSurcharge()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        StringBuilder seatList = new StringBuilder();
        for (BookingEventPassenger p : allRows) {
            if (p.getSeatNumber() != null && !p.getSeatNumber().isBlank()) {
                if (seatList.length() > 0) {
                    seatList.append(' ');
                }
                seatList.append(p.getSeatNumber());
            }
        }
        boolean seatsWaived = seatCharges.signum() == 0 && seatList.length() > 0
                && allRows.stream().anyMatch(p -> p.getSeatNumber() != null && !"SAVER".equals(p.getFareType()));
        ledger.add(ledgerLine("SEATS",
                (seatList.length() > 0 ? seatList.toString() : "AUTO") + (seatsWaived ? " (WAIVED)" : ""),
                money(symbol, seatCharges), false));
        int bagCount = allRows.stream().map(p -> p.getExtraBags() != null ? p.getExtraBags() : 0)
                .reduce(0, Integer::sum);
        BigDecimal bagCharges = allRows.stream().map(p -> nz(p.getBaggageFee()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        ledger.add(ledgerLine("BAGS",
                bagCount > 0 ? bagCount + " EXTRA" + (multi ? " X " + segments.size() + " FLIGHTS" : "") : "NONE",
                money(symbol, bagCharges), false));
        // Government/airport taxes, itemised per code the way tickets price
        // them. Pre-taxation bookings (no breakdown) keep the legacy line.
        if (event.getTaxBreakdown() != null && !event.getTaxBreakdown().isBlank()) {
            boolean firstTax = true;
            for (String part : event.getTaxBreakdown().split(";")) {
                String[] pieces = part.split(":");
                if (pieces.length != 2) {
                    continue;
                }
                String label = TAX_LABELS.getOrDefault(pieces[0].trim().toUpperCase(),
                        pieces[0].trim().toUpperCase());
                ledger.add(ledgerLine(firstTax ? "TAXES" : "", label,
                        money(symbol, new BigDecimal(pieces[1].trim())), false));
                firstTax = false;
            }
        } else {
            ledger.add(ledgerLine("TAX", "INCLUDED", money(symbol, BigDecimal.ZERO), false));
        }
        String totalLine = ledgerLine("TOTAL", "PAID (INCL. ALL TAXES)",
                money(symbol, nz(event.getTotalFare())), true);

        return """
                <html xmlns="http://www.w3.org/1999/xhtml">
                <head>
                <style>
                  @page { size: A4; margin: 20px 28px; }
                  body { font-family: Helvetica, Arial, sans-serif; color: %s; font-size: 10.5px; margin: 0; }
                  table { border-collapse: collapse; }
                  .band { background-color: %s; color: #ffffff; font-weight: bold; font-size: 12.5px;
                          letter-spacing: 0.5px; padding: 7px 14px; margin-top: 10px; border-radius: 5px; }
                  .rule-h { font-size: 10px; font-weight: bold; letter-spacing: 1px; color: %s;
                            margin-top: 12px; padding-bottom: 3px; border-bottom: 1px solid #d8ccc0; }
                  .rule-p { font-size: 9px; color: #333333; line-height: 1.75; margin-top: 5px; }
                  .lbl { font-size: 8px; letter-spacing: 1.5px; text-transform: uppercase; color: %s; }
                  .mono { font-family: Courier, monospace; }
                </style>
                </head>
                <body>

                  <!-- Maroon header band -->
                  <table width="100%%" style="background-color:%s;">
                    <tr>
                      <td style="padding:14px 16px;color:#ffffff;font-style:italic;font-weight:bold;font-size:15px;">Going places together</td>
                      <td style="padding:10px 16px;text-align:right;color:#ffffff;">
                        <table style="border-collapse:collapse;margin-left:auto;"><tr>
                          <td style="vertical-align:middle;padding-right:12px;">
                            <table style="width:38px;height:38px;background-color:#7a3a58;border-radius:19px;border-collapse:collapse;"><tr>
                              <td style="text-align:center;vertical-align:middle;font-size:7px;font-weight:bold;color:#ffffff;line-height:1.2;">SKY<br/>ALLIANCE</td>
                            </tr></table>
                          </td>
                          <td style="vertical-align:middle;font-size:22px;font-weight:bold;letter-spacing:1px;color:#ffffff;">SkyBook</td>
                          <td style="vertical-align:middle;padding-left:8px;">
                            <img src="data:image/png;base64,%s" width="26" height="26" alt="SkyBook plane"/>
                          </td>
                        </tr></table>
                      </td>
                    </tr>
                  </table>

                  <!-- Passenger + barcode block -->
                  <table width="100%%" style="margin-top:12px;">
                    <tr>
                      <td style="vertical-align:top;padding:0 14px;width:54%%;">
                        <div class="lbl">Passenger</div>
                        <div style="font-size:12px;font-weight:bold;line-height:1.5;margin-bottom:7px;">%s</div>
                        <div class="lbl">Booking reference</div>
                        <div class="mono" style="font-size:15px;font-weight:bold;letter-spacing:3px;color:%s;margin-bottom:7px;">%s</div>
                        <div class="lbl">E-ticket number(s)</div>
                        <div class="mono" style="font-size:11px;font-weight:bold;line-height:1.6;">%s</div>
                      </td>
                      <td style="vertical-align:top;padding:0 14px;width:46%%;">
                        <table width="100%%" style="background-color:#f8fafc;border:1px solid #e2e8f0;border-radius:10px;">
                          <tr><td style="padding:9px 14px 6px;text-align:center;">%s</td></tr>
                        </table>
                        <div style="margin-top:6px;font-size:9.5px;color:#475569;line-height:1.6;">
                          <div><b style="color:%s;">Itinerary Printing Office</b></div>
                          <div>SKYBOOK DIGITAL, DIGITAL OFFICE</div>
                          <div><b style="color:%s;">Date of issue:</b> <b>%s</b></div>
                        </div>
                      </td>
                    </tr>
                  </table>

                  <div class="band">ELECTRONIC TICKET RECEIPT</div>

                  <!-- Itinerary -->
                  <table width="100%%" style="margin-top:12px;font-size:11px;">
                    <tr style="background-color:#d9d9d9;color:#333333;text-align:left;font-size:10px;">
                      <th style="padding:6px 10px;">From</th>
                      <th style="padding:6px 10px;">To</th>
                      <th style="padding:6px 10px;">Flight</th>
                      <th style="padding:6px 10px;">Departure</th>
                      <th style="padding:6px 10px;">Arrival</th>
                      <th style="padding:6px 10px;">Last check-in</th>
                    </tr>
                    %s
                  </table>
                  <div style="border-top:2px solid #333333;"></div>

                  <div class="band">PASSENGER(S)</div>
                  <table width="100%%" style="margin-top:8px;font-size:11px;">
                    <tr style="font-size:8px;letter-spacing:1px;color:%s;text-align:left;">
                      <th style="padding:5px 9px;border-bottom:2px solid %s;">NAME</th>
                      <th style="padding:5px 9px;border-bottom:2px solid %s;">E-TICKET</th>
                      <th style="padding:5px 9px;border-bottom:2px solid %s;">%s</th>
                      <th style="padding:5px 9px;border-bottom:2px solid %s;">CABIN &#183; FARE</th>
                      <th style="padding:5px 9px;border-bottom:2px solid %s;text-align:right;">FARE PAID</th>
                    </tr>
                    %s
                  </table>

                  <div class="band">FARE CALCULATION</div>
                  <table width="100%%" style="margin-top:8px;background-color:#fbfaf7;border:1px solid #e7e2d8;border-radius:7px;">
                    <tr><td style="padding:9px 14px;">
                      <div class="mono" style="font-size:10.5px;line-height:1.75;white-space:pre;">%s</div>
                      <div class="mono" style="border-top:2px solid %s;margin-top:6px;padding-top:6px;font-size:10.5px;white-space:pre;color:%s;font-weight:bold;">%s</div>
                    </td></tr>
                  </table>
                  <div style="font-size:9.5px;color:#555555;margin-top:8px;line-height:1.6;">
                    Total paid: <b>%s</b> &#160;&#183;&#160; Status: %s &#160;&#183;&#160;
                    <span style="color:#888888;">Notices, baggage and fare rules: see page 2 of this receipt.</span>
                  </div>

                  <!-- Page 2: notices and airline rules (conditions-of-carriage
                       small print, two columns, like carriers print them). -->
                  <div style="page-break-before:always;"></div>
                  <div style="font-size:12px;font-weight:bold;letter-spacing:2px;color:#555555;border-bottom:2px solid %s;padding-bottom:6px;">
                    IMPORTANT NOTICES &#183; CONDITIONS OF CARRIAGE
                  </div>
                  <table width="100%%" style="margin-top:10px;">
                    <tr>
                      <td style="width:50%%;vertical-align:top;padding-right:14px;">
                        <div class="rule-h" style="margin-top:0;">TICKET KEY</div>
                        <div class="rule-p">
                          <b>(1)</b> OK = Confirmed &#160; <b>(2)</b> NVB = Not valid before &#160; <b>(3)</b> NVA = Not valid after &#160;
                          <b>(4)</b> Each passenger can check in a specific amount of baggage at no extra cost as indicated in the
                          column baggage. For more information on baggage rules and restrictions, please visit flyskybook.com/baggage.
                        </div>
                        <div class="rule-h">CHECK-IN AND BOARDING</div>
                        <div class="rule-p">
                          Online check-in opens 48 hours and closes 60 minutes before departure; your boarding pass is emailed and
                          available in Manage my trips. Airport counters close at the time shown in the Last check-in column.
                          The boarding gate closes 20 minutes before departure - passengers arriving after gate closure are
                          recorded as no-shows and the fare is not refundable. Carry the passport used at booking; it must be
                          valid for the whole journey and match the name on this ticket exactly.
                        </div>
                        <div class="rule-h">BAGGAGE</div>
                        <div class="rule-p">
                          Your free allowance is printed against each flight on page 1. Extra checked bags purchased for this
                          booking appear on the BAGS line of the fare calculation and apply to every flight in the same
                          direction. On connecting flights issued on one ticket, bags are checked through to the final
                          destination - collect them only there. Dangerous goods (lithium batteries in checked bags, aerosols,
                          flammables, corrosives) must not be packed; full list at flyskybook.com/baggage.
                        </div>
                      </td>
                      <td style="width:50%%;vertical-align:top;padding-left:14px;border-left:1px solid #e2e8f0;">
                        <div class="rule-h" style="margin-top:0;">FARES, CHANGES AND CANCELLATION</div>
                        <div class="rule-p">
                          Cancellation refunds follow the time of cancellation: more than 72 hours before departure 100%% of the
                          time-tier value, 24-72 hours 50%%, under 24 hours no refund; online cancellation closes 2 hours before
                          departure. Fare rules then apply per passenger: SAVER fares carry a 30%% cancellation fee, FLEXI fares
                          are fully refundable, and PREMIUM fares include free date changes plus refunds up to 6 hours before
                          departure. Taxes are refunded in full whenever no flight has been flown. Name changes are not
                          permitted - contact reservations to rebook.
                        </div>
                        <div class="rule-h">TAXES, FEES AND CHARGES</div>
                        <div class="rule-p">
                          Taxes itemised on page 1 are levied per departure airport and remitted to the relevant authority:
                          UK Air Passenger Duty and Passenger Service Charge (London departures), UAE Passenger Facility
                          Charge (Dubai), India User Development Fee and K3 (Indian airports), and an international service
                          charge elsewhere. Government-imposed amounts may change between booking and travel; any difference
                          is collected or refunded before departure.
                        </div>
                        <div class="rule-h">CONDITIONS OF CARRIAGE AND LIABILITY</div>
                        <div class="rule-p">
                          Carriage is subject to the SkyBook Airways Conditions of Carriage, available at
                          flyskybook.com/conditions. For international carriage, liability for death or bodily injury, baggage
                          destruction, loss or damage, and delay is governed by the Montreal Convention (1999). Baggage claims
                          must be filed in writing within 7 days of receipt of the bags (21 days for delay). This receipt is
                          your ticket record - keep it available throughout the journey; authorities may require proof of
                          onward or return travel.
                        </div>
                      </td>
                    </tr>
                  </table>

                  <!-- Carrier contact block (fictional airline - drama-range
                       phone number and invented address). -->
                  <table width="100%%" style="margin-top:14px;background-color:#f8fafc;border:1px solid #e2e8f0;border-radius:7px;">
                    <tr><td style="padding:10px 14px;font-size:9px;color:#475569;line-height:1.9;">
                      <b style="color:#1a1a1a;">SkyBook Airways &#183; Contact</b><br/>
                      Reservations &amp; support (24/7): <b>+44 20 7946 0958</b> &#160;&#183;&#160;
                      <b>support@flyskybook.com</b> &#160;&#183;&#160; flyskybook.com<br/>
                      Registered office: SkyBook Airways Ltd, One Skyway House, 100 Aviation Way, London EC2X 9SB, United Kingdom &#160;&#183;&#160; Company No. 01234567
                    </td></tr>
                  </table>

                </body>
                </html>
                """.formatted(
                INK, MAROON, LABEL, MAROON,
                MAROON, PLANE_PNG,
                paxNames.toString(), MAROON, escape(pnr), ticketNos.toString(),
                barcode(pnr),
                INK, INK, escape(issueDate(event)),
                itinerary.toString(),
                LABEL, MAROON, MAROON, MAROON, multi ? "SEATS OUT / RET" : "SEAT", MAROON, MAROON,
                passengerRows.toString(),
                String.join("\n", ledger), MAROON, MAROON, totalLine,
                money(symbol, nz(event.getTotalFare())), escape(nvl(event.getBookingStatus(), "CONFIRMED")), MAROON);
    }

    // ------------------------------------------------------------------
    // itinerary
    // ------------------------------------------------------------------

    private String segmentRows(BookingEventSegment s, int index, boolean multi,
                               String cabinLabel, String classCode, String fareBasis,
                               String journeyOrigin) {
        String from = nvl(s.getOriginAirportCode(), "?");
        String to = nvl(s.getDestinationAirportCode(), "?");
        LocalDateTime dep = parseEventTime(s.getDepartureTime());
        LocalDateTime arr = parseEventTime(s.getArrivalTime());

        StringBuilder sb = new StringBuilder();
        if (multi) {
            sb.append("""
                    <tr>
                      <td colspan="6" style="padding:9px 11px;background-color:#f3eef1;color:%s;font-weight:bold;font-size:10px;letter-spacing:1px;">%s &#183; %s - %s</td>
                    </tr>
                    """.formatted(MAROON, legLabel(index, to, journeyOrigin), escape(from), escape(to)));
        }
        String seats = s.getPassengers() == null ? "-"
                : s.getPassengers().stream()
                        .map(p -> p.getSeatNumber() != null ? p.getSeatNumber() : "-")
                        .reduce((a, b) -> a + ", " + b).orElse("-");
        sb.append("""
                <tr>
                  <td style="padding:6px 10px;vertical-align:top;line-height:1.5;"><b style="font-size:13px;">%s</b> - %s%s</td>
                  <td style="padding:6px 10px;vertical-align:top;line-height:1.5;"><b style="font-size:13px;">%s</b> - %s%s</td>
                  <td style="padding:6px 10px;vertical-align:top;line-height:1.5;"><b>%s</b><br/><span style="font-size:9px;color:#555555;">%s</span></td>
                  <td style="padding:6px 10px;vertical-align:top;line-height:1.5;"><b>%s</b><br/><b>%s</b></td>
                  <td style="padding:6px 10px;vertical-align:top;line-height:1.5;"><b>%s</b><br/><b>%s</b></td>
                  <td style="padding:6px 10px;vertical-align:top;line-height:1.5;">%s</td>
                </tr>
                <tr style="background-color:#ececec;font-size:10px;color:#222222;">
                  <td colspan="2" style="padding:6px 10px;vertical-align:top;line-height:1.55;">
                    <div>Class: <b>%s</b></div>
                    <div>Cabin: %s</div>
                    <div>Max baggage (4): %s</div>
                    <div>Fare basis: %s</div>
                  </td>
                  <td colspan="2" style="padding:6px 10px;vertical-align:top;line-height:1.55;">
                    <div>Operated by: <b>%s</b></div>
                    <div>Marketed by: <b>%s</b></div>
                    <div>Booking status (1): OK</div>
                    <div>Seats: %s</div>
                  </td>
                  <td colspan="2" style="padding:6px 10px;vertical-align:top;line-height:1.55;">
                    <div>NVB (2): <b>%s</b></div>
                    <div>NVA (3): <b>%s</b></div>
                    <div>Duration: <b>%s</b></div>
                  </td>
                </tr>
                """.formatted(
                escape(from), escape(city(from)),
                s.getDepartureTerminal() != null ? "<div style=\"color:#333333;\">Terminal: <b>" + escape(s.getDepartureTerminal()) + "</b></div>" : "",
                escape(to), escape(city(to)),
                s.getArrivalTerminal() != null ? "<div style=\"color:#333333;\">Terminal: <b>" + escape(s.getArrivalTerminal()) + "</b></div>" : "",
                escape(nvl(s.getFlightNumber(), "-")),
                escape(airlineName(s.getFlightNumber())),
                dep != null ? HHMM.format(dep) : "-", dep != null ? ddMon(dep) : "-",
                arr != null ? HHMM.format(arr) : "-", arr != null ? ddMon(arr) : "-",
                dep != null ? HHMM.format(dep.minusMinutes(60)) : "-",
                escape(classCode), escape(cabinLabel), baggageFor(firstClass(s)), escape(fareBasis),
                escape(airlineName(s.getFlightNumber()).toUpperCase()),
                escape(airlineName(s.getFlightNumber()).toUpperCase()),
                escape(seats),
                dep != null ? ddMon(dep) : "-",
                dep != null ? ddMon(dep.plusDays(120)) : "-",
                duration(from, dep, to, arr)));
        return sb.toString();
    }

    /**
     * The ground time between two CHAINED legs: next leg leaves the airport
     * this one lands at, within 24 hours. Anything else (a return days later,
     * a broken chain) is not a connection and prints nothing.
     */
    private String connectionRow(BookingEventSegment leg, BookingEventSegment next) {
        String at = leg.getDestinationAirportCode();
        if (at == null || !at.equalsIgnoreCase(next.getOriginAirportCode())) {
            return "";
        }
        LocalDateTime arr = parseEventTime(leg.getArrivalTime());
        LocalDateTime dep = parseEventTime(next.getDepartureTime());
        if (arr == null || dep == null || !dep.isAfter(arr)) {
            return "";
        }
        long mins = Duration.between(arr, dep).toMinutes();
        if (mins > 24 * 60) {
            return "";
        }
        return """
                <tr>
                  <td colspan="6" style="padding:8px 11px;background-color:#fdf6ee;border-top:1px dashed #d8c9b8;border-bottom:1px dashed #d8c9b8;font-size:10px;color:#7a5c3e;">
                    CONNECTION IN %s (%s) &#183; <b>%dh %02dm</b> &#183; protected transfer, bags checked through
                  </td>
                </tr>
                """.formatted(escape(city(at)), escape(at), mins / 60, mins % 60);
    }

    private String duration(String from, LocalDateTime dep, String to, LocalDateTime arr) {
        if (dep == null || arr == null) {
            return "-";
        }
        Duration d = AirportTimeZones.elapsedBetween(from, dep, to, arr);
        long mins = Math.max(0, d.toMinutes());
        return "%02d:%02d".formatted(mins / 60, mins % 60);
    }

    /** City name in caps, falling back to the code - printable.ts cityFor(). */
    private static String city(String code) {
        String city = AirportCityLookup.cityFor(code);
        return (city != null ? city : nvl(code, "-")).toUpperCase();
    }

    private static String firstClass(BookingEventSegment s) {
        return s.getPassengers() != null && !s.getPassengers().isEmpty()
                ? s.getPassengers().get(0).getTravelClass() : null;
    }

    /**
     * Airline wording: the first flight is OUTBOUND, a flight landing back at
     * the journey's origin is the RETURN, and anything chained in between is
     * a CONNECTING FLIGHT (LHR-DXB-HYD prints OUTBOUND + CONNECTING FLIGHT).
     */
    private static String legLabel(int index, String destination, String journeyOrigin) {
        if (index == 0) {
            return "OUTBOUND";
        }
        if (destination != null && destination.equalsIgnoreCase(journeyOrigin)) {
            return "RETURN";
        }
        return "CONNECTING FLIGHT";
    }

    /** Airline display name from the code embedded in the flight number. */
    private static String airlineName(String flightNumber) {
        return AirlineLookup.forFlightNumber(flightNumber).displayName();
    }

    // ------------------------------------------------------------------
    // ledger + barcode helpers
    // ------------------------------------------------------------------

    /** LASTNAME/FIRSTNAME layout like the on-screen ledger. */
    private static String ledgerName(BookingEventPassenger p) {
        String name = nvl(p.getName(), "-").trim();
        int split = name.lastIndexOf(' ');
        if (split < 0) {
            return name.toUpperCase();
        }
        return name.substring(split + 1).toUpperCase() + "/" + name.substring(0, split).toUpperCase();
    }

    private static String seatFor(List<BookingEventPassenger> rows, int segmentIndex) {
        return rows.stream()
                .filter(p -> (p.getSegmentIndex() != null ? p.getSegmentIndex() : 0) == segmentIndex)
                .map(p -> p.getSeatNumber() != null ? p.getSeatNumber() : "-")
                .findFirst().orElse("-");
    }

    /** Fixed-width label, dot-leader description, right-aligned amount - one pre line. */
    private static String ledgerLine(String label, String desc, String amount, boolean bold) {
        String padded = desc + " ";
        StringBuilder dots = new StringBuilder(padded);
        while (dots.length() < LEDGER_DESC_WIDTH) {
            dots.append('.');
        }
        String amt = " ".repeat(Math.max(0, 12 - amount.length())) + amount;
        String labelPadded = label + " ".repeat(Math.max(0, 9 - label.length()));
        String line = "<b>" + escape(labelPadded) + "</b>" + escape(dots.toString()) + "<b>" + escape(amt) + "</b>";
        return line;
    }

    /**
     * The frontend ticket's Code-128-style barcode, reproduced as a strip of
     * black table cells (openhtmltopdf renders no inline SVG): identical
     * deterministic widths derived from the PNR, quiet zones, and the
     * spaced human-readable value beneath.
     */
    private static String barcode(String text) {
        List<Integer> widths = new ArrayList<>(List.of(2, 1, 1, 2));
        for (char ch : text.toCharArray()) {
            int c = ch;
            widths.add(1 + (c % 3));
            widths.add(1 + ((c >> 2) % 2));
            widths.add(2 + (c % 2));
            widths.add(1 + ((c >> 3) % 2));
        }
        widths.add(2);
        widths.add(1);
        widths.add(2);

        StringBuilder cells = new StringBuilder("<td style=\"width:6px;\"></td>");
        for (int i = 0; i < widths.size(); i++) {
            String color = i % 2 == 0 ? "#0f172a" : "#ffffff";
            cells.append("<td style=\"width:").append(widths.get(i) * 2)
                    .append("px;background-color:").append(color).append(";\"></td>");
        }
        cells.append("<td style=\"width:6px;\"></td>");

        StringBuilder spaced = new StringBuilder();
        for (char ch : text.toCharArray()) {
            if (spaced.length() > 0) {
                spaced.append("&#160;&#160;");
            }
            spaced.append(escape(String.valueOf(ch)));
        }
        return "<table style=\"height:44px;margin:0 auto;\"><tr>" + cells + "</tr></table>"
                + "<div style=\"font-family:Courier,monospace;font-size:10px;color:#334155;margin-top:5px;text-align:center;\">"
                + spaced + "</div>";
    }

    private String issueDate(BookingEvent event) {
        LocalDateTime booked = parseEventTime(event.getBookingDate());
        return booked != null ? ddMon(booked) : "-";
    }

    /** Producer stamps event times as "yyyy-MM-dd HH:mm" (BookingEventProducer.EVENT_TIME). */
    private static LocalDateTime parseEventTime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(value, EVENT_TIME);
        } catch (java.time.format.DateTimeParseException e) {
            try {
                return LocalDateTime.parse(value);
            } catch (java.time.format.DateTimeParseException ignored) {
                return null;
            }
        }
    }

    private static final DateTimeFormatter EVENT_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private static BookingEventSegment fallbackSegment(BookingEvent event) {
        return BookingEventSegment.builder()
                .segmentIndex(0)
                .flightId(event.getFlightId())
                .flightNumber(event.getFlightNumber())
                .originAirportCode(event.getOriginAirportCode())
                .destinationAirportCode(event.getDestinationAirportCode())
                .departureTime(event.getDepartureTime())
                .arrivalTime(event.getArrivalTime())
                .passengers(event.getPassengers())
                .build();
    }

    /** ddMonYYYY with the frontend's month array - locale-proof ("Sep", never "Sept"). */
    private static String ddMon(LocalDateTime value) {
        // Spaced day-month-year, matching the frontend download's ddMon.
        return "%02d %s %d".formatted(value.getDayOfMonth(), MONTHS[value.getMonthValue() - 1], value.getYear());
    }

    private static String cabin(String travelClass) {
        if (travelClass == null) {
            return "Economy";
        }
        return switch (travelClass) {
            case "PREMIUM_ECONOMY" -> "Premium Economy";
            case "BUSINESS" -> "Business";
            case "FIRST" -> "First";
            default -> "Economy";
        };
    }

    private static String money(String symbol, BigDecimal amount) {
        return symbol + nz(amount).setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    private static BigDecimal nz(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

    private static String nvl(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
