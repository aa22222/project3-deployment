const { test } = require('node:test');
const assert = require('node:assert/strict');
const { app, pool } = require('../index');

test('cashier exposes keyboard and screen-reader status landmarks', async t => {
    t.mock.method(pool, 'query', async () => ({
        rows: [{ menu_item_id: 1, item_name: 'Jasmine Tea', current_price: '4.50' },
            { menu_item_id: 2, item_name: 'Mochi', current_price: '3.00' }]
    }));
    const server = app.listen(0, '127.0.0.1');
    await new Promise(resolve => server.once('listening', resolve));
    t.after(async () => {
        await new Promise(resolve => server.close(resolve));
        await pool.end();
    });

    const response = await fetch(`http://127.0.0.1:${server.address().port}/cashier`);
    assert.equal(response.status, 200);
    const html = await response.text();
    assert.match(html, /<a class="skip-link" href="#menu-heading">Skip to menu<\/a>/);
    assert.match(html, /<h2 tabindex="-1" id="menu-heading">Menu<\/h2>/);
    assert.match(html, /aria-label="Add Jasmine Tea for \$4\.50"/);
    assert.match(html, /<div id="cart" role="list" aria-label="Current order"><\/div>/);
    assert.match(html, /id="cart-status" class="sr-only" role="status" aria-live="polite" aria-atomic="true"/);
    assert.match(html, /<label class="payment-label" for="payment-method">Payment method<\/label>/);
    assert.match(html, /id="notice" role="status" aria-live="polite" aria-atomic="true"/);
    assert.match(html, /<h2 tabindex="-1" id="receipt-title">Boba Shop<\/h2>/);
    assert.match(html, /<dialog id="drink-editor"/);
    assert.match(html, /data-name="Jasmine Tea"[^>]+data-customizations="Boba\|Less ice\|More ice\|Less sweet"/);
    assert.match(html, /data-name="Mochi"[^>]+data-customizations=""/);
    assert.doesNotMatch(html, /window\.prompt\(/);
    assert.doesNotMatch(html, /id="page-language"|id="weather-data"|id="assistant-form"/);
});
