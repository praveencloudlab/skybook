package com.skybook.praveen.checkinservice.service;

import com.skybook.praveen.checkinservice.dto.response.FlightManifestResponse;

import java.time.LocalDateTime;

public interface ManifestService {

    /**
     * Live view computed from current CheckIn rows regardless of the
     * manifest's own persisted status (design doc section 3.5/5.7)
     * - implementation note: this module does not snapshot CheckIn rows at
     * finalize time, so "frozen" in practice means the finalize action
     * itself is idempotent/locked, not that every subsequent read is
     * byte-for-byte historical. In practice CheckIn rows for a departed
     * flight rarely change afterward, so this rarely matters.
     */
    FlightManifestResponse getManifest(Long flightId);

    /** Idempotent - a no-op if already FINALIZED. Rejects if the gate hasn't closed yet. */
    FlightManifestResponse finalizeManifest(Long flightId, LocalDateTime now);

    /**
     * Scheduler entry point (design doc section 5.7/10) - finalizes every
     * flight with at least one CheckIn whose gate has closed and that isn't
     * already FINALIZED. Computes its own cutoff (no external I/O involved
     * in manifest finalization, unlike CheckInFacade's operations, so there
     * is no reason to push the config out to the caller here). Returns the
     * number of manifests newly finalized.
     */
    int finalizeDueManifests();
}
