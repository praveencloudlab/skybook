SkyBook SRS v1.0 — Part 1
1. Project Vision
SkyBook is an enterprise-style airline reservation platform where users can search flights, register, book tickets, check in online, choose seats, and generate boarding passes.
Main goal: build a realistic Senior Java Developer portfolio project using:
Java 21
Spring Boot 3
PostgreSQL
Kafka
Docker
JWT Security
Flyway
Next.js
GitHub Actions
________________________________________
2. Project Scope
Version 1 includes
Guest flight search
User registration/login
One-way booking
Round-trip booking
Multi-passenger booking
Mock payment
Booking confirmation
Online check-in
Seat selection during check-in
Boarding pass PDF with QR code
Kafka-based notifications
Admin flight management
Version 1 does not include
Real payment gateway
Real airline API integration
Flight delay management
Loyalty points
Hotel/car rental
Multi-city booking
Mobile app
________________________________________
3. Actors
Guest User
Can:
Search flights
View flight details
Register
Login
Cannot:
Book flight
Check in
Choose seat
Download boarding pass
________________________________________
Registered User
Can:
Search flights
Book flight
Add passengers
View bookings
Cancel booking
Check in online
Choose seat
Download boarding pass
________________________________________
Admin User
Can:
Manage airports
Manage airlines
Manage aircraft
Manage flight schedules
Create flight instances
View bookings
View reports
________________________________________
System
Automatically:
Publishes Kafka events
Generates booking reference / PNR
Generates boarding pass
Logs audit events
Sends mock notifications
________________________________________
4. Core User Journey
Guest searches flight
        ↓
Guest selects flight
        ↓
Guest registers/logs in
        ↓
User enters passenger details
        ↓
User confirms booking
        ↓
Mock payment succeeds
        ↓
Booking confirmed
        ↓
Kafka event: booking.created
        ↓
User checks in online
        ↓
User chooses seat
        ↓
Check-in completed
        ↓
Kafka event: checkin.completed
        ↓
Boarding pass generated
        ↓
User downloads boarding pass PDF
________________________________________
5. Business Rules
Flight Search
Guests can search flights without login.
Search requires origin, destination, journey date, passenger count, and cabin class.
Only active scheduled flights should appear.
Past flights should not appear.
________________________________________
Registration
Email must be unique.
Password must be encrypted using BCrypt.
User role is USER by default.
Admin users are created manually through database seed data.
________________________________________
Booking
Only logged-in users can book.
One booking can contain multiple passengers.
Booking must generate a unique PNR.
Booking starts as PENDING.
After mock payment success, booking becomes CONFIRMED.
A confirmed booking publishes booking.created Kafka event.
Cancelled booking publishes booking.cancelled Kafka event.
________________________________________
Passenger
Passenger is not always the logged-in user.
One user can book for family members.
Passenger details are stored separately from user account.
Adult, child, and infant passengers should be supported.
________________________________________
Seat Selection
Decision locked:
In Version 1, seat selection happens during online check-in.
Reason:
This matches many realistic airline flows.
It keeps booking simpler.
It allows us to build a proper seat map/check-in module.
Later Version 2 can add paid seat selection during booking.
________________________________________
Check-in
Only confirmed bookings can be checked in.
Check-in opens 24 hours before departure.
Check-in closes 2 hours before departure.
User must select seat during check-in.
A passenger can check in only once per flight.
After successful check-in, booking status becomes CHECKED_IN if all passengers are checked in.
________________________________________
Seat Locking
One seat cannot be assigned to two passengers.
Seat assignment must be transactional.
PostgreSQL stored procedure/function will be used for atomic seat assignment.
________________________________________
Boarding Pass
Boarding pass is generated only after successful check-in.
Boarding pass contains passenger, flight, seat, gate, boarding time, and QR code.
Boarding pass must be downloadable as PDF.
________________________________________
6. Non-Functional Requirements
Security
JWT authentication
Refresh token support
BCrypt password hashing
Role-based authorization
Admin APIs protected
User can access only own bookings
Input validation
CORS configured
________________________________________
Performance
Flight search should respond quickly.
Proper indexes must be added.
Stored procedures/functions used only for useful database-heavy operations.
Kafka used for async processing, not search.
________________________________________
Reliability
No duplicate seat assignment.
Booking and payment state must be consistent.
Kafka consumers should be idempotent.
Failures should be logged clearly.
________________________________________
Maintainability
Fixed folder structure.
Same coding style across all services.
Flyway for database migrations.
OpenAPI/Swagger for APIs.
Clear documentation.
________________________________________
7. First Locked Decisions
Area	Decision
Project name	SkyBook
Architecture	Microservices
Database	PostgreSQL
Messaging	Kafka
Auth	JWT + Refresh Token
Seat choice	During check-in
Payment	Mock payment in v1
Frontend	Next.js
CI/CD	GitHub Actions
OS	Windows
IDE	IntelliJ Ultimate


