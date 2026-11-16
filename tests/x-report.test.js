const { test } = require('node:test');
const assert = require('node:assert/strict');
const express = require('express');
const path = require('node:path');
const { registerXReport, reportQuery } = require('../lib/x-report');

test('X-report renders graphs, safely selects dates, and handles empty/error states', async t => {
    const report = {
        date: '2026-11-16', generated_at: '2026-11-16 14:00:00', timezone: 'America/Chicago',
        orders: '2', sales: '15.00', items: '3', discounts: '5.00',
        hourly: Array.from({ length: 24 }, (_, hour) => ({ hour, sales: hour === 10 ? 15 : 0, orders: hour === 10 ? 2 : 0 })),
        payments: [{ label: 'Cash', orders: 2, sales: 15 }],
        products: [{ label: '<Milk Tea>', quantity: 3, sales: 20 }]
    };
    const queries = [];
    let failure = false;
    const app = express();
    app.set('view engine', 'ejs');
    app.set('views', path.join(__dirname, '..', 'views'));
    registerXReport(app, { query: async (sql, values) => {
        queries.push({ sql, values });
        if (failure) throw new Error('private database details');
        return { rows: [report] };
    } });
    t.mock.method(console, 'error', () => {});
    const server = app.listen(0, '127.0.0.1');
    await new Promise(resolve => server.once('listening', resolve));
    t.after(() => new Promise(resolve => server.close(resolve)));
    const base = `http://127.0.0.1:${server.address().port}/manager/x-report`;
    const page = await fetch(base);
    assert.equal(page.status, 200);
    assert.equal(page.headers.get('cache-control'), 'no-store');
    const html = await page.text();
    assert.match(html, /\$15\.00/);
    assert.match(html, /\$7\.50/);
    assert.match(html, /&lt;Milk Tea&gt;/);
    assert.equal((html.match(/class="hourly-column"/g) || []).length, 24);
    for (const title of ['Hourly sales', 'Sales by payment method', 'Top 10 items by quantity']) assert.ok(html.includes(title));
    assert.deepEqual(queries[0].values, [null]);
    assert.equal((await fetch(`${base}?date=2024-02-29`)).status, 200);
    assert.deepEqual(queries.at(-1).values, ['2024-02-29']);
    const count = queries.length;
    for (const date of ['2026-02-29', '2026-11-31', '0000-01-01', 'garbage', '2026-11-16%27', '']) {
        assert.equal((await fetch(`${base}?date=${date}`)).status, 400);
    }
    assert.equal((await fetch(`${base}?date=2026-11-16&date=2026-11-17`)).status, 400);
    assert.equal(queries.length, count);
    Object.assign(report, { orders: 0, sales: 0, items: 0, discounts: 0, payments: [], products: [], hourly: report.hourly.map(row => ({ ...row, sales: 0, orders: 0 })) });
    const empty = await (await fetch(base)).text();
    assert.match(empty, /No sales recorded/);
    assert.doesNotMatch(empty, /NaN|Infinity/);
    failure = true;
    const failed = await fetch(base);
    assert.equal(failed.status, 500);
    const failedHtml = await failed.text();
    assert.match(failedHtml, /Could not load the X-report/);
    assert.doesNotMatch(failedHtml, /private database details|Sales after discounts/);
});

test('X-report PostgreSQL totals reconcile without multiplying orders or including cancelled orders', { skip: process.env.REWARDS_DB_TEST !== 'true' }, async () => {
    const { pool } = require('../index');
    let client;
    try {
        client = await pool.connect();
        await client.query('BEGIN');
        await client.query(`
            CREATE TEMP TABLE customer_order (order_id bigint, order_time timestamp, total_price numeric, payment_method text) ON COMMIT DROP;
            CREATE TEMP TABLE kitchen_order_status (order_id bigint, status text) ON COMMIT DROP;
            CREATE TEMP TABLE order_item (order_id bigint, menu_item_id integer, quantity integer, unit_price numeric) ON COMMIT DROP;
            CREATE TEMP TABLE menu_item (menu_item_id integer, item_name text) ON COMMIT DROP;
            CREATE TEMP TABLE rewards_activity (order_id bigint, discount_amount numeric) ON COMMIT DROP;
            INSERT INTO customer_order VALUES
                (1, '2020-01-02 00:00', 10, 'Cash'),
                (2, '2020-01-02 12:30', 5, 'Card'),
                (3, '2020-01-02 14:00', 100, 'Cash'),
                (4, '2020-01-03 00:00', 200, 'Cash'),
                (5, '2020-01-01 23:59', 300, 'Cash');
            INSERT INTO kitchen_order_status VALUES (2, 'Done'), (3, 'Cancelled');
            INSERT INTO menu_item VALUES (1, 'Tea'), (2, 'Mochi');
            INSERT INTO order_item VALUES (1, 1, 2, 5), (1, 2, 1, 5), (2, 1, 1, 5), (3, 1, 20, 5);
            INSERT INTO rewards_activity VALUES (1, 5);
        `);
        const { rows: [report] } = await client.query(reportQuery, ['2020-01-02']);
        assert.equal(Number(report.orders), 2);
        assert.equal(Number(report.sales), 15);
        assert.equal(Number(report.items), 4);
        assert.equal(Number(report.discounts), 5);
        assert.equal(report.hourly.length, 24);
        assert.equal(report.hourly.reduce((sum, row) => sum + Number(row.sales), 0), 15);
        assert.equal(report.payments.reduce((sum, row) => sum + Number(row.sales), 0), 15);
        assert.equal(report.products[0].label, 'Tea');
        assert.equal(Number(report.products[0].quantity), 3);
        const { rows: [empty] } = await client.query(reportQuery, ['2019-01-01']);
        assert.equal(Number(empty.sales), 0);
        assert.deepEqual(empty.products, []);
    } finally {
        if (client) {
            await client.query('ROLLBACK');
            client.release();
        }
        await pool.end();
    }
});
