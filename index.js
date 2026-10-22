const express = require('express');
const { Pool } = require('pg');
const dotenv = require('dotenv').config();

// Create express app
const app = express();
const port = 3000;

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
    pool.end();
    console.log('Application successfully shutdown');
    process.exit(0);
});

app.set("view engine", "ejs");
app.use(express.json());

async function renderCashier(req, res) {
    try {
        const result = await pool.query(
            'SELECT menu_item_id, item_name, current_price FROM menu_item ORDER BY item_name'
        );
        res.render('cashier', { menuItems: result.rows });
    } catch (error) {
        console.error('Could not load cashier menu:', error);
        res.status(500).send('Could not load the menu. Check the database connection.');
    }
}

app.get('/', renderCashier);
app.get('/cashier', renderCashier);
app.post('/orders', async (req, res) => {
    const submittedItems = req.body && req.body.items;
    const allowedCustomizations = new Set(['Boba', 'Less ice', 'More ice', 'Less sweet']);
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
            if (!menuItem || !Number.isSafeInteger(quantity) || quantity < 1 || quantity > 1000 ||
                customizations.some(value => !allowedCustomizations.has(value)) ||
                new Set(customizations).size !== customizations.length) {
                throw new Error('One or more order items are invalid. Refresh the page and try again.');
            }
            const unitPrice = Number(menuItem.current_price) + (customizations.includes('Boba') ? 0.99 : 0);
            return { menuItemId, quantity, customizations, menuItem, unitPrice };
        });

        const total = items.reduce((sum, item) => sum + item.unitPrice * item.quantity, 0);
        const idResult = await client.query(
            'SELECT (SELECT COALESCE(MAX(order_id), 0) + 1 FROM customer_order) AS order_id, ' +
            '(SELECT COALESCE(MAX(order_item_id), 0) + 1 FROM order_item) AS order_item_id'
        );
        let orderId = Number(idResult.rows[0].order_id);
        let orderItemId = Number(idResult.rows[0].order_item_id);
        await client.query(
            'INSERT INTO customer_order(order_id, order_time, total_price) VALUES ($1, CURRENT_TIMESTAMP, $2)',
            [orderId, total.toFixed(2)]
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

        await client.query('COMMIT');
        return res.json({ orderId, total: total.toFixed(2) });
    } catch (error) {
        if (client) await client.query('ROLLBACK').catch(() => {});
        const clientError = /invalid|no inventory recipe|not enough inventory/i.test(error.message || '');
        console.error('Could not submit order:', error);
        return res.status(clientError ? 400 : 500).json({
            error: clientError ? error.message : 'Could not submit the order. Please try again.'
        });
    } finally {
        if (client) client.release();
    }
});

app.listen(port, () => {
    console.log(`Example app listening at http://localhost:${port}`);
});
