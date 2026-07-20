package com.airsense.api.services;

import com.airsense.api.entities.GridCell;
import com.airsense.api.repositories.GridCellRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.geo.GeoJsonPoint;
import org.springframework.data.mongodb.core.geo.GeoJsonPolygon;
import org.springframework.data.geo.Point;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
public class GridGenerationService {

    @Autowired
    private GridCellRepository repository;

    /**
     * Generates a simple 10x10 km grid (100 cells) for the given city bounding box.
     * Hardcoded for Delhi's approximate center for mock purposes.
     * 1 degree lat/lon is roughly 111 km. So 1 km is roughly 0.009 degrees.
     */
    public List<GridCell> generateGrid(String cityId) {
        // Idempotent: clear existing
        repository.deleteByCityId(cityId);

        double startLat = 28.50; // South
        double startLon = 77.10; // West
        double step = 0.009; // ~1km

        int rows = 10;
        int cols = 10;

        List<GridCell> cells = new ArrayList<>();

        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                double minLat = startLat + (r * step);
                double maxLat = startLat + ((r + 1) * step);
                double minLon = startLon + (c * step);
                double maxLon = startLon + ((c + 1) * step);

                double centerLat = minLat + (step / 2);
                double centerLon = minLon + (step / 2);

                GeoJsonPoint centroid = new GeoJsonPoint(centerLon, centerLat);
                
                // Polygon needs to be closed (first point == last point)
                GeoJsonPolygon polygon = new GeoJsonPolygon(List.of(
                        new Point(minLon, minLat),
                        new Point(maxLon, minLat),
                        new Point(maxLon, maxLat),
                        new Point(minLon, maxLat),
                        new Point(minLon, minLat)
                ));

                GridCell cell = GridCell.builder()
                        .gridCellId(cityId + "-GRID-" + r + "-" + c)
                        .cityId(cityId)
                        .wardId("WARD-" + ((r * cols + c) % 5 + 1)) // Mock ward
                        .row(r)
                        .col(c)
                        .centroid(centroid)
                        .polygon(polygon)
                        .areaKm2(1.0) // approx 1km x 1km
                        .createdAt(Instant.now())
                        .build();

                cells.add(cell);
            }
        }

        return repository.saveAll(cells);
    }
}
