package com.skybook.praveen.checkinservice.controller;

import com.skybook.praveen.checkinservice.dto.response.BoardingPassResponse;
import com.skybook.praveen.checkinservice.dto.response.BoardingPassVerifyResponse;
import com.skybook.praveen.checkinservice.service.BoardingPassService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/boarding-passes")
@RequiredArgsConstructor
public class BoardingPassController {

    private final BoardingPassService boardingPassService;

    @GetMapping("/{id}")
    public ResponseEntity<BoardingPassResponse> getById(@PathVariable Long id) {
        return ResponseEntity.ok(boardingPassService.getById(id));
    }

    /** Gate verification (design doc section 6/7) - 422 on any verification failure, not a 200 body. */
    @GetMapping("/verify")
    public ResponseEntity<BoardingPassVerifyResponse> verify(@RequestParam String token) {
        return ResponseEntity.ok(boardingPassService.verify(token));
    }
}
