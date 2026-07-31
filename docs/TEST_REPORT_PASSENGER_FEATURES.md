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
