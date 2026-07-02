package com.skybook.praveen.bookingservice.repository;

import com.skybook.praveen.bookingservice.entity.Passenger;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PassengerRepository extends JpaRepository<Passenger, Long> {

    List<Passenger> findByPassportNumber(String passportNumber);

    List<Passenger> findByFirstNameContainingIgnoreCaseOrLastNameContainingIgnoreCase(
            String firstName, String lastName);
}
