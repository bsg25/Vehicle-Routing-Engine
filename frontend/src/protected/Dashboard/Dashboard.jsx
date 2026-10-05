import { useState, useEffect } from 'react';
import { Link } from 'react-router-dom';
import { MapContainer, TileLayer, useMap } from 'react-leaflet';
import L from 'leaflet';
import axios from 'axios';
import 'leaflet/dist/leaflet.css';

import api from '../../api';
import './Dashboard.css';

const geocode = async (query) => {


  const res = await axios.get('https://nominatim.openstreetmap.org/search', {
    params: { q: query, format: 'json', limit: 1 },
    headers: { 'Accept-Language': 'en' },
  });
  if (!res.data.length) throw new Error(`Location not found: "${query}"`);
  return { lat: parseFloat(res.data[0].lat), lon: parseFloat(res.data[0].lon) };

};

const RouteLayer = ({ routeData, pins }) => {

  
  const map = useMap();

  useEffect(() => {
    if (!routeData?.geometry?.length) return;

    const coords = [
      [pins.source.lat, pins.source.lon],
      ...routeData.geometry.map(p => [p.lat, p.lon]),
      [pins.dest.lat, pins.dest.lon],
    ];

    const polyline = L.polyline(coords, { color: '#a855f7', weight: 4, opacity: 0.8 }).addTo(map);
    const startMarker = L.circleMarker([pins.source.lat, pins.source.lon], { radius: 8, fillColor: '#22c55e', color: '#fff', weight: 2, fillOpacity: 1 }).addTo(map);
    const endMarker = L.circleMarker([pins.dest.lat, pins.dest.lon], { radius: 8, fillColor: '#ef4444', color: '#fff', weight: 2, fillOpacity: 1 }).addTo(map);

    const stepMarkers = routeData.steps.map((step) => {
      const coord = routeData.geometry[step.wayPoints[0]];
      if (!coord) return null;
      // Leaflet renders string tooltips as raw HTML; road names come from
      // community-editable OSM data, so pass a text node instead.
      const tooltipEl = document.createElement('span');
      tooltipEl.textContent = step.instruction;
      return L.circleMarker([coord.lat, coord.lon], {
        radius: 6, fillColor: '#fff', color: '#a855f7', weight: 2, fillOpacity: 1,
      })
        .bindTooltip(tooltipEl, { permanent: false, direction: 'top' })
        .addTo(map);
    }).filter(Boolean);

    map.fitBounds(coords);

    return () => {
      polyline.remove();
      startMarker.remove();
      endMarker.remove();
      stepMarkers.forEach(m => m.remove());
    };
  }, [routeData, map]);

  return null;
};

const Dashboard = () => {


  const [start, setStart] = useState('');
  const [destination, setDestination] = useState('');
  const [routeData, setRouteData] = useState(null);
  const [pins, setPins] = useState(null);
  const [error, setError] = useState(null);
  const [loading, setLoading] = useState(false);

  const handleRoute = async (e) => {
    e.preventDefault();
    setError(null);
    setLoading(true);

    try {
      const [source, dest] = await Promise.all([geocode(start), geocode(destination)]);
      const response = await api.post('/api/route', { source, destination: dest });
      setRouteData(response.data);
      setPins({ source, dest });
    } catch (err) {
      setError(err.message || err.response?.data?.message || 'Failed to get route');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className='dashboard'>
      <aside className='dashboard-sidebar'>
        <div className='dashboard-sidebar-top'>
          <Link to='/' className='sidebar-logo'>
            <span className='sidebar-logo-icon'>GR</span>
            <span className='sidebar-logo-text'>GeoRoute</span>
          </Link>

          <form className='route-form' onSubmit={handleRoute}>
            <p className='route-form-label'>Start</p>
            <input
              className='route-input'
              type='text'
              placeholder='e.g. Central Park, NYC'
              value={start}
              onChange={(e) => setStart(e.target.value)}
              required
            />
            <p className='route-form-label'>Destination</p>
            <input
              className='route-input'
              type='text'
              placeholder='e.g. Times Square, NYC'
              value={destination}
              onChange={(e) => setDestination(e.target.value)}
              required
            />
            {error && <p className='route-error'>{error}</p>}
            <button className='route-submit' type='submit' disabled={loading}>
              {loading ? 'Getting route...' : 'Get Route'}
            </button>
            {loading && (
              <div className='route-progress' role='progressbar' aria-label='Computing route'>
                <div className='route-progress-bar' />
              </div>
            )}
            {routeData && (
              <button className='route-clear' type='button' onClick={() => {
                setStart('');
                setDestination('');
                setRouteData(null);
                setPins(null);
                setError(null);
              }}>
                Clear
              </button>
            )}
          </form>

        </div>
      </aside>

      <div className='dashboard-map'>
        <MapContainer center={[40.7128, -74.0060]} zoom={12} style={{ height: '100%', width: '100%' }}>
          <TileLayer
            url='https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png'
            attribution='&copy; OpenStreetMap contributors'
          />
          {routeData && pins && <RouteLayer routeData={routeData} pins={pins} />}
        </MapContainer>
      </div>
    </div>
  );
};

export default Dashboard;
