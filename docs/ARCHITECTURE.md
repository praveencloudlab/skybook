# SkyBook Microservice Architecture

## 1. Architecture Goals

SkyBook is a flight booking platform built as an enterprise-style microservice system. The system is designed around clear business capabilities, independent service ownership, database isolation, asynchronous communication where useful, and simple REST APIs for synchronous user-facing flows.

The architecture will evolve incrementally. Existing service names and package names must remain unchanged.

## 2. Current Services

### auth-service

Responsible for user registration, password encryption, login, JWT generation, JWT validation, Spring Security integration, protected APIs, and authentication-related user data.

### notification-service

Responsible for consuming notification events and sending user notifications such as registration success emails, booking confirmation emails, payment updates, check-in reminders, and password reset messages.

### skybook-common

Shared Java library for stable cross-service constants, common DTOs, event names, error structures, and utilities that are safe to share across services.

Shared code must remain small. Business logic must not be placed in `skybook-common`.

## 3. Planned Business Services

### flight-service

Owns flight management. This includes airports, aircraft, routes, flight schedules, flight status, departure/arrival time information, and search APIs.

Feature branch: `feature/flight-management`.

### inventory-service

Owns seat inventory and availability. This service decides whether seats are available for a specific flight instance and protects inventory from overbooking.

Feature branch: `feature/inventory-management`.

### booking-service

Owns booking lifecycle. This includes booking creation, booking status, passenger details, selected flight references, selected seats, booking cancellation, and booking history.

Feature branch: `feature/booking-management`.

### payment-service

Owns payment lifecycle. This includes payment initiation, payment status, refunds, and payment provider integration boundaries.

Feature branch: `feature/payment-processing`.

### checkin-service

Owns check-in lifecycle. This includes passenger check-in, boarding pass generation, and check-in status.

Feature branch: `feature/checkin-management`.

### api-gateway

Single external entry point for frontend clients. It will route requests to internal services and later centralize cross-cutting concerns such as authentication validation, request logging, rate limiting, and CORS.

Feature branch: `feature/api-gateway`.

## 4. Service Ownership and Database Boundaries

Each microservice owns its own database schema. No service should directly read or write another service's tables.

Recommended database ownership:

| Service | Database / Schema | Owns |
|---|---|---|
| auth-service | skybook_auth | users, credentials, auth-related data |
| flight-service | skybook_flight | airports, aircraft, routes, flight schedules |
| inventory-service | skybook_inventory | flight inventory, seat availability, holds |
| booking-service | skybook_booking | bookings, passengers, booking status |
| payment-service | skybook_payment | payments, refunds, payment status |
| checkin-service | skybook_checkin | check-ins, boarding passes |
| notification-service | skybook_notification | notification logs, templates if needed |

## 5. Communication Strategy

### Synchronous REST

Use REST when the caller needs an immediate response.

Examples:

- Frontend searches flights through api-gateway -> flight-service.
- Frontend creates booking through api-gateway -> booking-service.
- booking-service checks seat availability with inventory-service during booking.
- api-gateway validates authenticated access using JWT locally or through auth-service where required.

### Asynchronous Kafka Events

Use Kafka when a business event has happened and other services need to react without blocking the original transaction.

Examples:

- `user.registered` -> notification-service sends welcome email.
- `booking.created` -> notification-service sends booking pending email.
- `payment.completed` -> booking-service confirms booking.
- `booking.confirmed` -> notification-service sends confirmation email.
- `checkin.completed` -> notification-service sends boarding pass/check-in email.

## 6. Core Kafka Topics

Kafka topic constants should live in `skybook-common` when reused across services.

Planned topics:

- `skybook.user.registered`
- `skybook.booking.created`
- `skybook.booking.confirmed`
- `skybook.booking.cancelled`
- `skybook.payment.completed`
- `skybook.payment.failed`
- `skybook.checkin.completed`
- `skybook.notification.failed`

## 7. Request Flow Examples

### Registration Flow

1. Client calls auth-service registration API through api-gateway once gateway exists.
2. auth-service validates request.
3. auth-service stores user with BCrypt encrypted password.
4. auth-service publishes `skybook.user.registered`.
5. notification-service consumes event and sends welcome email.

### Login Flow

1. Client sends email and password to auth-service.
2. auth-service validates credentials.
3. auth-service generates JWT.
4. Client uses JWT for protected APIs.

### Flight Search Flow

1. Client calls api-gateway search endpoint.
2. api-gateway routes request to flight-service.
3. flight-service searches active scheduled flights.
4. flight-service returns available flight schedule information.
5. Later, inventory-service can enrich availability if required.

### Booking Flow

1. Client sends booking request.
2. booking-service validates passenger and flight references.
3. booking-service requests inventory hold from inventory-service.
4. booking-service creates booking with pending payment status.
5. booking-service publishes `skybook.booking.created`.
6. payment-service completes payment.
7. payment-service publishes `skybook.payment.completed`.
8. booking-service confirms booking.
9. booking-service publishes `skybook.booking.confirmed`.
10. notification-service sends confirmation email.

## 8. Security Model

- auth-service owns user identity and JWT issuing.
- Every protected service validates JWT before accepting user-specific requests.
- Public APIs are limited to registration, login, and public flight search.
- Internal service-to-service security can be added later through gateway rules, network policies, or service credentials.

## 9. Error Handling Standard

Each service should return consistent error responses.

Recommended response shape:

```json
{
  "timestamp": "2026-06-29T00:00:00Z",
  "status": 400,
  "error": "Bad Request",
  "message": "Validation failed",
  "path": "/api/flights"
}
```

Validation errors should be clear and client-friendly. Internal exceptions should not leak implementation details.

## 10. Flight Management Service Design

### Service Name

`flight-service`

This is a new service under:

```text
backend/flight-service
```

### Package Name

```text
com.skybook.praveen.flightservice
```

### Initial Responsibility

The first version of flight-service will manage flight schedule data and expose basic REST APIs.

### Initial Domain Model

Start small and evolve safely.

#### Flight

Represents one scheduled flight.

Initial fields:

- id
- flightNumber
- airlineCode
- originAirportCode
- destinationAirportCode
- departureTime
- arrivalTime
- status
- createdAt
- updatedAt

### Initial Flight Status Values

- SCHEDULED
- DELAYED
- CANCELLED
- DEPARTED
- ARRIVED

### Initial APIs

Base path:

```text
/api/flights
```

Planned endpoints:

| Method | Path | Purpose |
|---|---|---|
| POST | `/api/flights` | Create scheduled flight |
| GET | `/api/flights/{id}` | Get flight by id |
| GET | `/api/flights/search` | Search flights by origin, destination, and date |
| PATCH | `/api/flights/{id}/status` | Update flight status |

### Initial Persistence

Use PostgreSQL and Spring Data JPA.

Initial database:

```text
skybook_flight
```

For development, Hibernate `ddl-auto=update` may be used initially to move quickly. Flyway can be introduced once the model stabilizes.

## 11. Development Order

Flight management will be built in small stable steps:

1. Add `flight-service` module to backend parent.
2. Add `flight-service` Maven configuration.
3. Add Spring Boot application class.
4. Add minimal configuration.
5. Compile backend.
6. Add domain enum: `FlightStatus`.
7. Add entity: `Flight`.
8. Add repository: `FlightRepository`.
9. Add request/response DTOs.
10. Add service class.
11. Add REST controller.
12. Add validation and exception handling.
13. Test APIs locally.
14. Commit and push after each stable step.

## 12. Non-Goals for Flight Service First Version

The first version will not include:

- seat inventory
- booking creation
- payment
- check-in
- notification sending
- gateway routing
- distributed tracing
- Kubernetes deployment

Those will be handled in their own feature branches.

## 13. Design Decisions

### Why separate flight-service?

Flight management is a separate business capability. It should not be mixed with booking or inventory because flight schedules can exist before any booking or seat allocation happens.

### Why REST for flight search?

Flight search needs an immediate response for the user interface. REST is the simplest and clearest fit.

### Why Kafka for business events?

Kafka allows other services to react to important events without blocking the main user flow. Notification sending, payment completion handling, and check-in messages are good asynchronous use cases.

### Why not put business logic in skybook-common?

Shared business logic creates tight coupling. `skybook-common` should contain only stable shared contracts, constants, and simple utilities.

### Why incremental branches?

Each feature branch maps to one business capability. This keeps code reviews clean and reduces risk when merging into `main`.
