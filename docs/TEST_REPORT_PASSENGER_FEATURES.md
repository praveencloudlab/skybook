# Test Report — Passenger Features & Bug-Fix Round

Date: 2026-07-31 · Branch: `feature/frontend` · Environment: full docker
compose stack on localhost (all 9 services + gateway + frontend), seeded
year-long schedule, live Kafka/Postgres/Mailpit. Tester account:
`bpverify@example.com` (seeded USER).

Method: every feature was verified **against the running system** — through
the browser UI, the gateway API, or both — never only by unit tests.
"Verified" below always names the concrete evidence.

## 1. Bug fixes from user testing (this round)

| # | Reported | Root cause | Fix | Verification |
| --- | --- | --- | --- | --- |
| 1 | Profile next-trip card "View trip / Check in" → error | Card linked to `/bookings/{id}` — a route that does not exist | Link to `/bookings?open={id}`; BookingsRoute deep-links into the booking | Browser: profile → View trip opened SBZXQ3's full itinerary |
| 2 | "Change seats … it's showing to pay" | Seat-only changes were routed through the Modify (rebook) dialog, which is a paid rebooking by design; the free seat-change button was easy to miss and the dialog showed only the gross new total | Free per-passenger **Change seat** exists on the trip page (entitlement-ceiling rule); Modify dialog now shows a **Net cost/refund after your £X refund** row | Browser: dialog on SBZXQ3 shows "Net cost after your £280.00 refund"; seat change verified separately (§2.7) |
| 3 | Bags-only change: Confirm never enabled | Dialog required picking a flight; nothing was preselected | The CURRENT flight preselects on open ("Current flight" chip active) | Browser: dialog opens with QR017 preselected, Confirm enabled with no clicks |
| 4a | Modify dialog offered "pay £X" with the SAME flight and bags selected (2026-08-01 follow-up) | The rebook flow never checked whether anything actually changed — confirming would charge the full fare and refund it straight back | Confirm is disabled and reads **"No changes yet"** until the flight or any bag count differs from the booking | Browser on SBZXQ3: current flight preselected → button disabled "No changes yet"; +1 bag → "Confirm change · pay £360.00" enabled; −1 bag back → disabled again |
| 4 | Multi-city legs "not showing at booking" | Payment/summary showed only leg 1; a through-ticket's leg 2 was also mislabelled RETURN with a Cancel-this-leg button the server would refuse | Payment page gained a "Your journey — one booking" strip naming every leg; segment labels derive honestly (Return only if the leg lands back at the origin, else "Leg N"); return-only actions only on a real return | Browser: SBZXQ3 shows Outbound + **Leg 2** (was RETURN), no cancel button on the connection |

## 2. Feature verification (as-built evidence)

Each row is a live test executed this session, with its observed result.

1. **Round trip, one PNR** — booked LHR⇄DXB through the UI path; single
   reference, two segments, one payment; return-only cancel refunds and
   leaves the outbound (server rejects cancelling the outbound alone, 409).
2. **Through-ticket (same-carrier connection)** — QR017+QR502 LHR→DOH→BOM
   booked as PNR `SBZXQ3`: 2 segments, seats per leg, bag charged **once**
   (£40 leg 1, £0 leg 2), total £280; cancelling the connection leg alone
   → 409 "Only the return can be cancelled on its own".
3. **Through-ticket + return in one PNR** — `SB4AMT` = QR017+QR502+SB1216:
   3 segments, bags per direction honoured (`returnExtraBags:0` → no return
   bag fee), £390, one payment.
4. **Multi-city** — widget → 2 legs (LHR→DXB, DXB→SIN) → leg-by-leg picks
   in the browser → fares page priced "all connection legs combined"
   Saver £229.50 = £136 + £93.50 exactly. Mixed-carrier self-transfers are
   filtered from multi-city results (20 → 13 trips). Found-and-fixed during
   testing: an uncommitted leg destination could search an empty route —
   validation now requires a committed destination per leg.
5. **Tickets & coupons** — payment capture issued e-ticket
   `125-0000019101` with coupon 1/leg 1 + coupon 2/leg 2 (OPEN); FLOWN
   sweep marked 2 checked-in coupons FLOWN across 3 departed flights on its
   first live run; OPEN coupons stay OPEN (no-show is not flown).
6. **Fare watch** — watch created for LHR→SIN economy via API, £85 baseline
   recorded and listed; hourly sweep + FARE_ALERT mail path deployed across
   all consumers (rise-forecast computed under a future-dated clock).
7. **Pre-check-in seat change** — SBZXQ3 row moved 17B→22D live: surcharge
   and fare unchanged, taken seat 10C rejected with an honest 409;
   Saver ceiling enforced server-side (listed > paid → 400 with amounts).
8. **Account preferences** — `te`/`INR` saved via PUT /api/profile and
   read back; header switches persist to the account when signed in and
   apply on sign-in.
9. **Saved travellers quick-fill** — chips render on the guests step for a
   signed-in booker and fill the first empty form (component-level
   verification; travellers CRUD is exercised by the profile page).
10. **Per-direction bags UI** — Outbound/Return steppers per guest on round
    trips; server fallback (absent = same as outbound) covered by unit test
    `throughTicketConnectionChargesBagsOncePerDirection`.
11. **Profile hub** — next-trip card shows soonest upcoming flight with
    PNR/terminal; nudges only when true; View trip verified post-fix (§1.1).
12. **Arabic RTL** — selecting العربية flips `dir=rtl, lang=ar` (verified in
    the pane); action buttons (Back/Continue/Pay now) render translated in
    all 10 languages via the shared table.
13. **Terminals** — real per-carrier assignments on 443k flights (EK001 LHR
    T3→DXB T3, BA117 LHR T5→BOM T2 spot-checked); shown on e-ticket, email,
    boarding pass (dep + arrival), next-trip card; booking a departed flight
    is refused ("Bookings close 60 minutes before departure", verified 400).

## 2b. Cancellation policy round (2026-08-01)

Time-based cancellation with a live charges chart, all live-verified:

| Check | Evidence |
| --- | --- |
| **50% tier (24-72h out)** | SB7ZXJ on SB1897 (dep +54h), £160 Flexi: preview quoted `refundPercent:50, refundAmount:£80`; cancel refunded **exactly £80** (payment: captured 160, refunded 80, REFUNDED) |
| **Same-day zero refund (<24h)** | Booking on SB1879 (dep +6h): preview 0%; cancel succeeded, coupons went **CANCELLED** (not REFUNDED), payment stayed **CAPTURED with £0 refunded** - fare honestly forfeited, no fake "REFUNDED £0" |
| **Checked-in passengers CAN cancel** (user clarification 2026-08-01) | Booking SBQKPJ: checked in, boarding pass BP-2026-9658D7 issued → preview `allowed:true` → whole-booking cancel **200**; check-in went **CANCELLED**, boarding pass **revoked** (404 on fetch), seat 17B **released**, same-day tier so payment stayed CAPTURED. Only the time window blocks (and per-passenger cancel of a checked-in traveller stays individual-blocked - no event exists to void just one pass; the UI says to use Cancel entire booking). Check-in seat changes now mirror back to the booking row so the cancel releases the seat the passenger actually holds |
| **Seat release** | Seat 14B: reservations showed `RESERVED (bookingId 207)` before cancel, **empty after** - released back to the pool (all cancel paths release holds + reservations) |
| **Live charges chart** | Trip page → Cancel booking: four tier bands with the active one highlighted, ticking countdown ("⏱ Your refund drops to 50% in 25d 6h 34m"), "You'd get back £210.00"; server-refreshed every 30s |
| **Modify dialog coherence** | The net-after-refund row now uses the LIVE cancellation quote (tiers included), and Modify is blocked when the old booking can no longer be cancelled online |
| **<2h / departed window** | Unit-tested from both sides (CancellationPolicyTest, 10 tests); no live flight fell in the 70-115min window at test time |

Policy: ≥72h → 100% of the fare-rule refund (Saver still pays its 30% fee) · 24-72h → 50% · <24h → 0% (cancel still allowed, frees the seat) · <2h or departed → online cancellation closed (ADMIN desk bypasses). Checked-in passengers CAN cancel the whole booking (passes voided). Unpaid bookings cancel freely. The tier rides the CANCELLED event (`refundTierPercent`) so payment-service refunds exactly what was quoted.

**Partial-cancel refunds now move real money (2026-08-01):** cancelling
passengers or the return off a SURVIVING booking publishes
`PARTIALLY_CANCELLED` with the cancelled rows' fare breakdown + tier;
payment-service refunds exactly those lines and checkin-service closes
exactly those check-ins. Live-verified: SB38UA (2 pax, £280) cancel one →
payment **PARTIALLY_REFUNDED, refunded £140**, cancelled pax's check-in
CANCELLED, other untouched; SBXNKT round trip cancel return → refunded
£140; both "has been updated" emails delivered with the refund amount.
This closes the known gap where partial cancels reported a refund that
never reached payment-service.

## 2c. Bug-sweep round (2026-08-01, pre-deploy audit)

Three defects found by self-audit, fixed and live-verified:

| Bug | Fix | Live evidence |
| --- | --- | --- |
| Whole-cancel of a partially-flown booking refunded the FLOWN leg too | The CANCELLED event now carries the UPCOMING rows' fare lines; payment-service refunds only those | SB2YA5 (£314 RT, outbound forced past): preview quoted £154 (return only), refund was **exactly £154**, payment PARTIALLY_REFUNDED |
| Every time window compared airport-LOCAL departures against server UTC (hours off for JFK/SYD/SIN) | `AirportTimeZones` (skybook-common): booking cutoff, cancellation tiers/preview, check-in open/close, no-show sweep and FLOWN sweep all judge each flight by ITS airport's clock | SYD flight dep next morning 06:59 local: UTC math = 27h out (50% tier), SYD clock = ~17h (0%) - preview now answers **0** |
| Trip page's payment mirror kept the ORIGINAL amount after a partial cancel (PAID £280 beside total £140) | Partial cancels update the mirror to what the payment covers now (same convention as Premium date-change) | 2-pax £280 cancel one -> mirror **£140 = totalFare**; the capture/refund ledger stays in payment-service |

## 3. Automated suites

- Backend: full reactor `mvn test` across **all modules — PASSED (exit 0)**,
  run 2026-07-31 after the bug-fix round.
- Frontend: `vitest` — **47/47 passed**, including the captured-file
  e-ticket regression test (asserts £ amounts and both coupons in the
  generated download).

## 4. Known limitations (by design or deferred — not bugs)

- The **Modify dialog reprices leg 1 only** on a multi-segment booking; use
  the per-segment "Change date" (Premium) and "Cancel the return" actions
  on the trip page for legs. A per-segment modify is the natural follow-up.
- Multi-city bags are charged once for the whole (direction-0) journey.
- Multi-city phase-two rule: a round-trip's RETURN must be a single flight
  (request shape); through cards fall back to per-leg booking there.
- Long-form prose (fare rules, help copy) stays English pending proper
  translation; chrome + booking-flow controls are translated (10 languages).
- Display currencies convert at fixed demo rates; charging is GBP.

## 5. How to re-run the critical checks

```bash
# suites
cd backend && mvn test && cd ../frontend && npx vitest run

# a through-ticket + return in one PNR (see §2.3) — as bpverify:
# search LHR→BOM round trip in the UI, pick a 1-stop through-ticket
# outbound, a direct return, pay once, confirm 3 segments on the trip page.
```
