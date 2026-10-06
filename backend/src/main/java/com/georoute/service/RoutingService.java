package com.georoute.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.georoute.model.Coordinate;
import com.georoute.model.RouteResult;
import com.georoute.model.RouteStep;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class RoutingService {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();

    public RoutingService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public RouteResult getRoute(Coordinate source, Coordinate destination) {
        long sourceNode = findNearestNode(source.lat(), source.lon());
        long destNode = findNearestNode(destination.lat(), destination.lon());
        return runAStar(sourceNode, destNode, source, destination);
    }

    private long findNearestNode(double lat, double lon) {
        // car_component vertices all touch a drivable road and are mutually reachable (see db/car_graph.sql)
        String sql = """
            SELECT id FROM ways_vertices_pgr
            WHERE car_component
            ORDER BY the_geom <-> ST_SetSRID(ST_Point(?, ?), 4326)
            LIMIT 1
            """;
        try {
            Long id = jdbc.queryForObject(sql, Long.class, lon, lat);
            if (id == null) throw new IllegalStateException("No routable road found near the given location.");
            return id;
        } catch (EmptyResultDataAccessException e) {
            throw new IllegalStateException("No routable road found near the given location.");
        }
    }

    private RouteResult runAStar(long sourceNode, long destNode, Coordinate source, Coordinate destination) {
        String sql = """
            SELECT
                CASE WHEN r.node = w.source
                     THEN ST_AsGeoJSON(w.geom)
                     ELSE ST_AsGeoJSON(ST_Reverse(w.geom))
                END AS geojson,
                r.cost,
                w.name,
                ST_Length(w.geom::geography) AS distance_m
            FROM pgr_aStar(?::text, ?, ?, true) r
            JOIN ways w ON r.edge = w.id
            WHERE r.edge != -1
            ORDER BY r.seq
            """;
        String edgesSql = edgesQueryFor(source, destination);

        List<Coordinate> geometry = new ArrayList<>();
        List<RouteStep> steps = new ArrayList<>();
        double[] totals = new double[2]; // [distance, duration]
        int[] coordIndex = new int[1];
        List<List<Coordinate>> prevHolder = new ArrayList<>();

        jdbc.query(sql, rs -> {
            String geoJson = rs.getString("geojson");
            double cost = rs.getDouble("cost");
            String name = rs.getString("name");
            if (name == null) name = "Unnamed road";
            double distanceM = rs.getDouble("distance_m");

            List<Coordinate> coords = parseGeoJsonCoords(geoJson);
            int startIndex = coordIndex[0];
            geometry.addAll(coords);
            coordIndex[0] += coords.size();
            totals[1] += cost;
            totals[0] += distanceM;

            List<Coordinate> prevCoords = prevHolder.isEmpty() ? null : prevHolder.getFirst();
            String instruction = getInstruction(prevCoords, coords, name);
            if (!steps.isEmpty() && steps.getLast().instruction().equals(instruction)) {
                RouteStep last = steps.getLast();
                steps.set(steps.size() - 1, new RouteStep(instruction, last.distance() + distanceM, new int[]{last.wayPoints()[0], coordIndex[0] - 1}));
            } else {
                steps.add(new RouteStep(instruction, distanceM, new int[]{startIndex, coordIndex[0] - 1}));
            }

            prevHolder.clear();
            prevHolder.add(coords);
        }, edgesSql, sourceNode, destNode);

        if (geometry.isEmpty())
            throw new IllegalStateException("No route found between the given locations.");

        return new RouteResult(geometry, steps, totals[0], totals[1], false);
    }

    // Creates a boundary box using (source, destination) which significantly reduces querying pgAstar() time
    private static String edgesQueryFor(Coordinate source, Coordinate destination) {
        double minLat = Math.min(source.lat(), destination.lat());
        double maxLat = Math.max(source.lat(), destination.lat());
        double minLon = Math.min(source.lon(), destination.lon());
        double maxLon = Math.max(source.lon(), destination.lon());
        double latPad = Math.max(0.1, 0.25 * (maxLat - minLat));
        double lonPad = Math.max(0.1, 0.25 * (maxLon - minLon));
        return String.format(java.util.Locale.US,
                "SELECT id, source, target, cost, reverse_cost, x1, y1, x2, y2 FROM ways "
                        + "WHERE source != target AND highway NOT IN ('footway', 'cycleway', 'path', 'service') "
                        + "AND geom && ST_MakeEnvelope(%.6f, %.6f, %.6f, %.6f, 4326)",
                minLon - lonPad, minLat - latPad, maxLon + lonPad, maxLat + latPad);
    }

    private static String getInstruction(List<Coordinate> prevCoords, List<Coordinate> currCoords, String roadName) {
        if (prevCoords == null || prevCoords.size() < 2 || currCoords.size() < 2)
            return "Continue on " + roadName;

        Coordinate p0 = prevCoords.get(prevCoords.size() - 2);
        Coordinate p1 = prevCoords.getLast();
        Coordinate p2 = currCoords.get(0);
        Coordinate p3 = currCoords.get(1);

        double dx1 = p1.lon() - p0.lon();
        double dy1 = p1.lat() - p0.lat();
        double dx2 = p3.lon() - p2.lon();
        double dy2 = p3.lat() - p2.lat();

        double cross = dx1 * dy2 - dy1 * dx2;
        double dot = dx1 * dx2 + dy1 * dy2;
        double angle = Math.atan2(cross, dot) * 180 / Math.PI;

        if (angle > 150 || angle < -150) return "Make a U-turn onto " + roadName;
        if (angle > 45)  return "Turn left onto " + roadName;
        if (angle > 15)  return "Keep left onto " + roadName;
        if (angle < -45) return "Turn right onto " + roadName;
        if (angle < -15) return "Keep right onto " + roadName;
        return "Continue on " + roadName;
    }

    private List<Coordinate> parseGeoJsonCoords(String geoJson) {
        try {
            JsonNode coords = mapper.readTree(geoJson).get("coordinates");
            List<Coordinate> result = new ArrayList<>();
            for (JsonNode c : coords)
                result.add(new Coordinate(c.get(1).asDouble(), c.get(0).asDouble()));
            return result;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse route geometry.", e);
        }
    }
}
