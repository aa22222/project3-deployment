const { test } = require('node:test');
const assert = require('node:assert/strict');
const express = require('express');
const { createWeatherRouter } = require('../lib/weather');

test('weather selects future hours across midnight, caches/coalesces requests, and handles failures', async t => {
    let now = Date.parse('2026-11-17T05:30:00Z'); // 11:30 PM in College Station.
    t.mock.method(Date, 'now', () => now);
    const app = express(); app.use('/weather', createWeatherRouter());
    const server = app.listen(0, '127.0.0.1');
    await new Promise(resolve => server.once('listening', resolve));
    t.after(() => new Promise(resolve => server.close(resolve)));
    const endpoint = `http://127.0.0.1:${server.address().port}/weather`;
    const realFetch = globalThis.fetch;
    let calls = 0, mode = 'ok';
    t.mock.method(globalThis, 'fetch', async (url, options) => {
        if (!String(url).startsWith('https://api.open-meteo.com/')) return realFetch(url, options);
        calls++;
        const params = new URL(url).searchParams;
        assert.equal(params.get('timezone'), 'America/Chicago');
        assert.equal(params.get('temperature_unit'), 'fahrenheit');
        assert.equal(params.get('timeformat'), 'unixtime');
        assert.equal(params.get('apikey'), null);
        assert.ok(options.signal);
        if (mode === 'timeout') throw new DOMException('Timed out', 'TimeoutError');
        if (mode === 'http') return Response.json({}, { status: 503 });
        if (mode === 'malformed') return Response.json({ current: { temperature_2m: null } });
        return Response.json({
            current_units: { temperature_2m: '°F' }, hourly_units: { temperature_2m: '°F' },
            current: { time: now / 1000, temperature_2m: 68, weather_code: 999 },
            hourly: { time: [-7200, -1800, 1800, 5400, 9000, 12600].map(offset => now / 1000 + offset), temperature_2m: [68, 67, 66, 65, 64, 63], weather_code: [0, 1, 2, 3, 61, 80] }
        });
    });
    const results = await Promise.all([realFetch(endpoint), realFetch(endpoint)]);
    assert.ok(results.every(result => result.status === 200));
    assert.equal(calls, 1);
    const weather = await results[0].json();
    assert.equal(weather.location, 'College Station, TX');
    assert.equal(weather.timezone, 'America/Chicago');
    assert.equal(weather.current.condition, 'Conditions unavailable');
    assert.equal(weather.forecast.length, 3);
    assert.equal(weather.forecast[0].temperature, 66);
    const hour = new Intl.DateTimeFormat('en-US', { timeZone: weather.timezone, hour: 'numeric' }).format(new Date(weather.forecast[0].time * 1000));
    assert.match(hour, /12.*AM/);
    await realFetch(endpoint);
    assert.equal(calls, 1);
    for (mode of ['http', 'timeout', 'malformed']) {
        now += 16 * 60000;
        const failed = await realFetch(endpoint);
        assert.equal(failed.status, 503);
        assert.match((await failed.json()).error, /Weather is unavailable/);
        const count = calls;
        await realFetch(endpoint);
        assert.equal(calls, count, 'failure cooldown prevents provider flooding');
    }
    mode = 'ok'; now += 60000;
    assert.equal((await realFetch(endpoint)).status, 200);
});

test('weather panel appears on kiosk/mobile, not the cashier screen', async t => {
    const { app, pool } = require('../index');
    t.mock.method(pool, 'query', async () => ({ rows: [{ menu_item_id: 1, item_name: 'Milk Tea', current_price: '4.50' }] }));
    const server = app.listen(0, '127.0.0.1');
    await new Promise(resolve => server.once('listening', resolve));
    t.after(async () => { await new Promise(resolve => server.close(resolve)); await pool.end(); });
    for (const route of ['/kiosk', '/mobile', '/cashier']) {
        const response = await fetch(`http://127.0.0.1:${server.address().port}${route}`);
        assert.equal(response.status, 200);
        const html = await response.text();
        assert.equal(html.includes('id="weather-data"'), route !== '/cashier');
        assert.equal(html.includes('src="/weather.js"'), route !== '/cashier');
    }
});
