# SKB-DOC-11 — Requirements Traceability Matrix (RTM)

| | |
|---|---|
| **Document ID** | SKB-DOC-11 |
| **Version** | 1.0 |
| **Status** | Baselined |
| **Owner** | Platform Engineering |
| **Effective date** | 2026-08-01 |
| **Rule** | Every FR/NFR in SKB-DOC-01 appears here with design, implementation, and verification columns. A requirement with an empty verification cell is OPEN and blocks release of its feature. |

Evidence shorthand: **TR** = `docs/TEST_REPORT_PASSENGER_FEATURES.md`;
**E2E** = certification suite; **U** = unit/integration suites (named class
where load-bearing).

## 1. Functional requirements

| Req | Design | Implementation (primary) | Verification |
|---|---|---|---|
| FR-SRCH-01 | FRONTEND_MODULE | Public search paths (4-gate), SearchPage | E2E; TR |
| FR-SRCH-02 | ROUND_TRIP; FRONTEND | BookingWidget (trip types, leg chaining), SearchPage multi-leg walk | TR §2.4 (£229.50 = 136+93.50) |
| FR-SRCH-03 | ROUND_TRIP §10b | Flight-service sameCarrier + 45/60-min layover rules; multi-city self-transfer filter | TR §2.2, §2.4 (20→13 filter) |
| FR-SRCH-04 | FRONTEND | Day-floor fare on result cards via FareCalculator | TR; U (FareCalculator) |
| FR-SRCH-05 | Fare-calendar section, BOOKING module | `/api/bookings/fare-calendar` | U; UI verified |
| FR-SRCH-06 | BOOKING (bookable cutoff) | `createDraftBooking` 60-min guard + public filters | TR §2.13 (400 verified) |
| FR-BOOK-01 | ROUND_TRIP §1–5 | booking_segments (V10/V12), segment-major rows | TR §2.1–2.3 (SBZXQ3, SB4AMT) |
| FR-BOOK-02 | ROUND_TRIP §5 | One-payment saga | TR §2.3 (£390 one capture) |
| FR-BOOK-03 | SEAT_SELECTION; ROUND_TRIP | PassengerBookingDetail (17 components), per-direction bags, contact w/ phone | TR §2.10; U bags-per-direction test |
| FR-BOOK-04 | SEAT_SELECTION §5.1 | Draft→hold→finalize; compensation on every failure path | U (facade compensation tests); E2E |
| FR-BOOK-05 | IDEMPOTENCY_MODULE | Idempotency keys booking+payment | E2E replay test (same-body 200 / mismatch 409) |
| FR-BOOK-06 | Profile hub design | Saved travellers CRUD + quick-fill | TR §2.9 |
| FR-BOOK-07 | Modify-as-rebook notes | ModifyBookingDialog (net row, preselect, no-op guard) | TR §1 rows 2–3 + no-op fix; browser-verified |
| FR-BOOK-08 | ROUND_TRIP §11 | rebookSegment (Premium exchange) | U SegmentOperationsTest; TR |
| FR-FARE-01 | FARE_RULES / frontend fares step | Fare families + rules display | E2E; UI |
| FR-FARE-02 | Demand-pricing design | FareCalculator (clock-fixed testable) | U; TR |
| FR-FARE-03 | SEAT_SELECTION §8 | SeatPricingPolicy; paid surcharge recorded per row | U; TR §2.7 |
| FR-FARE-04 | Fare-watch design (V13) | fare_alerts + hourly sweep + FARE_ALERT mail | TR §2.6 |
| FR-PAY-01 | PAYMENT_MODULE | Payment state machine, gateway sim | U; E2E |
| FR-PAY-02 | PAYMENT_MODULE refunds | Refund rows (amount+fee), PaymentValidator cap | U RefundServiceImplTest; concurrency test |
| FR-PAY-03 | Cancellation-policy notes | RefundCalculator×tier; quote==execution | U (calculator tier tests); TR live £140/£280 |
| FR-PAY-04 | PARTIALLY_CANCELLED design | Event + payment consumer partial refund | U (4 consumer tests); TR live (SB38UA, SBXNKT) |
| FR-PAY-05 | PAYMENT_MODULE invoices | Immutable invoice + credit notes | U |
| FR-CANX-01 | ROUND_TRIP §7 (matrix) | cancelBooking / cancelPassengers / cancelSegment; direction guard | U; TR (409 on outbound-alone) |
| FR-CANX-02 | CancellationPolicy javadoc | Tiers 72/24/2 + Premium waiver 6h, per-line composition | U CancellationPolicyTest (both-sides boundaries) |
| FR-CANX-03 | Charges-chart notes | `/cancellation-preview` + CancellationChargesCard countdown | Browser-verified; TR |
| FR-CANX-04 | Checked-in cancel notes | Facade guard removal + CANCELLED→checkin revoke chain | TR (SBQKPJ: pass revoked, seat freed) |
| FR-CANX-05 | Seat-mirror notes | CheckInEvent seat mirror incl. BOARDING_PASS_GENERATED | TR (16B→16E row followed) |
| FR-CANX-06 | Policy §admin | `SecurityAccess.isAdmin()` bypass in assess | U facade tests |
| FR-CANX-07 | Policy §unpaid | Unpaid short-circuit in assess/preview | U; TR |
| FR-CHK-01 | CHECKIN_MODULE §windows | CheckInValidator 24h/45m per record | U validator tests |
| FR-CHK-02 | GUEST_CHECKIN_MODULE | Guest session path | Live-verified (module notes) |
| FR-CHK-03 | Terminals design; CHECKIN | Pass issuance w/ TerminalPolicy data + HMAC QR | TR §2.13; admin verify E2E |
| FR-CHK-04 | Pass-parity notes | CheckInEvent enrichment + BoardingPassPdfTemplate | U PDF-extraction tests (terminals incl.) |
| FR-CHK-05 | CHECKIN sweeps | No-show sweep; coupons stay OPEN | U; TR (no-show grouping evidence) |
| FR-CHK-06 | Seat-change designs | bookings changeSeat (pre) + checkins seat (post, reissue) | TR (17B→22D; 16B→16E reissue; over-ceiling 409/refusal) |
| FR-TKT-01 | ROUND_TRIP §6 | issueTicketsIfAbsent (deterministic numbers) | U; TR (125-…) |
| FR-TKT-02 | ROUND_TRIP §6–7 | Coupon lifecycle + FLOWN sweep | TR §2.5 (live sweep) |
| FR-TKT-03 | E-ticket style C notes | printable.ts ledger | U captured-file regression (£, coupons) |
| FR-ACCT-01 | AUTH/SSO modules | Register/login/reset (deployed-origin links)/Google SSO | E2E; SSO live-verified |
| FR-ACCT-02 | Profile-hub notes | ProfilePage cards; auth V6 prefs | TR §2.8, §2.11 |
| FR-ACCT-03 | Trips-grouping notes | groupOf() + chips | U 7 grouping tests; browser-verified counts |
| FR-ACCT-04 | SECURITY_HARDENING §4.2 | ownerSubject + guards everywhere | U guard tests; E2E |
| FR-ADM-01 | Admin-console design | AdminPage (6 sections) + admin endpoints | TR; live-verified ops |
| FR-ADM-02 | SECURITY_HARDENING | ADMIN role + bootstrap env | U; config review |
| FR-NOTF-01 | NOTIFICATION_MODULE | Per-event templates + PDF attach + exact refund lines | U template tests; Mailpit evidence in TR |
| FR-NOTF-02 | NOTIFICATION_MODULE | Pure-consumer design; DLT | Resilience drills |
| FR-INTL-01 | i18n notes | 10-language table; `dir=rtl` for ar | Browser-verified; TR |
| FR-INTL-02 | Currency notes | Display-only FX, exact charge amounts | TR; U (money formatting) |

## 2. Non-functional requirements

| Req | Design | Implementation | Verification |
|---|---|---|---|
| NFR-01 | SECURITY_HARDENING; SKB-DOC-06 | RS256, roles, service creds, fail-fast secrets | Security recon + guard tests; Sonar |
| NFR-02 | RESILIENCE_MODULE | Timeouts/CB/bulkheads/DLT/producer callbacks | Live degradation drills (module notes) |
| NFR-03 | OBSERVABILITY_MODULE | Prom/Grafana/Loki/Tempo + health groups | Deployed dashboards; reactor health tests |
| NFR-04 | SKB-DOC-05 | Flyway-only schema, @Version, state machines | U state-machine tests; migration history |
| NFR-05 | SKB-DOC-05 §6 | History rows, immutable invoices | U; refund evidence chain (runbook §3.5) |
| NFR-06 | CI_CD_MODULE | Actions + Sonar gate | Gate PASSED record (91.2%, 0/0) |
| NFR-07 | SKB-DOC-04 §5.4 | Idempotent consumers | U duplicate-delivery tests |
| NFR-08 | DOCKERIZATION; SKB-DOC-09 | One compose + prod overlay | Full up-from-clean verification |
| NFR-09 | Pagination fix notes | Paginated search; no-reload UI switches | Regression guard; browser-verified |
| NFR-10 | DR_RUNBOOK | restart policies; backup/restore | Reboot survival on VM; DR drill |

## 3. Open items

| Item | Status |
|---|---|
| SKB-DOC-01 §5 timezone normalisation | Backlog — accepted limitation, registered |
| ROUND_TRIP step 8 (drop legacy mirrors) | Scheduled next release (SKB-DOC-05 §4.3) |
| Kubernetes deployment | Design frozen on `feature/kubernetes`; blocked on cluster availability |
