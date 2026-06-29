CREATE TABLE flights (
                         id BIGSERIAL PRIMARY KEY,
                         flight_number VARCHAR(20) NOT NULL UNIQUE,
                         airline VARCHAR(100) NOT NULL,
                         source VARCHAR(100) NOT NULL,
                         destination VARCHAR(100) NOT NULL,
                         departure_time TIMESTAMP NOT NULL,
                         arrival_time TIMESTAMP NOT NULL,
                         total_seats INTEGER NOT NULL,
                         available_seats INTEGER NOT NULL,
                         price NUMERIC(10, 2) NOT NULL,
                         created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);