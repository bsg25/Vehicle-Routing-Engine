-- Car-only routing graph. Run after setup_routing.sql

-- A* previously loaded every edge in the bounding box, including footways,
-- cycleways, paths and service roads (~80% of edges in NYC). Restricting the
-- graph to drivable roads shrinks each A* query's in-memory graph ~6x and stops
-- cars being routed along footpaths.

-- Partial spatial index matching the backend's A* edges query
CREATE INDEX IF NOT EXISTS ways_car_geom_idx ON ways USING GIST (geom)
WHERE source != target AND highway NOT IN ('footway', 'cycleway', 'path', 'service');

-- Mark vertices on the giant connected component of the car-only graph, so
-- snapping never picks a node that only reaches the network via footpaths.
ALTER TABLE ways_vertices_pgr ADD COLUMN IF NOT EXISTS car_component BOOLEAN NOT NULL DEFAULT false;
UPDATE ways_vertices_pgr SET car_component = false WHERE car_component;

CREATE TEMP TABLE car_comp AS
SELECT node, component
FROM pgr_connectedComponents(
  'SELECT id, source, target, cost, reverse_cost FROM ways
   WHERE source != target AND highway NOT IN (''footway'', ''cycleway'', ''path'', ''service'')'
);

WITH giant AS (
  SELECT component FROM car_comp GROUP BY component ORDER BY count(*) DESC LIMIT 1
)
UPDATE ways_vertices_pgr v SET car_component = true
FROM car_comp c, giant g
WHERE c.node = v.id AND c.component = g.component;

-- Partial index: nearest-node search only ever scans car-network vertices
CREATE INDEX IF NOT EXISTS ways_vertices_car_geom_idx ON ways_vertices_pgr USING GIST (the_geom)
WHERE car_component;

ANALYZE ways;
ANALYZE ways_vertices_pgr;
