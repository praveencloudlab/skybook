# ✈️ SkyBook Flight Service API

---

## Project Information

| Property | Value |
|----------|-------|
| Project | SkyBook Airline Reservation System |
| Module | Flight Service |
| Version | 1.0.0 |
| Status | Development |
| Java | 21 |
| Spring Boot | 3.5.x |
| Database | PostgreSQL |
| Build Tool | Maven |
| Documentation | OpenAPI 3 / Swagger |
| Author | Praveen Somireddy |

---

# Table of Contents

1. Overview
2. Responsibilities
3. Architecture
4. Package Structure
5. Technology Stack
6. Module Dependencies
7. Database Design
8. Flight Entity
9. Flight Lifecycle
10. API Design Standards
11. Security
12. Logging
13. Validation Rules
14. Future Enhancements

---

# 1. Overview

Flight Service is one of the core microservices of the SkyBook Airline Reservation System.

It manages every flight available within the system and provides APIs for administrators, airline operators and downstream services.

The service is responsible only for flight management.

It does **NOT** perform:

- Booking
- Payment
- Check-in
- Notification
- Inventory Allocation

Those responsibilities belong to their respective microservices.

---

# 2. Responsibilities

Current Features

- Create Flight
- Bulk Create Flights
- Update Flight
- Search Flights
- Get Flight
- List Flights
- Update Flight Status
- Delay Flight
- Reschedule Flight
- Cancel Flight
- Boarding
- Departure
- Arrival
- Swagger Documentation

Future Features

- Flight Schedule Management
- Recurring Flights
- Aircraft Assignment
- Gate Management
- Terminal Management
- Flight History
- Delay History
- Flight Audit
- Soft Delete
- Restore Flight

---

# 3. Architecture

```
                    Client

                       │

                REST Controller

                       │

                FlightService

                       │

             FlightServiceImpl

                       │

             FlightRepository

                       │

                 PostgreSQL
```

---

## Future Architecture

```
                   Flight Schedule

                           │

                Generate Flight Instances

                           │

                       Flight Entity

                           │

        ┌──────────────┬───────────────┐
        │              │               │
        ▼              ▼               ▼

 Booking Service   Inventory     Notification
```

---

# 4. Package Structure

```
flight-service

├── config
├── controller
├── dto
│   ├── request
│   └── response
├── entity
├── enums
├── exception
├── mapper
├── repository
├── service
│   └── impl
└── util
```

---

# 5. Technology Stack

Backend

- Java 21
- Spring Boot 3.5
- Spring MVC
- Spring Security
- Spring Validation

Persistence

- Spring Data JPA
- PostgreSQL
- Hibernate

Documentation

- OpenAPI 3
- Swagger UI

Messaging

- Kafka (Upcoming Integration)

Testing

- JUnit 5
- Mockito
- Testcontainers (Future)

Build

- Maven

---

# 6. Module Dependencies

Flight Service is consumed by:

```
Booking Service

Inventory Service

Notification Service

Payment Service

Check-in Service
```

Flight Service depends on:

```
skybook-common
```

---

# 7. Database Design

Current Table

```
flights
```

Future Tables

```
flight_schedule

flight_status_history

flight_delay_history

flight_gate_history

flight_terminal_history
```

---

# 8. Flight Entity

Current Fields

| Field | Type | Description |
|--------|------|-------------|
| id | Long | Primary Key |
| flightNumber | String | Unique Flight Number |
| airlineCode | String | Airline Code |
| originAirportCode | String | Departure Airport |
| destinationAirportCode | String | Arrival Airport |
| departureTime | LocalDateTime | Departure |
| arrivalTime | LocalDateTime | Arrival |
| status | FlightStatus | Current Status |

---

## Future Fields

| Field | Description |
|--------|-------------|
| gate | Boarding Gate |
| terminal | Airport Terminal |
| aircraftCode | Aircraft Identifier |
| baseFare | Base Ticket Fare |
| availableSeats | Seats Available |
| totalSeats | Total Capacity |
| deleted | Soft Delete Flag |
| version | Optimistic Lock Version |

---

# 9. Flight Lifecycle

```
CREATE

   │

SCHEDULED

   │

BOARDING

   │

FINAL_CALL

   │

DEPARTED

   │

IN_AIR

   │

LANDED

   │

ARRIVED
```

Exceptional States

```
DELAYED

CANCELLED

DIVERTED

RETURNED_TO_GATE
```

---

# 10. API Design Standards

Base URL

```
/api/flights
```

Content Type

```
application/json
```

Response

```
application/json
```

Date Format

```
ISO-8601
```

Example

```
2026-12-20T10:30:00
```

HTTP Status Codes

| Code | Meaning |
|------|---------|
| 200 | Success |
| 201 | Created |
| 204 | Deleted |
| 400 | Validation Error |
| 401 | Unauthorized |
| 403 | Forbidden |
| 404 | Not Found |
| 409 | Duplicate Resource |
| 500 | Internal Server Error |

---

# 11. Security

Current

- JWT Authentication
- Spring Security

Future

- Role Based Access

Roles

```
ADMIN

AIRLINE_ADMIN

CUSTOMER_SUPPORT

CUSTOMER
```

---

# 12. Logging

Future Logging Strategy

```
INFO

WARN

ERROR

DEBUG
```

Every request will contain

- Correlation Id
- Request Id
- User Id
- Timestamp

---

# 13. Validation Rules

General Rules

- Flight Number must be unique.
- Origin and Destination cannot be the same.
- Departure must be before Arrival.
- Airport codes are always uppercase.
- Airline code is always uppercase.
- Flight number cannot be modified after creation.
- Cancelled flights cannot move to Boarding.
- Arrived flights cannot be rescheduled.

---

# 14. Future Enhancements

Phase 2

- Flight Schedule
- Recurring Flights
- Automatic Flight Generation

Phase 3

- Delay History
- Gate Assignment
- Terminal Assignment

Phase 4

- Aircraft Assignment
- Pricing
- Seat Capacity

Phase 5

- Soft Delete
- Restore Flight
- Audit Trail

Phase 6

- Flyway
- Docker
- Kubernetes
- CI/CD
- Performance Monitoring