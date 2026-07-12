# 🛡️ SkyBook Resilience — Design

---

## Project Information

| | |
|---|---|
| **Scope** | Timeouts, circuit breakers, controlled retries, bulkheads, fallbacks on every synchronous Feign path; Kafka consumer retry policy + dead-letter topics; producer send-failure visibility |
| **Branch** | `feature/resilience` |
| **Status** | Implemented and verified live per §14 step 8 - breaker open/fast-fail/recovery and poison-pill-to-DLT all observed against the running stack. See §16 Implementation Notes. |

Goal: a slow or dead dependency degrades the caller *predictably* — bounded latency, fast failure, no thread-pool exhaustion, no silently dropped Kafka messages. Today none of that is true: a hanging flight-service holds a booking-service thread for **60 seconds** (Feign's default read timeout, configured nowhere), every failed call is retried by nobody or by everybody-at-once, and a poison Kafka message is retried 10 times in a tight loop and then **silently discarded** (spring-kafka's default), gone forever.

---

# Table of Contents

1. [Overview](#1-overview)
2. [Load-Bearing Findings](#2-load-bearing-findings)
3. [Architecture](#3-architecture)
4. [Timeouts](#4-timeouts)
5. [Circuit Breakers](#5-circuit-breakers)
6. [Retries](#6-retries)
7. [Bulkheads](#7-bulkheads)
8. [Fallbacks](#8-fallbacks)
9. [Kafka: Consumer Retries + Dead-Letter Topics](#9-kafka-consumer-retries--dead-letter-topics)
10. [Kafka: Producer Send Visibility](#10-kafka-producer-send-visibility)
11. [Observability Integration](#11-observability-integration)
12. [Deferred / Out of Scope](#12-deferred--out-of-scope)
13. [Known Risks / Open Questions](#13-known-risks--open-questions)
14. [Build Order](#14-build-order)
15. [Testing Plan](#15-testing-plan)

---

# 1. Overview

Two failure domains, treated separately because their failure modes are different:

**Synchronous HTTP (Feign)** — five call paths, all confirmed by inspection: booking→flight, booking→inventory, checkin→flight, checkin→inventory, inventory→flight. Per the roadmap's own emphasis, Flight and Inventory are the services everyone else calls synchronously — they are the fleet's shared points of failure. Each path gets, via **Resilience4j** (`resilience4j-spring-boot3`, config-driven, annotations on the existing wrapper clients): explicit timeouts (Feign config), a circuit breaker, a semaphore bulkhead, and — for idempotent reads only — a bounded retry.

**Asynchronous Kafka** — six `@KafkaListener` consumers across booking, checkin, payment, and notification. Each service's listener container factory gains a `DefaultErrorHandler` with exponential backoff and a `DeadLetterPublishingRecoverer`: a message that still fails after bounded retries lands in a per-source-topic `.DLT` topic instead of vanishing. Producers stop being fire-and-forget: send futures get a failure-logging callback.

No new infrastructure containers. No behavioral change on the happy path. Everything lands in the seams the codebase already built: the per-service wrapper clients (which already translate `FeignException` → domain exceptions) and the per-service listener container factories (which already exist for deserialization config).

---

# 2. Load-Bearing Findings

Confirmed by inspection, not assumed:

1. **No timeouts are configured anywhere.** No `connect-timeout`/`read-timeout` under any `spring.cloud.openfeign` (or `feign.client`) key in any `application.yml`. Spring Cloud OpenFeign's defaults apply: **10s connect, 60s read**. A flight-service that accepts connections but responds slowly pins the calling thread for up to a minute — and booking's create-booking path makes *multiple* sequential inventory calls (one hold per passenger), so worst-case latency stacks.
2. **No resilience library exists.** No resilience4j, no Spring Retry, no `@Retryable`, no circuit breaker of any kind anywhere in the reactor (fresh grep, unchanged since the api-gateway design doc first noted it).
3. **The wrapper-client seam is already there — but the annotations cannot go directly on it.** Every Feign client is wrapped in a hand-written `XServiceClient` component that catches `FeignException` and rethrows domain exceptions (`FlightServiceUnavailableException`, `InventoryServiceUnavailableException`, `SeatUnavailableException`); callers never see Feign. The first draft put the Resilience4j annotations on these wrapper methods — **design review caught that as unworkable**: the AOP aspect sits *outside* the annotated method, so (a) the breaker/retry would only ever observe the already-translated domain exceptions, never the `FeignException`s the config filters on, and (b) an open circuit throws `CallNotPermittedException` *before the method body runs*, where the wrapper's own try/catch can never translate it (same for `BulkheadFullException`). The corrected structure (§5) splits the seam in two: an inner annotated bean seeing raw Feign exceptions, and the existing wrapper on the outside translating *everything* — Feign, breaker, and bulkhead exceptions alike — into the unchanged domain contract. This also sidesteps Spring's self-invocation proxy trap. Compensation paths (`releaseHoldQuietly`, `cancelReservationQuietly`) already exist and already never throw.
4. **spring-kafka's default error handling silently discards poison messages.** No `DefaultErrorHandler`, `CommonErrorHandler`, `DeadLetterPublishingRecoverer`, or `@RetryableTopic` anywhere. The framework default retries a failing record 9 times with **zero backoff** (hammering whatever is failing), then logs and *commits past it* — the message is gone. For payment-service's `BookingEventConsumer` (which creates payments) or booking's `PaymentEventConsumer` (which confirms bookings), a transient DB hiccup at the wrong moment means a permanently lost business event today.
5. **Producers are fire-and-forget.** All five `XEventProducer` classes call `kafkaTemplate.send(...)` and ignore the returned future — a broker outage during a send is invisible even in the new centralized logs, because nothing ever inspects the result.
6. **The observability stack (just merged) makes resilience visible for free.** Resilience4j ships Micrometer integration: circuit-breaker state, retry counts, and bulkhead saturation auto-register with the Prometheus registry that every service now has, and land in the existing Grafana without new plumbing. DLT topics are observable via the existing Kafka consumer-lag metrics and Loki logs. Sequencing observability *before* resilience — the roadmap's own ordering — pays off here.
7. **Consumer group + container factories are per-service and already customized** (e.g. `bookingEventContainerFactory` in payment-service) for JSON deserialization — the error handler slots into these existing factory beans; no listener code changes.

---

# 3. Architecture

```
   Caller (facade/service code) - unchanged
        │
        ▼
   XServiceClient                 ← existing wrapper: the DOMAIN boundary.
        │                           Translates FeignException AND
        │                           CallNotPermittedException AND
        │                           BulkheadFullException → domain exceptions.
        ▼
   ResilientXClient (new)         ← inner bean: @Bulkhead @CircuitBreaker @Retry*
        │                           (aspects see RAW Feign exceptions here;
        ▼                            * @Retry on idempotent reads only)
   XFeignClient → HTTP            ← Feign with explicit timeouts (§4)

   Five paths: booking→{flight,inventory}, checkin→{flight,inventory},
   inventory→flight. On open circuit / saturation / timeout / 5xx:
   domain exception in milliseconds → existing HTTP error mapping → gateway 5xx.

     Kafka consumers (booking, checkin, payment, notification ×3):
        record fails → DefaultErrorHandler, ExponentialBackOffWithMaxRetries(2):
                       attempt 1 → 1s → attempt 2 → 2s → attempt 3
                     → still failing → DeadLetterPublishingRecoverer
                     → <source-topic>.DLT   (e.g. skybook.booking.events.DLT)
```

The two-bean split per dependency (design-review correction — see finding §2.3) exists because Resilience4j's AOP proxy wraps the *outside* of the annotated method: the aspects must sit on a bean whose exceptions are still raw `FeignException`s, and the domain translation must sit on a bean *outside* the proxy so it can also catch what the aspects themselves throw (`CallNotPermittedException`, `BulkheadFullException`). The failure contract with every existing caller is unchanged — the same domain exceptions, just thrown in milliseconds instead of after a 60-second hang, and with the circuit open, without even attempting the doomed call.

---

# 4. Timeouts

`application.yml` per calling service (booking, checkin, inventory):

```yaml
spring:
  cloud:
    openfeign:
      client:
        config:
          default:
            connect-timeout: 2000   # ms - same compose network / same k8s cluster later
            read-timeout: 5000      # ms - all current calls are small-payload CRUD
```

- **2s connect / 5s read as the fleet default** — every current inter-service call is a small JSON CRUD operation between co-located containers; anything slower than 5s *is* a failure and should be treated as one. Worst-case create-booking latency drops from "minutes" (60s × sequential seat holds) to a bounded few seconds before the circuit breaker (§5) starts failing faster still.
- Per-client overrides (keyed by Feign client name) are possible under the same config tree if a genuinely slower endpoint ever appears — none exists today, so only `default` is set.
- Timeout exceptions surface as `FeignException`/`RetryableException` — already caught by the wrapper clients' existing `catch (FeignException unreachable)` blocks → existing domain exceptions. Zero caller changes.

# 5. Circuit Breakers

**Resilience4j via `resilience4j-spring-boot3` + AOP annotations, configured in `application.yml`** — not the Spring Cloud CircuitBreaker abstraction (an extra indirection to swap implementations this project will never swap), and not hand-rolled decorators (the annotation + config-file model keeps tuning out of Java code, consistent with how everything else in this fleet is configured).

- One breaker instance per downstream dependency per caller: `flight` and `inventory` in booking and checkin; `flight` in inventory — five instances total, all sharing one config profile:

```yaml
resilience4j:
  circuitbreaker:
    configs:
      default:
        sliding-window-type: COUNT_BASED
        sliding-window-size: 10
        minimum-number-of-calls: 5
        failure-rate-threshold: 50
        wait-duration-in-open-state: 10s
        permitted-number-of-calls-in-half-open-state: 2
        record-exceptions: [feign.FeignException, java.util.concurrent.TimeoutException]
        ignore-exceptions: [feign.FeignException.FeignClientException]  # 4xx = downstream is healthy, caller erred
    instances:
      flight: { base-config: default }
      inventory: { base-config: default }
```

- **4xx responses don't count as failures** — a `404 flight not found` or `409 seat conflict` proves the downstream is *up and working*; only 5xx/timeouts/connect failures should trip the breaker. (`FeignException.FeignClientException` is Feign's 4xx family; 5xx is `FeignServerException`, which stays recorded.) This filtering **only works because the annotations sit on the inner bean** where the raw `FeignException` hierarchy is still what propagates — on the outer wrapper the breaker would only ever see already-translated domain exceptions and the `record-exceptions` list would silently match nothing (design-review catch).
- **Two-bean structure per dependency** (§3): the annotations go on a new, thin `ResilientXClient` bean whose methods do nothing but delegate to the Feign interface; the existing `XServiceClient` wrapper calls *it* and extends its existing catch blocks with `CallNotPermittedException` (open circuit) and `BulkheadFullException` (saturation), both mapped to the existing `XServiceUnavailableException`:

```java
@Component
@RequiredArgsConstructor
public class ResilientFlightClient {
    private final FlightServiceFeignClient feignClient;

    @Bulkhead(name = "flight")
    @CircuitBreaker(name = "flight")
    @Retry(name = "flight-read")           // reads only - write methods omit this
    public FlightDetails getFlight(Long id) {
        return feignClient.getFlight(id);   // raw FeignException propagates to the aspects
    }
}

// FlightServiceClient (existing) - stays the domain boundary:
public FlightDetails getFlight(Long flightId) {
    try {
        return resilientFlightClient.getFlight(flightId);
    } catch (FeignException.NotFound notFound) {
        throw new FlightNotFoundForBookingException(flightId);
    } catch (CallNotPermittedException | BulkheadFullException fastFail) {
        throw new FlightServiceUnavailableException(flightId, fastFail);   // circuit open / saturated: no call was even attempted
    } catch (FeignException unreachable) {
        throw new FlightServiceUnavailableException(flightId, unreachable);
    }
}
```

  An open circuit or full bulkhead throws *before* the inner method body executes — only a bean **outside** the proxy can translate those, which is exactly what the outer wrapper now is. This also avoids Spring's self-invocation trap (aspects don't fire on `this.`-calls within one bean).
- The `*Quietly` compensation methods route through the same inner bean (they share the dependency's health and its breaker state) but keep their never-throw semantics — an open circuit there just logs, exactly like any other failure already does.

# 6. Retries

**Reads retry, writes don't.** Retrying a timed-out *write* (`holdSeat`, `reserveSeat`) risks double side effects — the first attempt may have succeeded server-side after the client gave up. Until those endpoints are provably idempotent end-to-end (they key on `(flightId, seat, bookingId)` but that guarantee is inventory-service's to certify, not the caller's to assume — flagged in §13), automatic retry on them is a correctness bug waiting to happen, not resilience.

```yaml
resilience4j:
  retry:
    configs:
      reads:
        max-attempts: 3            # 1 original + 2 retries
        wait-duration: 200ms
        enable-exponential-backoff: true
        exponential-backoff-multiplier: 2   # 200ms, 400ms
        retry-exceptions: [feign.RetryableException, feign.FeignException.FeignServerException]
    instances:
      flight-read: { base-config: reads }
      inventory-read: { base-config: reads }
```

- `@Retry(name = "flight-read")` only on the **inner `ResilientXClient`** read methods (§5's structure — on the outer wrapper the `retry-exceptions` filter would never match the already-translated exceptions): `getFlight` (all three callers), checkin's flight status/schedule reads, and inventory reads that are pure GETs. Write paths get circuit breaker + timeout + bulkhead but **no** retry annotation.
- Retry sits *inside* the circuit breaker (Resilience4j's default aspect order: Bulkhead → CircuitBreaker → Retry → method): each individual attempt is recorded by the breaker, and once the breaker opens, retries stop happening at all — no retry-storm against a dying service.

# 7. Bulkheads

Semaphore bulkheads (not thread-pool — no async hand-off exists in these blocking Tomcat services, and a thread-pool bulkhead would add a context switch for nothing):

```yaml
resilience4j:
  bulkhead:
    configs:
      default:
        max-concurrent-calls: 10
        max-wait-duration: 100ms
    instances:
      flight: { base-config: default }
      inventory: { base-config: default }
```

- Caps how many Tomcat worker threads one slow dependency can hold hostage at once (each service's default Tomcat pool is 200 threads; without this, 200 concurrent bookings against a hanging inventory-service = a fully wedged booking-service that can't even serve reads).
- `BulkheadFullException` → caught in the wrappers → same `XServiceUnavailableException` contract.
- 10 concurrent calls is far above any load this dev/portfolio system sees; the value of the bulkhead is the *ceiling existing*, not the specific number — revisit with real load data (§13).

# 8. Fallbacks

**Fail fast and honestly; no fabricated data.** For a booking platform, the correct "fallback" for *can't verify the flight exists* or *can't hold the seat* is a clear, immediate 503 — not a cached maybe-stale flight or an optimistic "assume it's fine." The existing domain exceptions → HTTP mapping already produce exactly that, so:

- **No `fallbackMethod` returning substitute data anywhere.** The circuit breaker's fail-fast (plus §4's bounded timeouts) *is* the user-facing improvement: seconds→milliseconds on a dead dependency.
- The one legitimate degraded-mode behavior already exists and stays: the `*Quietly` compensation methods (release hold, cancel reservation) swallow failures by design — the inventory sweep job (`SeatHoldExpiryJob`, TTL 15min) is the documented self-healing backstop for leaked holds, unchanged by this branch.

# 9. Kafka: Consumer Retries + Dead-Letter Topics

Each of the four consuming services' listener container factories gains:

```java
ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(2); // = 3 total attempts
backOff.setInitialInterval(1_000L);
backOff.setMultiplier(2.0);
backOff.setMaxInterval(2_000L);

new DefaultErrorHandler(
    new DeadLetterPublishingRecoverer(kafkaTemplate),   // → <topic>.DLT, same partition
    backOff
)
```

- **`ExponentialBackOffWithMaxRetries(2)`, not plain `ExponentialBackOff(1000, 2.0)`** — design-review correction: the plain constructor sets interval and multiplier but **no retry bound at all** (it stops on interval exhaustion rules, not attempt count), so the "3 total attempts" this section promises would never actually be enforced. The `WithMaxRetries` variant makes the policy unambiguous: attempt 1 → 1s → attempt 2 → 2s → attempt 3 → DLT.
- **3 total attempts** (1 + 2 retries) with real backoff — replaces the default's 10 zero-interval hammer-retries.
- **DLT naming: spring-kafka's default `<source-topic>.DLT`** (e.g. `skybook.booking.events.DLT`) — convention over invention; auto-created by the broker like every other topic in this stack (auto-create confirmed on in the dockerization branch).
- **Partition-count invariant, stated explicitly:** the default recoverer publishes each dead-lettered record to the *same partition number* it came from, which silently requires every `.DLT` topic to have **at least as many partitions as its source topic**. That holds trivially today (every topic is broker-auto-created single-partition) but becomes a trap the moment any source topic is provisioned with more partitions — so: (a) the invariant is documented here and in the code comment on the recoverer bean, and (b) when explicit topic provisioning arrives (`feature/kubernetes`-era, §12), DLT topics must be provisioned alongside their sources with matching partition counts, or the recoverer given an explicit destination resolver.
- The recoverer publishes the *original record* plus exception metadata headers (`kafka_dlt-exception-message`, stacktrace, original topic/partition/offset) — enough to diagnose and, later, manually replay.
- **Deserialization failures** (malformed JSON — the classic poison pill) can't even reach the listener: each factory's `JsonDeserializer` gets wrapped in `ErrorHandlingDeserializer`, so they fail into the error handler → DLT instead of the container's infinite retry loop. This is a real today-bug: a single unparseable record on `skybook.booking.events` currently wedges payment-service's consumer permanently.
- **Not `@RetryableTopic`** (spring-kafka's non-blocking retry-topic cascade): it creates 2-3 extra topics *per listener* and reorders records across retries. These consumers are low-volume and order-sensitive-ish (booking state transitions); blocking in-place retries with backoff are simpler and sufficient. Revisit only if a consumer ever needs high-throughput non-blocking retries.
- **No automatic DLT re-consumer.** Replay is a human decision in this system's maturity stage — the DLT is a durable holding pen, inspectable via Grafana/Loki (the DLT publish is logged) and `kafka-console-consumer`. An admin replay tool is deferred (§12).

# 10. Kafka: Producer Send Visibility

All five `XEventProducer` classes: the ignored `send()` future gains a `whenComplete` callback — success stays silent, failure logs at `ERROR` with topic + event type + key. Deliberately minimal:

- **No producer-side outbox pattern** (transactional outbox table + relay) — the real fix for atomicity between DB commit and event publish, but a substantial architectural change per service; explicitly deferred (§12) rather than half-built here.
- **No blocking `.get()`** on sends — these publishes sit inside request paths; making every request wait on broker acks would trade a reliability gap for a latency regression.
- With `ERROR`-level logs now centralized in Loki (observability branch), a failing producer is at least *visible* within seconds, which today it is not.

# 11. Observability Integration

- `resilience4j-micrometer` registers everything automatically once the starter is present: `resilience4j_circuitbreaker_state`, `resilience4j_circuitbreaker_calls_total`, `resilience4j_retry_calls_total`, `resilience4j_bulkhead_available_concurrent_calls` — straight into the existing `/actuator/prometheus` → Prometheus → Grafana pipe.
- The fleet dashboard gains one row: circuit-breaker state per instance (timeline), retry rate, bulkhead saturation. DLT growth is visible via the broker's existing per-topic metrics plus a Loki query on the DLT-publish log line.
- Resilience4j events (state transitions) also surface on the actuator (`/actuator/circuitbreakers`) — exposed via the existing `management.endpoints` include list (one word added per calling service).

---

# 12. Deferred / Out of Scope

- **Transactional outbox / exactly-once publishing** (§10) — the correct end-state for DB-commit-vs-publish atomicity; a per-service architectural change that deserves its own design, not a rider on this branch.
- **DLT replay tooling** (admin endpoint or CLI to re-drive DLT records) — deferred until there's an Admin Portal (roadmap Phase 2) to hang it on; manual `kafka-console-consumer` inspection suffices now.
- **Idempotency certification of inventory's hold/reserve endpoints** (would unlock write retries, §6) — inventory-service work with its own tests, tracked in §13.
- **Gateway-level resilience** (per-route timeouts/CBs at api-gateway) — the gateway already fails fast per its own design (`DownstreamErrorHandlingFilter` → 502, rate limiting); duplicating per-service breakers there adds tuning surface without adding safety while each service protects itself. Revisit if the gateway ever fronts services that don't self-protect.
- **Retry budgets / adaptive concurrency** — far beyond this system's scale; the static configs are the right ceremony level.
- **Load testing to tune the numbers** — every threshold in this doc is a reasoned default, explicitly expected to be re-tuned if/when real load data exists (§13).

# 13. Known Risks / Open Questions

- **All thresholds are untested-under-load defaults.** Window size 10 / 50% failure rate / 10s open-state / bulkhead 10 are textbook starting values, chosen to be *safe* (fail fast, recover quickly) rather than *optimal*. The observability dashboards are the feedback loop for tuning them; stated plainly so nobody mistakes them for measured values.
- **Write retries stay off until inventory certifies hold/reserve idempotency** — the endpoints *look* idempotent (keyed on flightId/seat/bookingId) but claiming it without tests on inventory's side would be exactly the kind of assumption this project's process exists to catch. Until then, a timed-out hold surfaces to the user as a retryable 503 — annoying but correct.
- **DLT topics share the broker's single-partition auto-create defaults** — fine at this scale; a real deployment would provision them explicitly (a `feature/kubernetes`-era concern).
- **`ErrorHandlingDeserializer` changes consumer factory config in four services at once** — mechanical but wide; the full-stack Kafka integration tests that already exist (payment, checkin, inventory) are the regression net, plus a new poison-pill test per §15.
- **Aspect order matters** (Bulkhead → CB → Retry) — Resilience4j's default is exactly what §6 requires, but it's config-overridable and easy to break silently; asserted by a dedicated unit test rather than trusted (§15).

# 14. Build Order

1. **Feign timeouts** (§4) — pure YAML, three services. Verified deterministically (design-review correction — a "non-routable IP" connect test behaves differently per host/network and can fail instantly rather than after the timeout): **read timeout** via a local stub server that accepts the connection and then sleeps past 5s (same JDK `HttpServer` pattern api-gateway's tests already use) asserting failure at ~5s not 60s; **connect timeout** via a controlled non-accepting endpoint (a bound socket with a full backlog queue) where achievable, otherwise verified against the live compose stack (paused container) without exact-duration assertions.
2. **Resilience4j starter + circuit breakers** (§5) on booking's two wrapper clients first (the richest seam), unit-tested; then roll to checkin and inventory.
3. **Bulkheads** (§7) — same wrappers, same config profile pattern.
4. **Read-only retries** (§6) — annotation on read methods only; unit test asserts write methods have no retry behavior.
5. **Kafka error handler + DLT + ErrorHandlingDeserializer** (§9) — one service first (payment, it has the strongest existing Kafka integration tests), poison-pill test proving the DLT receives the record; then the other three.
6. **Producer send callbacks** (§10) — five small edits.
7. **Actuator exposure + Grafana dashboard row** (§11).
8. **Full-stack verification** — compose stack up; stop flight-service; drive booking traffic; observe: bounded latency, breaker opening (Grafana), fast 503s, breaker re-closing after restart. Publish a poison message to a real topic; observe it land in the `.DLT`.
9. **Design doc → implemented + Implementation Notes**, per house pattern.

# 15. Testing Plan

| Layer | What's tested | How |
|---|---|---|
| Read timeout | Bounds latency at ~5s, not Feign's 60s default | Deterministic: local JDK `HttpServer` stub accepts the connection, sleeps past the timeout; assert the call fails within a tolerance band of the configured value |
| Connect timeout | Bounds connection establishment | Controlled non-accepting endpoint (bound socket, backlog full) where the platform allows a deterministic assertion; otherwise verified live against a paused container without exact-timing assertions — never a "non-routable IP", which fails instantly-or-slowly depending on the host network (design-review correction) |
| Circuit breaker | Opens after threshold failures; open circuit throws the *domain* exception fast; half-open recovery works | Unit tests on the wrapper clients with a mocked Feign client throwing 5xx; assert `CallNotPermittedException` never leaks past the wrapper |
| 4xx don't trip the breaker | A run of 404s/409s leaves the breaker closed | Same harness, `FeignException.NotFound`/`Conflict` |
| Retry scope | Reads retry (3 attempts, backoff); writes never retry | Mocked Feign client counting invocations; explicit test that `holdSeat`/`reserveSeat` are invoked exactly once on failure |
| Aspect order | Breaker records each retry attempt; open breaker suppresses retries | Unit test asserting call counts against a breaker forced open |
| Bulkhead | Saturation → immediate `XServiceUnavailableException`, not queuing | Concurrent test (same pattern as checkin's existing `CheckInConcurrencyTest`) |
| Kafka retries + DLT | Failing record retried 3× with backoff, then lands in `<topic>.DLT` with exception headers | Full-stack Kafka integration test (Testcontainers, extending each service's existing abstract base) with a listener stubbed to always throw |
| Poison pill | Unparseable JSON goes to DLT, consumer keeps consuming subsequent records | Integration test publishing raw garbage bytes to the topic |
| Producer visibility | Failed send logs at ERROR | Unit test with a failing mock template future |
| End-to-end degradation | Full §14-step-8 manual scenario against the live compose stack | Documented run in Implementation Notes, with the Grafana evidence |

---

# 16. Implementation Notes

Built per §14's order. The two-bean split from the design review held up exactly as promised — the behavior tests it demanded all passed first run. Three findings, one design-doc correction, and a live verification worth recording:

**1. The DLT suffix is `-dlt`, not `.DLT`.** The design doc stated spring-kafka's default DLT naming as `<topic>.DLT`; this spring-kafka version's actual default is `<topic>-dlt` — found empirically when the DLT integration test sat watching `skybook.dlt-test.events.DLT` for 30 seconds while the recoverer's `UNKNOWN_TOPIC` warning plainly named `skybook.dlt-test.events-dlt`. Framework default kept (the doc's own convention-over-invention reasoning); every reference in code comments uses `-dlt`.

**2. A stale `skybook-common` in the local Maven repo silently disabled all logging during test debugging.** Running `mvn -pl payment-service verify` *without* `-am` resolved `skybook-common` from `~/.m2` — a snapshot installed before the observability branch added `skybook-logback-base.xml` — so logback found no config and dropped every log line, which cost a debugging round-trip on the DLT test (no listener evidence, no recoverer evidence, nothing). Worth remembering fleet-wide: on this reactor, per-module builds need `-am` or a fresh `mvn install`.

**3. `record-exceptions` as a positive-only list beats record-plus-ignore.** The design sketched `record-exceptions: [FeignException] + ignore-exceptions: [FeignClientException]`; the implementation lists only what counts as failure (`RetryableException`, `FeignException.FeignServerException`) — anything unlisted (all 4xx) counts as success, which is exactly the intent with one less list to keep coherent.

**Live degradation verification** (§14 step 8, against the compose stack):
- flight-service stopped, valid create-booking traffic through the gateway: attempt 1 failed in 4.7s (3 retry attempts against the dead host), attempt 2 in 699ms, attempts 3-6 in **78ms flat** — breaker OPEN after the failure threshold (failureRate 100%, failedCalls 5, notPermittedCalls 5 on `/actuator/circuitbreakers`), every fast failure a clean 5xx through the gateway. inventory's breaker stayed CLOSED throughout (never called — flight validation fails first), confirming per-dependency isolation.
- flight-service restarted: after the 10s open window, half-open probes succeeded and the breaker returned to CLOSED. The post-restart responses were 404s (empty flight table) — which also live-confirmed that 4xx traffic does not re-trip the breaker.
- Poison pill (`this is not valid json {{{`) published to the real `skybook.booking.events`: appeared in `skybook.booking.events-dlt` three times — once per consumer group (payment, checkin, notification), each having independently failed deserialization and dead-lettered it — and all three consumers kept consuming. Before this branch, that record would have permanently wedged all three.

**Test coverage added:** `FeignTimeoutIntegrationTest` (read timeout bounds a hung downstream at ~5s, lower-bound asserted so an instant connect failure can't fake a pass), `ResilientClientBehaviorTest` (8 tests: breaker open/fail-fast/no-leak, 4xx-don't-trip, read-retry exactly 3×, writes exactly once, open-breaker-suppresses-retries, quiet-compensation semantics), `KafkaDeadLetterIntegrationTest` (bounded 3-delivery retry then DLT with exception headers; poison pill straight to DLT), producer failure-path test (failing future → ERROR log, nothing thrown). Full reactor `mvn clean verify` green after every increment.

**Deferred items unchanged from §12** — most notably write-retry enablement still gated on inventory-service certifying hold/reserve idempotency, and the transactional outbox for producer atomicity.
