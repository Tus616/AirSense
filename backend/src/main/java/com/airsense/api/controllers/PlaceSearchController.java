package com.airsense.api.controllers;

import com.airsense.api.dto.PlaceSearchResponseDto;
import com.airsense.api.services.NominatimPlaceSearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/places")
public class PlaceSearchController {
    private final NominatimPlaceSearchService placeSearchService;

    @GetMapping("/search")
    public ResponseEntity<PlaceSearchResponseDto> search(@RequestParam String q) {
        return ResponseEntity.ok(placeSearchService.search(q));
    }
}
