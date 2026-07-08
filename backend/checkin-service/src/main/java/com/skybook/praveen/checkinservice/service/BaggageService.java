package com.skybook.praveen.checkinservice.service;

import com.skybook.praveen.checkinservice.dto.request.CreateBaggageRequest;
import com.skybook.praveen.checkinservice.dto.response.BaggageResponse;

import java.util.List;

public interface BaggageService {

    /** Only for a CHECKED_IN passenger (design doc section 5.5). */
    BaggageResponse addBaggage(CreateBaggageRequest request);

    List<BaggageResponse> getByCheckInId(Long checkInId);
}
