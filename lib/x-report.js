// Orders store UTC in a timestamp without time zone. Use the shop's calendar
// for dates and hours, independently of the PostgreSQL session timezone.
// One statement keeps totals and graphs on the same database snapshot.
const reportQuery = `
WITH bounds AS (
    SELECT COALESCE($1::date, (CURRENT_TIMESTAMP AT TIME ZONE 'America/Chicago')::date) AS day,
           CURRENT_TIMESTAMP AT TIME ZONE 'UTC' AS generated_at
), sales AS (
    SELECT co.*, co.order_time AT TIME ZONE 'UTC' AT TIME ZONE 'America/Chicago' AS local_order_time
    FROM customer_order co CROSS JOIN bounds b
    LEFT JOIN kitchen_order_status kos ON kos.order_id = co.order_id
    WHERE co.order_time >= (b.day::timestamp AT TIME ZONE 'America/Chicago' AT TIME ZONE 'UTC')
      AND co.order_time < LEAST(
          (b.day + INTERVAL '1 day') AT TIME ZONE 'America/Chicago' AT TIME ZONE 'UTC',
          b.generated_at)
      AND COALESCE(kos.status, 'New') <> 'Cancelled'
), hourly AS (
    SELECT h.hour, COALESCE(SUM(s.total_price), 0) AS sales, COUNT(s.order_id) AS orders
    FROM generate_series(0, 23) AS h(hour)
    LEFT JOIN sales s ON EXTRACT(HOUR FROM s.local_order_time) = h.hour
    GROUP BY h.hour ORDER BY h.hour
), payments AS (
    SELECT payment_method AS label, COUNT(*) AS orders, SUM(total_price) AS sales
    FROM sales GROUP BY payment_method ORDER BY payment_method
), products AS (
    SELECT mi.menu_item_id, mi.item_name AS label, SUM(oi.quantity) AS quantity,
           SUM(oi.quantity * oi.unit_price) AS sales
    FROM sales s JOIN order_item oi ON oi.order_id = s.order_id
    JOIN menu_item mi ON mi.menu_item_id = oi.menu_item_id
    GROUP BY mi.menu_item_id, mi.item_name
    ORDER BY quantity DESC, sales DESC, mi.item_name, mi.menu_item_id LIMIT 10
)
SELECT (SELECT day::text FROM bounds) AS date,
       (SELECT TO_CHAR(generated_at AT TIME ZONE 'UTC' AT TIME ZONE 'America/Chicago', 'YYYY-MM-DD HH24:MI:SS') FROM bounds) AS generated_at,
       'America/Chicago' AS timezone,
       (SELECT COUNT(*) FROM sales) AS orders,
       (SELECT COALESCE(SUM(total_price), 0) FROM sales) AS sales,
       (SELECT COALESCE(SUM(oi.quantity), 0) FROM order_item oi JOIN sales s USING (order_id)) AS items,
       (SELECT COALESCE(SUM(ra.discount_amount), 0) FROM rewards_activity ra JOIN sales s USING (order_id)) AS discounts,
       (SELECT COALESCE(json_agg(hourly), '[]'::json) FROM hourly) AS hourly,
       (SELECT COALESCE(json_agg(payments), '[]'::json) FROM payments) AS payments,
       (SELECT COALESCE(json_agg(products), '[]'::json) FROM products) AS products
`;

function validReportDate(value) {
    if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(value) || value.startsWith('0000')) return false;
    const date = new Date(`${value}T00:00:00Z`);
    return Number.isFinite(date.getTime()) && date.toISOString().slice(0, 10) === value;
}

function registerXReport(app, pool) {
    app.get('/manager/x-report', async (req, res) => {
        res.set('Cache-Control', 'no-store');
        const date = req.query.date;
        if (date !== undefined && !validReportDate(date)) {
            return res.status(400).render('x-report', { report: null, error: 'Choose a valid report date.', selectedDate: '' });
        }
        try {
            const { rows } = await pool.query(reportQuery, [date || null]);
            res.render('x-report', { report: rows[0], error: '', selectedDate: rows[0].date });
        } catch (error) {
            console.error('Could not load X-report:', error);
            res.status(500).render('x-report', { report: null, error: 'Could not load the X-report. Please refresh to try again.', selectedDate: date || '' });
        }
    });
}

module.exports = { registerXReport, reportQuery, validReportDate };
