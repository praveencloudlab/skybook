package com.skybook.praveen.checkinservice.controller;

import com.skybook.praveen.checkinservice.dto.response.FlightManifestResponse;
import com.skybook.praveen.checkinservice.service.ManifestService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

@RestController
@RequestMapping("/api/manifests")
@RequiredArgsConstructor
public class FlightManifestController {

    private final ManifestService manifestService;

    @GetMapping("/{flightId}")
    public ResponseEntity<FlightManifestResponse> getManifest(@PathVariable Long flightId) {
        return ResponseEntity.ok(manifestService.getManifest(flightId));
    }

    /** Manual/explicit finalize - the scheduler (ManifestFinalizationJob) covers the normal path. */
    @PostMapping("/{flightId}/finalize")
    public ResponseEntity<FlightManifestResponse> finalizeManifest(@PathVariable Long flightId) {
        return ResponseEntity.ok(manifestService.finalizeManifest(flightId, LocalDateTime.now()));
    }
}
