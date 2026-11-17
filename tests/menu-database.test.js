const { test } = require('node:test');
const assert = require('node:assert/strict');

test('mobile and kiosk render the complete PostgreSQL menu with identical IDs and prices', { skip: process.env.REWARDS_DB_TEST !== 'true' }, async () => {
    const { app, pool } = require('../index');
    let server;
    try {
        const { rows } = await pool.query('SELECT menu_item_id, current_price FROM menu_item ORDER BY item_name');
        await new Promise(resolve => { server = app.listen(0, '127.0.0.1', resolve); });
        const base = `http://127.0.0.1:${server.address().port}`;
        const menus = [];
        for (const route of ['/kiosk', '/mobile']) {
            const response = await fetch(base + route);
            assert.equal(response.status, 200);
            const html = await response.text();
            const items = [...html.matchAll(/class="drink"[^>]*data-id="([^"]+)"[^>]*data-name="[^"]*"[^>]*data-price="([^"]+)"/g)].map(match => ({ id: Number(match[1]), price: Number(match[2]) }));
            assert.deepEqual(items, rows.map(row => ({ id: Number(row.menu_item_id), price: Number(row.current_price) })));
            menus.push(items);
            if (route === '/mobile') {
                const images = [...html.matchAll(/class="menu-image" src="([^"]+)"/g)];
                assert.equal(images.length + (html.match(/Image coming soon/g) || []).length, rows.length);
                for (const [, imageUrl] of images) {
                    const image = await fetch(base + imageUrl);
                    assert.equal(image.status, 200);
                    assert.match(image.headers.get('content-type'), /^image\//);
                    await image.arrayBuffer();
                }
            }
        }
        assert.deepEqual(menus[0], menus[1]);
        console.log(`Verified ${rows.length} real menu items on kiosk and mobile`);
    } finally {
        if (server) await new Promise(resolve => server.close(resolve));
        await pool.end();
    }
});
