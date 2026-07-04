# inventory-service

Owns **which seats exist and which can be sold** for every SkyBook flight: fleet master data (aircraft + seat maps), per-flight sellable counts, TTL-bound seat holds, confirmed seat reservations, and an append-only audit trail.

Full module documentation: [`docs/INVENTORY_SERVICE_MODULE.md`](../../docs/INVENTORY_SERVICE_MODULE.md) · Test report: [`docs/inventory-service-test-report.html`](../../docs/inventory-service-test-report.html)

## Quick facts

| | |
|---|---|
| Port | `8084` |
| Database | PostgreSQL `skybook_inventory` (`ddl-auto: update`) |
| Kafka | publishes `InventoryEvent` → `skybook.inventory.events` |
| Calls | flight-service (`GET /api/flights/{id}`) via Feign, only at inventory creation |
| Swagger | http://localhost:8084/swagger-ui.html |
| Health | http://localhost:8084/actuator/health |

## Run

Prerequisites: Java 21, PostgreSQL with `skybook_inventory` created, Kafka on `localhost:9092`, flight-service on `8082`.

```
mvn spring-boot:run -pl inventory-service
```

Key configuration (`application.yml`):

```yaml
inventory.hold.ttl-minutes: 15          # hold TTL before SeatHoldExpiryJob releases the seat
inventory.hold.sweep-interval-ms: 60000 # expiry sweep cadence
flight-service.base-url: http://localhost:8082
```

## API surface

| Area | Base path | Highlights |
|---|---|---|
| Aircraft | `/api/aircraft` | CRUD-ish master data; RETIRED is terminal |
| Seat maps | `/api/aircraft/{id}/seats`, `/seat-map` | bulk create is all-or-nothing |
| Inventory | `/api/inventory` | create (validates flight), search, history, `/hold`, `/release`, close/reopen |
| Reservations | `/api/reservations` | confirm hold or direct-reserve, `/cancel`, by booking/flight |

Error contract: 404 not-found, 409 conflicts (incl. optimistic-lock), **410 expired hold**, 502 flight-service unreachable, 400 validation.

Ready-to-use requests: import [`docs/skybook.postman_collection.json`](../../docs/skybook.postman_collection.json).

## Core invariant

```
availableSeats + heldSeats + reservedSeats + blockedSeats == totalSeats
```

Maintained transactionally in `InventoryServiceImpl`; concurrent seat operations are serialized by optimistic locking (`@Version` on `FlightInventory`) — losers receive 409 and retry. Proven by `SeatHoldConcurrencyTest` against real PostgreSQL.

## Tests

`mvn test -pl inventory-service -am` — Docker required for the integration layers (they skip automatically when Docker is absent).

| Layer | What runs |
|---|---|
| Unit (83) | domain golden tables, services with mocked repositories |
| JPA (Testcontainers PostgreSQL) | constraints, defaults, string enums, cascades, finders |
| WebMvc | all 4 controllers, full error contract |
| Full stack | `InventoryApiKafkaIntegrationTest` — real HTTP + PostgreSQL + Kafka |
| Concurrency | racing holds on real transactions |

Coverage: `mvn test` then open `inventory-service/target/site/jacoco/index.html`.

## Not yet wired (Sprint 5)

booking-service does not call this service yet — seat holds/reservations only happen via direct API calls. Planned: hold on booking creation, confirm on payment, release on cancellation, plus the `BookingEventConsumer`.
