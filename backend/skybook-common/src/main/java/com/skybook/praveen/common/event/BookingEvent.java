package com.skybook.praveen.common.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BookingEvent {

    /**
     * Event Type
     */
    private BookingEventType type;

    /**
     * Booking Reference (PNR)
     */
    private String bookingReference;

    /**
     * Recipient Email
     */
    private String contactEmail;

    /**
     * Recipient Name
     */
    private String contactName;

    /**
     * Email Subject
     */
    private String subject;

    /**
     * Email Body
     */
    private String message;
}