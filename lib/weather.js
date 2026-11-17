const express = require('express');

const LOCATION = { location: 'College Station, TX', latitude: 30.628, longitude: -96.334, timezone: 'America/Chicago' };
const conditions = new Map([
    [[0], 'Clear sky'], [[1], 'Mostly clear'], [[2], 'Partly cloudy'], [[3], 'Overcast'],
    [[45, 48], 'Fog'], [[51, 53, 55], 'Drizzle'], [[56, 57], 'Freezing drizzle'],
    [[61, 63, 65], 'Rain'], [[66, 67], 'Freezing rain'], [[71, 73, 75, 77], 'Snow'],
    [[80, 81, 82], 'Rain showers'], [[85, 86], 'Snow showers'], [[95], 'Thunderstorm'],
    [[96, 99], 'Thunderstorm with hail']
].flatMap(([codes, label]) => codes.map(code => [code, label])));

function createWeatherRouter() {
    const router = express.Router();
    let cached, expires = 0, retryAfter = 0, pending;
    async function refresh() {
        const url = new URL('https://api.open-meteo.com/v1/forecast');
        url.search = new URLSearchParams({ latitude: LOCATION.latitude, longitude: LOCATION.longitude,
            timezone: LOCATION.timezone, current: 'temperature_2m,weather_code', hourly: 'temperature_2m,weather_code',
            temperature_unit: 'fahrenheit', timeformat: 'unixtime', forecast_days: '2' });
        const response = await fetch(url, { signal: AbortSignal.timeout(6000) });
        if (!response.ok) throw new Error('Weather provider unavailable');
        const data = await response.json(), current = data.current, hourly = data.hourly;
        if (data.current_units?.temperature_2m !== '°F' || data.hourly_units?.temperature_2m !== '°F' ||
            !Number.isFinite(current?.time) || !Number.isFinite(current.temperature_2m) || !Number.isInteger(current.weather_code) ||
            !Array.isArray(hourly?.time) || !Array.isArray(hourly.temperature_2m) || !Array.isArray(hourly.weather_code) ||
            hourly.time.length !== hourly.temperature_2m.length || hourly.time.length !== hourly.weather_code.length) throw new Error('Incomplete weather');
        const reading = (time, temperature, code) => ({ time, temperature, condition: conditions.get(code) || 'Conditions unavailable' });
        const forecast = hourly.time.map((time, i) => reading(time, hourly.temperature_2m[i], hourly.weather_code[i]));
        if (forecast.some(hour => !Number.isFinite(hour.time) || !Number.isFinite(hour.temperature)) ||
            forecast.filter(hour => hour.time > Date.now() / 1000).length < 3) throw new Error('Incomplete forecast');
        cached = { current: reading(current.time, current.temperature_2m, current.weather_code), forecast: forecast.sort((a, b) => a.time - b.time) };
        expires = Date.now() + 15 * 60000;
    }
    router.get('/', async (_req, res) => {
        res.set('Cache-Control', 'no-store');
        try {
            if (!cached || expires <= Date.now()) {
                if (retryAfter > Date.now()) throw new Error('Weather retry cooldown');
                if (!pending) pending = refresh().catch(error => {
                    retryAfter = Date.now() + 30000;
                    throw error;
                }).finally(() => { pending = null; });
                await pending;
            }
            res.json({ location: LOCATION.location, timezone: LOCATION.timezone, current: cached.current,
                forecast: cached.forecast.filter(hour => hour.time > Date.now() / 1000).slice(0, 3) });
        } catch {
            res.status(503).json({ error: 'Weather is unavailable. You can still place an order. Please try again shortly.' });
        }
    });
    return router;
}

module.exports = { createWeatherRouter };
