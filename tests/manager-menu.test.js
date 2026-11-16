const { test } = require('node:test');
const assert = require('node:assert/strict');
const { app, pool } = require('../index');

test('manager menu renders and validates persisted edits', async t => {
    const item = { menu_item_id: 7, item_name: 'Milk <Tea>', current_price: '4.50' };
    const queries = [];
    let failure;
    let missing = false;
    t.mock.method(pool, 'query', async (sql, values) => {
        queries.push({ sql, values });
        if (failure) throw failure;
        if (sql.startsWith('UPDATE')) {
            if (missing) return { rows: [] };
            Object.assign(item, { item_name: values[0], current_price: values[1].toFixed(2) });
        }
        return { rows: [{ ...item }] };
    });
    t.mock.method(console, 'error', () => {});
    const server = app.listen(0, '127.0.0.1');
    await new Promise(resolve => server.once('listening', resolve));
    t.after(async () => {
        await new Promise(resolve => server.close(resolve));
        await pool.end();
    });
    const base = `http://127.0.0.1:${server.address().port}`;
    const update = (body, id = 7) => fetch(`${base}/api/menu/${id}`, {
        method: 'PATCH', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body)
    });

    const page = await fetch(`${base}/manager/menu`);
    assert.equal(page.status, 200);
    assert.equal(page.headers.get('cache-control'), 'no-store');
    assert.match(await page.text(), /Milk &lt;Tea&gt;/);
    const queryCount = queries.length;
    for (const body of [{ name: ' ', price: 4 }, { name: 'Tea', price: -1 },
        { name: 'Tea', price: 1.123 }, { name: 'Tea', price: '4' },
        { name: 'Tea', price: 1000000 }, { name: 'x'.repeat(201), price: 4 }, {}]) {
        assert.equal((await update(body)).status, 400);
    }
    assert.equal((await update({ name: 'Tea', price: 4 }, 'bad')).status, 400);
    assert.equal(queries.length, queryCount, 'invalid edits never reach the database');

    const saved = await update({ name: '  Jasmine Tea  ', price: 5.25 });
    assert.equal(saved.status, 200);
    assert.deepEqual(await saved.json(), { menu_item_id: 7, item_name: 'Jasmine Tea', current_price: '5.25' });
    assert.deepEqual(queries.at(-1).values, ['Jasmine Tea', 5.25, 7]);
    assert.match(queries.at(-1).sql, /WHERE menu_item_id = \$3/);
    const cashier = await fetch(`${base}/cashier`);
    assert.match(await cashier.text(), /data-name="Jasmine Tea" data-price="5.25"/);
    assert.equal((await update({ name: 'Free Tea', price: 0 })).status, 200);

    missing = true;
    assert.equal((await update({ name: 'Tea', price: 4 })).status, 404);
    missing = false;
    failure = Object.assign(new Error('duplicate'), { code: '23505' });
    assert.equal((await update({ name: 'Tea', price: 4 })).status, 409);
    failure = new Error('database unavailable');
    const failedSave = await update({ name: 'Tea', price: 4 });
    assert.equal(failedSave.status, 500);
    assert.doesNotMatch(await failedSave.text(), /database unavailable/);
    const failedPage = await fetch(`${base}/manager/menu`);
    assert.equal(failedPage.status, 500);
    assert.match(await failedPage.text(), /Could not load the menu/);
});
