package com.skybook.praveen.bookingservice.mapper;

import com.skybook.praveen.bookingservice.dto.request.PassengerBookingDetail;
import com.skybook.praveen.bookingservice.entity.Passenger;

public final class PassengerMapper {

    private PassengerMapper() {
    }

    public static Passenger toEntity(PassengerBookingDetail detail) {
        return Passenger.builder()
                .title(detail.title())
                .firstName(detail.firstName())
                .middleName(detail.middleName())
                .lastName(detail.lastName())
                .dob(detail.dob())
                .gender(detail.gender())
                .nationality(detail.nationality().toUpperCase())
                .passportNumber(detail.passportNumber().toUpperCase())
                .passportExpiry(detail.passportExpiry())
                .email(detail.email())
                .phone(detail.phone())
                .build();
    }
}
