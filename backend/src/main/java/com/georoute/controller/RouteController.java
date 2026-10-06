package com.georoute.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.georoute.model.Coordinate;
import com.georoute.model.RouteRequest;
import com.georoute.model.RouteResult;
import com.georoute.service.RoutingService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class RouteController {

    // Rough bounding box around New York State, the region covered by the imported OSM extract
    private static final double MIN_LAT = 40.45, MAX_LAT = 45.02;
    private static final double MIN_LON = -79.77, MAX_LON = -71.75;

    private final StringRedisTemplate redis;
    private final RoutingService routingService;
    private final ObjectMapper mapper;
    private final boolean cacheEnabled;

    public RouteController(StringRedisTemplate redis, RoutingService routingService, ObjectMapper mapper,
                           @Value("${app.cache.enabled}") boolean cacheEnabled) {
        this.redis = redis;
        this.routingService = routingService;
        this.mapper = mapper;
        this.cacheEnabled = cacheEnabled;
    }

    @PostMapping("/route")
    public ResponseEntity<?> getRoute(@RequestBody RouteRequest req) throws Exception {

        String validationError = validate(req);
        if (validationError != null)
            return ResponseEntity.badRequest().body(Map.of("message", validationError));

        // Cache disabled (benchmarking baseline): compute every request directly
        if (!cacheEnabled) {
            try {
                return ResponseEntity.ok(routingService.getRoute(req.source(), req.destination()));
            } catch (Exception ex) {
                ex.printStackTrace();
                return ResponseEntity.status(500).body(Map.of("message", "Route calculation failed"));
            }
        }

        String cacheKey = "route:%s,%s:%s,%s".formatted(
                req.source().lat(), req.source().lon(),
                req.destination().lat(), req.destination().lon());
        String lockKey = "lock:" + cacheKey;

        String cached = redis.opsForValue().get(cacheKey);
        if (cached != null)
            return ResponseEntity.ok(fromCache(cached));

        Boolean acquired = redis.opsForValue().setIfAbsent(lockKey, "1", Duration.ofSeconds(30));
        if (!Boolean.TRUE.equals(acquired)) {
            for (int i = 0; i < 6; i++) {
                Thread.sleep(500);
                cached = redis.opsForValue().get(cacheKey);
                if (cached != null)
                    return ResponseEntity.ok(fromCache(cached));
            }
            return ResponseEntity.status(503)
                    .header("Retry-After", "3")
                    .body(Map.of("message", "This route is still being computed, please retry shortly."));
        }

        try {
            RouteResult result = routingService.getRoute(req.source(), req.destination());
            redis.opsForValue().set(cacheKey, mapper.writeValueAsString(result), Duration.ofHours(1));
            return ResponseEntity.ok(result);
        } catch (Exception ex) {
            ex.printStackTrace();
            return ResponseEntity.status(500).body(Map.of("message", "Route calculation failed"));
        } finally {
            redis.delete(lockKey);
        }
    }

    private RouteResult fromCache(String json) throws Exception {
        RouteResult r = mapper.readValue(json, RouteResult.class);
        return new RouteResult(r.geometry(), r.steps(), r.totalDistance(), r.totalDuration(), true);
    }

    private static String validate(RouteRequest req) {
        if (req == null || req.source() == null || req.destination() == null)
            return "Both source and destination coordinates are required.";
        for (Coordinate c : List.of(req.source(), req.destination())) {
            if (!Double.isFinite(c.lat()) || !Double.isFinite(c.lon()))
                return "Coordinates must be finite numbers.";
            if (c.lat() < MIN_LAT || c.lat() > MAX_LAT || c.lon() < MIN_LON || c.lon() > MAX_LON)
                return "Coordinates must be within New York State.";
        }
        return null;
    }
}
