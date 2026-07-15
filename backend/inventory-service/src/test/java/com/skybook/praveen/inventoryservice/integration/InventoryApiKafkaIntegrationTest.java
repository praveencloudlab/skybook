package com.skybook.praveen.inventoryservice.integration;

import com.skybook.praveen.inventoryservice.client.FlightDetails;
import com.skybook.praveen.inventoryservice.client.FlightServiceClient;
import com.skybook.praveen.inventoryservice.dto.request.CreateAircraftRequest;
import com.skybook.praveen.inventoryservice.dto.request.CreateAircraftSeatRequest;
import com.skybook.praveen.inventoryservice.dto.request.CreateFlightInventoryRequest;
import com.skybook.praveen.inventoryservice.dto.request.CreateSeatMapRequest;
import com.skybook.praveen.inventoryservice.dto.request.HoldSeatRequest;
import com.skybook.praveen.inventoryservice.dto.request.ReleaseSeatRequest;
import com.skybook.praveen.inventoryservice.dto.request.ReserveSeatRequest;
import com.skybook.praveen.inventoryservice.dto.response.AircraftResponse;
import com.skybook.praveen.inventoryservice.dto.response.AircraftSeatResponse;
import com.skybook.praveen.inventoryservice.dto.response.FlightInventoryResponse;
import com.skybook.praveen.inventoryservice.dto.response.SeatHoldResponse;
import com.skybook.praveen.inventoryservice.dto.response.SeatReservationResponse;
import com.skybook.praveen.inventoryservice.enums.InventoryStatus;
import com.skybook.praveen.inventoryservice.enums.SeatHoldStatus;
import com.skybook.praveen.inventoryservice.enums.SeatPosition;
import com.skybook.praveen.inventoryservice.enums.SeatReservationStatus;
import com.skybook.praveen.inventoryservice.enums.SeatType;
import com.skybook.praveen.inventoryservice.repository.AircraftRepository;
import com.skybook.praveen.inventoryservice.repository.AircraftSeatRepository;
import com.skybook.praveen.inventoryservice.repository.FlightInventoryRepository;
import com.skybook.praveen.inventoryservice.repository.InventoryHistoryRepository;
import com.skybook.praveen.inventoryservice.repository.SeatHoldRepository;
import com.skybook.praveen.inventoryservice.repository.SeatReservationRepository;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * Full-stack integration test: real HTTP (TestRestTemplate), real PostgreSQL
 * and real Kafka (both Testcontainers). The only mocked boundary is
 * FlightServiceClient - flight-service is another deployable, stubbed at the
 * anti-corruption layer exactly where Feign errors are translated.
 *
 * Also serves as the Kafka verification: events published by the facade are
 * consumed back off skybook.inventory.events and asserted by type.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class InventoryApiKafkaIntegrationTest {

    // Reuses the singleton PostgreSQL container from the abstract base
    // (same package); Kafka gets its own singleton here.
    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.0"));

    static {
        KAFKA.start();
    }

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", AbstractPostgresSpringBootTest.POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", AbstractPostgresSpringBootTest.POSTGRES::getUsername);
        registry.add("spring.datasource.password", AbstractPostgresSpringBootTest.POSTGRES::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("inventory.hold.sweep-interval-ms", () -> "600000");
    }

    private static final long FLIGHT_ID = 555L;
    private static final long BOOKING_ID = 42L;

    @Autowired
    private TestRestTemplate rest;

    @MockitoBean
    private FlightServiceClient flightServiceClient;

    @Autowired
    private AircraftRepository aircraftRepository;
    @Autowired
    private AircraftSeatRepository aircraftSeatRepository;
    @Autowired
    private FlightInventoryRepository flightInventoryRepository;
    @Autowired
    private SeatHoldRepository seatHoldRepository;
    @Autowired
    private SeatReservationRepository seatReservationRepository;
    @Autowired
    private InventoryHistoryRepository inventoryHistoryRepository;

    @BeforeEach
    void stubFlightService() {
        when(flightServiceClient.getFlight(anyLong())).thenReturn(new FlightDetails(
                FLIGHT_ID, "AI131", "LHR", "DEL",
                LocalDateTime.now().plusDays(7), LocalDateTime.now().plusDays(7).plusHours(9),
                "SCHEDULED"));
    }

    @Test
    void fullLifecycleThroughRealHttpDbAndKafka() {

        // Clean slate (shared DB container with other integration tests).
        seatReservationRepository.deleteAll();
        seatHoldRepository.deleteAll();
        inventoryHistoryRepository.deleteAll();
        flightInventoryRepository.deleteAll();
        aircraftSeatRepository.deleteAll();
        aircraftRepository.deleteAll();

        // 1. Aircraft
        ResponseEntity<AircraftResponse> aircraft = rest.postForEntity("/api/aircraft",
                new CreateAircraftRequest("VT-ITG", "Airbus", "A320neo"), AircraftResponse.class);
        assertThat(aircraft.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Long aircraftId = aircraft.getBody().id();

        // 2. Seat map
        ResponseEntity<AircraftSeatResponse[]> seats = rest.postForEntity(
                "/api/aircraft/" + aircraftId + "/seat-map",
                new CreateSeatMapRequest(List.of(
                        new CreateAircraftSeatRequest("12A", 12, SeatType.ECONOMY, SeatPosition.WINDOW, null),
                        new CreateAircraftSeatRequest("12B", 12, SeatType.ECONOMY, SeatPosition.MIDDLE, null),
                        new CreateAircraftSeatRequest("12C", 12, SeatType.ECONOMY, SeatPosition.AISLE, null))),
                AircraftSeatResponse[].class);
        assertThat(seats.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(seats.getBody()).hasSize(3);

        // 3. Inventory (facade validates the stubbed flight, publishes INVENTORY_CREATED)
        ResponseEntity<FlightInventoryResponse> inventory = rest.postForEntity("/api/inventory",
                new CreateFlightInventoryRequest(FLIGHT_ID, aircraftId, 0), FlightInventoryResponse.class);
        assertThat(inventory.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(inventory.getBody().totalSeats()).isEqualTo(3);
        assertThat(inventory.getBody().availableSeats()).isEqualTo(3);

        // 4. Hold 12A
        ResponseEntity<SeatHoldResponse> hold = rest.postForEntity("/api/inventory/hold",
                new HoldSeatRequest(FLIGHT_ID, "12A", BOOKING_ID, 200L, SeatType.ECONOMY),
                SeatHoldResponse.class);
        assertThat(hold.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(hold.getBody().status()).isEqualTo(SeatHoldStatus.ACTIVE);
        assertThat(hold.getBody().expiresAt()).isAfter(LocalDateTime.now());

        // 4b. Racing hold on the same seat by a different passenger gets 409 through the full stack.
        ResponseEntity<String> conflict = rest.postForEntity("/api/inventory/hold",
                new HoldSeatRequest(FLIGHT_ID, "12A", 99L, 990L, SeatType.ECONOMY), String.class);
        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // 5. Reserve (auto-resolves the booking's own hold)
        ResponseEntity<SeatReservationResponse> reservation = rest.postForEntity("/api/reservations",
                new ReserveSeatRequest(FLIGHT_ID, "12A", BOOKING_ID, 200L, null), SeatReservationResponse.class);
        assertThat(reservation.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(reservation.getBody().status()).isEqualTo(SeatReservationStatus.RESERVED);
        assertThat(reservation.getBody().originatingHoldId()).isEqualTo(hold.getBody().id());

        // 6. Counts after hold->confirm: 2 available, 0 held, 1 reserved
        FlightInventoryResponse counts = rest.getForObject(
                "/api/inventory/flight/" + FLIGHT_ID, FlightInventoryResponse.class);
        assertThat(counts.availableSeats()).isEqualTo(2);
        assertThat(counts.heldSeats()).isZero();
        assertThat(counts.reservedSeats()).isEqualTo(1);
        assertThat(counts.status()).isEqualTo(InventoryStatus.OPEN);

        // 7. Cancel the reservation - seat returns to the pool
        ResponseEntity<SeatReservationResponse> cancelled = rest.exchange(
                "/api/reservations/cancel", HttpMethod.POST,
                new HttpEntity<>(new ReleaseSeatRequest(FLIGHT_ID, "12A", BOOKING_ID, "integration test")),
                SeatReservationResponse.class);
        assertThat(cancelled.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(cancelled.getBody().status()).isEqualTo(SeatReservationStatus.CANCELLED);

        FlightInventoryResponse after = rest.getForObject(
                "/api/inventory/flight/" + FLIGHT_ID, FlightInventoryResponse.class);
        assertThat(after.availableSeats()).isEqualTo(3);
        assertThat(after.reservedSeats()).isZero();

        // 8. Kafka: the facade published one event per orchestrated operation.
        assertThat(consumeEventTypes(Duration.ofSeconds(15)))
                .contains("INVENTORY_CREATED", "SEAT_HELD", "SEAT_RESERVED", "RESERVATION_CANCELLED");
    }

    /** Raw-string consumer on skybook.inventory.events; extracts the "type" field of each JSON event. */
    private Set<String> consumeEventTypes(Duration timeout) {

        Properties props = new Properties();
        props.putAll(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "it-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName(),
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName()));

        Set<String> types = new HashSet<>();
        long deadline = System.currentTimeMillis() + timeout.toMillis();

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of("skybook.inventory.events"));
            while (System.currentTimeMillis() < deadline && types.size() < 4) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, String> record : records) {
                    String value = record.value();
                    int i = value.indexOf("\"type\":\"");
                    if (i >= 0) {
                        int start = i + 8;
                        types.add(value.substring(start, value.indexOf('"', start)));
                    }
                }
            }
        }
        return types;
    }
}
