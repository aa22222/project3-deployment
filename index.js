const express = require('express');
const { Pool } = require('pg');
const fs = require('fs');
const path = require('path');
const dotenv = require('dotenv').config();
const { createRewards } = require('./lib/rewards');

// Create express app
const app = express();
const port = Number(process.env.PORT) || 3000;
const drinkCustomizations = ['Boba', 'Less ice', 'More ice', 'Less sweet'];
function getAvailableCustomizations(itemName) {
    const name = String(itemName).toLowerCase();
    const isFood = /\b(mochi|soup|pie|cake|cookie|pastry|sandwich|salad|snack)\b/.test(name);
    const isDrink = /\b(tea|smoothie|coffee|latte|espresso|juice|lemonade|soda|boba|frappe|milkshake|drink)\b/.test(name);
    return isDrink && !isFood ? drinkCustomizations : [];
}
const kitchenOrderStatuses = new Set(['New', 'Preparing', 'Ready', 'Cancelled', 'Done']);

// Create pool
const pool = new Pool({
    user: process.env.PSQL_USER,
    host: process.env.PSQL_HOST,
    database: process.env.PSQL_DATABASE,
    password: process.env.PSQL_PASSWORD,
    port: process.env.PSQL_PORT,
    ssl: {rejectUnauthorized: false}
});

// Add process hook to shutdown pool
process.on('SIGINT', function() {
    if (pool) pool.end();
    console.log('Application successfully shutdown');
    process.exit(0);
});

app.set("view engine", "ejs");
app.use(express.static('public'));
app.use(express.json());
const rewards = createRewards({ pool });
app.use('/api/rewards', rewards.router);

async function renderOrderPage(req, res, view, pageTitle) {
    try {
        const result = await pool.query(
            'SELECT menu_item_id, item_name, current_price FROM menu_item ORDER BY item_name'
        );
        res.render(view, { menuItems: result.rows.map(item => ({
            ...item,
            customizations: getAvailableCustomizations(item.item_name)
        })), pageTitle });
    } catch (error) {
        console.error(`Could not load ${pageTitle.toLowerCase()} menu:`, error);
        res.status(500).send('Could not load the menu. Check the database connection.');
    }
}

app.get('/', (req, res) => res.render('portal'));
app.get('/manager', async (req, res) => {
    res.set('Cache-Control', 'no-store');
    try {
        const inventory = (await pool.query(
            'SELECT inventory_item_id, item_name, item_type, quantity_in_stock, unit, reorder_level FROM inventory_item ORDER BY item_name'
        )).rows;
        res.render('manager', { inventory, error: '' });
    } catch (error) {
        console.error('Could not load inventory:', error);
        res.status(500).render('manager', { inventory: [], error: 'Could not load inventory. Please refresh to try again.' });
    }
});
app.get('/manager/payroll', async (req, res) => {
    res.set('Cache-Control', 'no-store');
    try {
        const employees = (await pool.query(
            'SELECT employee_id, first_name, last_name, hourly_wage AS hourly_rate, hours_worked, ' +
            'ROUND(hourly_wage * hours_worked, 2) AS gross_pay ' +
            'FROM employee ORDER BY last_name, first_name, employee_id'
        )).rows;
        const safeColumns = employees.length ? Object.keys(employees[0]).filter(column =>
            /(^id$|employee.*id|first.*name|last.*name|full.*name|employee.*name|position|role|title|salary|wage|pay|hour|rate|commission|bonus)/i.test(column)
        ) : [];
        res.render('payroll', { employees, columns: safeColumns, error: '' });
    } catch (error) {
        console.error('Could not load payroll:', error);
        res.status(500).render('payroll', { employees: [], columns: [], error: 'Could not load payroll information. Please refresh to try again.' });
    }
});
app.patch('/api/inventory/:id', async (req, res) => {
    const id = Number(req.params.id);
    const quantity = req.body?.quantity;
    if (!Number.isSafeInteger(id) || id < 1 || typeof quantity !== 'number' ||
        !Number.isFinite(quantity) || quantity < 0 || quantity > 9999999999.99 || !/^\d+(\.\d{1,2})?$/.test(String(quantity))) {
        return res.status(400).json({ error: 'Enter a valid quantity from 0 to 9999999999.99 with at most two decimal places.' });
    }
    try {
        const item = (await pool.query(
            'UPDATE inventory_item SET quantity_in_stock = $1 WHERE inventory_item_id = $2 RETURNING inventory_item_id, quantity_in_stock, reorder_level',
            [quantity, id]
        )).rows[0];
        if (!item) return res.status(404).json({ error: 'Inventory item not found. Refresh the page.' });
        res.json(item);
    } catch (error) {
        console.error('Could not update inventory:', error);
        res.status(500).json({ error: 'Could not save inventory. Please try again.' });
    }
});
app.get('/cashier', (req, res) => renderOrderPage(req, res, 'cashier', 'Cashier'));
app.get('/mobile', (req, res) => renderOrderPage(req, res, 'cashier', 'Mobile Ordering'));
app.get('/kiosk', (req, res) => renderOrderPage(req, res, 'cashier', 'Customer Kiosk'));
app.get('/kitchen', (req, res) => res.render('kitchen'));
// Coalesce mobile checkout retries, including a response lost after the order saved.
const checkoutRequests = new Map();
app.post('/orders', async (req, res, next) => {
    const key = req.get('Idempotency-Key');
    if (!key) return next();
    if (!/^[a-zA-Z0-9-]{16,80}$/.test(key)) return res.status(400).json({ error: 'Invalid checkout identifier.' });
    const payload = JSON.stringify(req.body);
    const existing = checkoutRequests.get(key);
    if (existing) {
        if (existing.payload !== payload) return res.status(409).json({ error: 'This checkout identifier belongs to a different order.' });
        const result = await existing.result;
        return res.status(result.status).json(result.body);
    }
    // Never evict a saved checkout during this server session: a late retry must stay safe.
    if (checkoutRequests.size >= 10000) return res.status(503).json({ error: 'Mobile checkout is temporarily unavailable. Please order at the counter.' });
    let finish;
    const entry = { payload, result: new Promise(resolve => { finish = resolve; }) };
    checkoutRequests.set(key, entry);
    const sendJson = res.json.bind(res);
    res.json = body => {
        finish({ status: res.statusCode, body });
        if (res.statusCode >= 400) checkoutRequests.delete(key);
        return sendJson(body);
    };
    next();
});
app.post('/orders', async (req, res) => {
    const submittedItems = req.body && req.body.items;
    const paymentMethod = req.body && req.body.paymentMethod;
    const allowedPaymentMethods = new Set(['Cash', 'Card', 'Mobile pay']);
    if (!allowedPaymentMethods.has(paymentMethod)) {
        return res.status(400).json({ error: 'Choose Cash, Credit or debit card, or Mobile pay.' });
    }
    if (!Array.isArray(submittedItems) || submittedItems.length === 0) {
        return res.status(400).json({ error: 'Add a drink to the order first.' });
    }

    let client;
    try {
        client = await pool.connect();
        await client.query('BEGIN');
        await client.query('LOCK TABLE customer_order, order_item IN SHARE ROW EXCLUSIVE MODE');
        await client.query('LOCK TABLE inventory_item IN ROW EXCLUSIVE MODE');

        const menuResult = await client.query(
            'SELECT menu_item_id, item_name, current_price FROM menu_item WHERE menu_item_id = ANY($1::int[])',
            [submittedItems.map(item => Number(item.menuItemId))]
        );
        const menuById = new Map(menuResult.rows.map(item => [Number(item.menu_item_id), item]));
        const items = submittedItems.map(item => {
            const menuItemId = Number(item.menuItemId);
            const quantity = Number(item.quantity);
            const customizations = Array.isArray(item.customizations) ? item.customizations : [];
            const menuItem = menuById.get(menuItemId);
            const allowedCustomizations = new Set(getAvailableCustomizations(menuItem?.item_name));
            if (!menuItem || !Number.isSafeInteger(quantity) || quantity < 1 || quantity > 1000 ||
                customizations.some(value => !allowedCustomizations.has(value)) ||
                new Set(customizations).size !== customizations.length ||
                (customizations.includes('Less ice') && customizations.includes('More ice'))) {
                throw new Error('One or more order items are invalid. Refresh the page and try again.');
            }
            const unitPrice = Number(menuItem.current_price) + (customizations.includes('Boba') ? 0.99 : 0);
            return { menuItemId, quantity, customizations, menuItem, unitPrice };
        });

        const total = items.reduce((sum, item) => sum + item.unitPrice * item.quantity, 0);
        const reward = await rewards.prepareOrder(req.body.rewardsToken, req.body.redeemReward, Math.round(total * 100), client);
        const idResult = await client.query(
            'SELECT (SELECT COALESCE(MAX(order_id), 0) + 1 FROM customer_order) AS order_id, ' +
            '(SELECT COALESCE(MAX(order_item_id), 0) + 1 FROM order_item) AS order_item_id'
        );
        let orderId = Number(idResult.rows[0].order_id);
        let orderItemId = Number(idResult.rows[0].order_item_id);
        await client.query(
            'INSERT INTO customer_order(order_id, order_time, total_price, payment_method) VALUES ($1, CURRENT_TIMESTAMP, $2, $3)',
            [orderId, (reward.totalCents / 100).toFixed(2), paymentMethod]
        );

        for (const item of items) {
            await client.query(
                'INSERT INTO order_item(order_item_id, order_id, menu_item_id, quantity, unit_price, customizations) ' +
                'VALUES ($1, $2, $3, $4, $5, $6)',
                [orderItemId++, orderId, item.menuItemId, item.quantity, item.unitPrice.toFixed(2), item.customizations.join(', ')]
            );

            const recipe = await client.query(
                'SELECT inventory_item_id, quantity_required FROM menu_item_ingredient WHERE menu_item_id = $1',
                [item.menuItemId]
            );
            if (recipe.rowCount === 0) {
                throw new Error(`${item.menuItem.item_name} has no inventory recipe configured.`);
            }
            for (const ingredient of recipe.rows) {
                const deduction = Number(ingredient.quantity_required) * item.quantity;
                const stock = await client.query(
                    'UPDATE inventory_item SET quantity_in_stock = quantity_in_stock - $1 ' +
                    'WHERE inventory_item_id = $2 AND quantity_in_stock >= $1',
                    [deduction, ingredient.inventory_item_id]
                );
                if (stock.rowCount !== 1) {
                    throw new Error(`There is not enough inventory for ${item.menuItem.item_name}.`);
                }
            }
        }

        await rewards.completeOrder(reward, orderId, client);
        await client.query('COMMIT');
        client.release();
        client = null;
        return res.json({ orderId, subtotal: total.toFixed(2), discount: (reward.discountCents / 100).toFixed(2), total: (reward.totalCents / 100).toFixed(2), paymentMethod, rewards: await rewards.orderResult(reward, orderId) });
    } catch (error) {
        if (client) await client.query('ROLLBACK').catch(() => {});
        const clientError = /invalid|no inventory recipe|not enough inventory/i.test(error.message || '');
        console.error('Could not submit order:', error);
        return res.status(error.status || (clientError ? 400 : 500)).json({
            error: error.status || clientError ? error.message : 'Could not submit the order. Please try again.'
        });
    } finally {
        if (client) client.release();
    }
});

app.get('/api/kitchen/orders', async (req, res) => {
    try {
        const result = await pool.query(
            'WITH kitchen_queue AS (' +
            'SELECT co.order_id, co.order_time, co.total_price, COALESCE(kos.status, \'New\') AS status ' +
            'FROM customer_order co ' +
            'LEFT JOIN kitchen_order_status kos ON kos.order_id = co.order_id ' +
            'WHERE co.order_time >= CURRENT_TIMESTAMP - INTERVAL \'24 hours\' ' +
            'AND COALESCE(kos.status, \'New\') NOT IN (\'Cancelled\', \'Done\') ' +
            'ORDER BY co.order_time ASC, co.order_id ASC LIMIT 100' +
            ') ' +
            'SELECT q.order_id, q.order_time, q.total_price, oi.menu_item_id, mi.item_name, ' +
            'oi.quantity, oi.unit_price, oi.customizations, q.status ' +
            'FROM kitchen_queue q ' +
            'JOIN order_item oi ON oi.order_id = q.order_id ' +
            'JOIN menu_item mi ON mi.menu_item_id = oi.menu_item_id ' +
            'ORDER BY q.order_time ASC, q.order_id ASC, oi.order_item_id ASC'
        );
        const ordersById = new Map();
        for (const row of result.rows) {
            const orderId = Number(row.order_id);
            if (!ordersById.has(orderId)) {
                ordersById.set(orderId, {
                    orderId,
                    orderTime: row.order_time,
                    total: Number(row.total_price).toFixed(2),
                    items: [],
                    status: row.status || 'New'
                });
            }
            ordersById.get(orderId).items.push({
                name: row.item_name,
                quantity: Number(row.quantity),
                unitPrice: Number(row.unit_price).toFixed(2),
                customizations: row.customizations || ''
            });
        }
        return res.json([...ordersById.values()]);
    } catch (error) {
        console.error('Could not load kitchen orders:', error);
        return res.status(500).json({ error: 'Could not load kitchen orders.' });
    }
});

app.patch('/api/kitchen/orders/:orderId/status', async (req, res) => {
    const orderId = Number(req.params.orderId);
    const { status } = req.body || {};
    if (!Number.isSafeInteger(orderId) || orderId < 1 || !kitchenOrderStatuses.has(status)) {
        return res.status(400).json({ error: 'Choose a valid order status.' });
    }
    try {
        const existingOrder = await pool.query('SELECT 1 FROM customer_order WHERE order_id = $1', [orderId]);
        if (existingOrder.rowCount === 0) return res.status(404).json({ error: 'Order not found.' });
        await pool.query(
            'INSERT INTO kitchen_order_status(order_id, status, updated_at) VALUES ($1, $2, CURRENT_TIMESTAMP) ' +
            'ON CONFLICT (order_id) DO UPDATE SET status = EXCLUDED.status, updated_at = CURRENT_TIMESTAMP',
            [orderId, status]
        );
        return res.json({ orderId, status });
    } catch (error) {
        console.error('Could not update kitchen order:', error);
        return res.status(500).json({ error: 'Could not update the order.' });
    }
});

async function startServer() {
    const schema = fs.readFileSync(path.join(__dirname, 'db', 'schema.sql'), 'utf8');
    await pool.query(schema);
    app.listen(port, () => {
        console.log(`Example app listening at http://localhost:${port}`);
    });
}

if (require.main === module) {
    startServer().catch(error => {
        console.error('Could not initialize the database:', error);
        process.exit(1);
    });
}

module.exports = { app, pool };
