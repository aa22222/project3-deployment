const { test } = require('node:test');
const assert = require('node:assert/strict');
const express = require('express');
const { Pool } = require('pg');
const { createRewards } = require('../lib/rewards');

test('PostgreSQL rewards and order ledger roll back together; notification failure preserves completed rewards', { skip: process.env.REWARDS_DB_TEST !== 'true' }, async () => {
    require('dotenv').config();
    const pool = new Pool({ user: process.env.PSQL_USER, host: process.env.PSQL_HOST, database: process.env.PSQL_DATABASE, password: process.env.PSQL_PASSWORD, port: process.env.PSQL_PORT, ssl: { rejectUnauthorized: false }, connectionTimeoutMillis: 10000 });
    let client, server;
    try {
        client = await pool.connect();
        await client.query('BEGIN');
        // All test tables live only in this connection's temporary schema.
        await client.query('SET LOCAL search_path = pg_temp');
        await client.query('CREATE TEMP TABLE customer_order (order_id BIGINT PRIMARY KEY)');
        const schema = require('node:fs').readFileSync(require('node:path').join(__dirname, '../db/schema.sql'), 'utf8');
        await client.query(schema.slice(schema.indexOf('CREATE TABLE IF NOT EXISTS rewards_customer'), schema.indexOf('CREATE TABLE IF NOT EXISTS kitchen_order_status')));
        await client.query('CREATE TEMP TABLE inventory_item (quantity NUMERIC CHECK (quantity >= 0))');
        await client.query('INSERT INTO inventory_item VALUES (1)');
        let code, failDelivery = false;
        const rewards = createRewards({ pool: client, deliver: async (_contact, text) => {
            if (failDelivery) throw new Error('Test delivery failure');
            code = text.match(/\b\d{6}\b/)[0];
        } });
        const app = express(); app.use(express.json()); app.use('/rewards', rewards.router);
        await new Promise(resolve => { server = app.listen(0, '127.0.0.1', resolve); });
        const base = `http://127.0.0.1:${server.address().port}/rewards`;
        async function post(path, body) {
            const response = await fetch(base + path, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
            assert.equal(response.status, 200);
            return response.json();
        }
        const challenge = await post('/code', { contact: '+12025550199', smsOptIn: true });
        assert.equal(challenge.demoCode, undefined);
        const { token } = await post('/verify', { challengeId: challenge.challengeId, code });
        const purchase = await rewards.prepareOrder(token, false, 5489, client);
        await client.query('INSERT INTO customer_order VALUES (1)');
        await rewards.completeOrder(purchase, 1, client);
        assert.equal(Number((await client.query('SELECT points FROM rewards_customer')).rows[0].points), 54);

        await client.query('SAVEPOINT failed_order');
        const failed = await rewards.prepareOrder(token, true, 998, client);
        await client.query('INSERT INTO customer_order VALUES (2)');
        await rewards.completeOrder(failed, 2, client);
        await assert.rejects(client.query('UPDATE inventory_item SET quantity = -1'), error => error.code === '23514');
        await client.query('ROLLBACK TO SAVEPOINT failed_order');
        assert.equal(Number((await client.query('SELECT points FROM rewards_customer')).rows[0].points), 54);
        assert.equal(Number((await client.query('SELECT COUNT(*) AS count FROM rewards_activity')).rows[0].count), 1);

        const redeemed = await rewards.prepareOrder(token, true, 998, client);
        await client.query('INSERT INTO customer_order VALUES (2)');
        await rewards.completeOrder(redeemed, 2, client);
        assert.equal(redeemed.totalCents, 498);
        assert.equal(redeemed.points, 8);
        const ledger = (await client.query('SELECT * FROM rewards_activity WHERE order_id = 2')).rows[0];
        assert.equal(ledger.discount_amount, '5.00');
        assert.equal(ledger.points_redeemed, 50);
        await assert.rejects(rewards.prepareOrder(token, true, 998, client), error => error.status === 400);
        failDelivery = true;
        assert.equal((await rewards.orderResult(redeemed, 2)).notification, 'failed');
        assert.equal(Number((await client.query('SELECT points FROM rewards_customer')).rows[0].points), 8);
    } finally {
        if (server) await new Promise(resolve => server.close(resolve));
        if (client) { await client.query('ROLLBACK').catch(() => {}); client.release(); }
        await pool.end();
    }
});
