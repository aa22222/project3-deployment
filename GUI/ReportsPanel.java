import java.awt.BorderLayout;
import java.awt.BasicStroke;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.Rectangle;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.text.NumberFormat;
import java.text.ParseException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.TextStyle;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.StringJoiner;
import java.util.TimeZone;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.Scrollable;
import javax.swing.SpinnerDateModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingWorker;
import javax.swing.table.DefaultTableModel;

/** Manager reports backed by the current order and inventory tables. */
final class ReportsPanel extends JPanel {
    private static final String[] REPORT_NAMES = {
            "Product Usage Chart",
            "X-report",
            "Daily sales",
            "Weekly sales",
            "Sales Report",
            "Restock Report",
            "Average order value by weekday",
            "Sales by shift",
            "Order size distribution"
    };

    private static final String[] REPORT_SQL = {
            // 0: Product usage query
            "SELECT i.inventory_item_id, i.item_name, "
                    + "SUM(oi.quantity * mii.quantity_required) AS quantity_used, i.unit "
                    + "FROM customer_order co "
                    + "JOIN order_item oi ON oi.order_id = co.order_id "
                    + "JOIN menu_item_ingredient mii ON mii.menu_item_id = oi.menu_item_id "
                    + "JOIN inventory_item i ON i.inventory_item_id = mii.inventory_item_id "
                    + "WHERE co.order_time >= ? AND co.order_time < ? "
                    + "GROUP BY i.inventory_item_id, i.item_name, i.unit "
                    + "ORDER BY quantity_used DESC, i.item_name",
            // 1: X-report query (current date)
            "SELECT EXTRACT(HOUR FROM order_time)::integer AS hour, COUNT(*) AS orders, "
                    + "COALESCE(SUM(total_price), 0) AS revenue FROM customer_order "
                    + "WHERE order_time >= ? AND order_time < ? GROUP BY hour ORDER BY hour",
            // 2: Daily sales query
            "SELECT EXTRACT(HOUR FROM order_time)::integer AS hour, COUNT(*) AS orders, "
                    + "COALESCE(SUM(total_price), 0) AS revenue FROM customer_order "
                    + "WHERE order_time >= ? AND order_time < ? GROUP BY hour ORDER BY hour",
            // 3: Weekly sales query
            "SELECT order_time::date AS day, COUNT(*) AS orders, COALESCE(SUM(total_price), 0) AS revenue "
                    + "FROM customer_order WHERE (order_time >= ? AND order_time < ?) "
                    + "OR (? AND order_time >= ? AND order_time < ?) GROUP BY day ORDER BY day",
            // 4: Sales Report / Top items query
            "WITH sales AS (SELECT oi.menu_item_id, COALESCE(m.item_name, 'Unavailable item #' "
                    + "|| oi.menu_item_id) AS item_name, SUM(oi.quantity) AS units, "
                    + "COALESCE(SUM(oi.quantity * oi.unit_price), 0) AS revenue FROM order_item oi "
                    + "JOIN customer_order co ON co.order_id = oi.order_id "
                    + "LEFT JOIN menu_item m ON m.menu_item_id = oi.menu_item_id "
                    + "WHERE co.order_time >= ? AND co.order_time < ? "
                    + "GROUP BY oi.menu_item_id, m.item_name) "
                    + "SELECT item_name, units, revenue, SUM(units) OVER() AS total_units, "
                    + "FIRST_VALUE(item_name) OVER(ORDER BY units DESC, revenue DESC, item_name, menu_item_id) "
                    + "AS best_item, MAX(units) OVER() AS best_units FROM sales "
                    + "ORDER BY CASE WHEN ? THEN units ELSE revenue END DESC, units DESC, item_name, menu_item_id LIMIT ?",
            // 5: Restock report query
            "SELECT CASE WHEN quantity_in_stock = 0 THEN 'OUT OF STOCK' ELSE 'LOW STOCK' END AS status, "
                    + "item_name, quantity_in_stock, unit, reorder_level, "
                    + "GREATEST(reorder_level - quantity_in_stock, 0) AS shortage FROM inventory_item "
                    + "WHERE quantity_in_stock <= reorder_level ORDER BY (quantity_in_stock = 0) DESC, "
                    + "CASE WHEN reorder_level > 0 THEN quantity_in_stock / reorder_level ELSE 0 END, item_name",
            // 6: Weekday AOV query
            "SELECT EXTRACT(ISODOW FROM order_time)::integer AS weekday, COUNT(*) AS orders, "
                    + "COALESCE(SUM(total_price), 0) AS revenue FROM customer_order "
                    + "WHERE order_time >= ? AND order_time < ? GROUP BY weekday ORDER BY weekday",
            // 7: Sales by shift query
            "SELECT CASE WHEN EXTRACT(HOUR FROM order_time) >= ? AND EXTRACT(HOUR FROM order_time) < ? THEN 0 "
                    + "WHEN EXTRACT(HOUR FROM order_time) >= ? AND EXTRACT(HOUR FROM order_time) < ? THEN 1 "
                    + "ELSE 2 END AS block, COUNT(*) AS orders, COALESCE(SUM(total_price), 0) AS revenue "
                    + "FROM customer_order WHERE order_time >= ? AND order_time < ? GROUP BY block ORDER BY block",
            // 8: Order size distribution query
            "WITH sizes AS (SELECT co.order_id, COALESCE(SUM(oi.quantity), 0) AS items "
                    + "FROM customer_order co LEFT JOIN order_item oi ON oi.order_id = co.order_id "
                    + "WHERE co.order_time >= ? AND co.order_time < ? GROUP BY co.order_id), "
                    + "stats AS (SELECT COUNT(*) AS total_orders, AVG(items) AS average_items, "
                    + "PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY items) AS median_items, "
                    + "MODE() WITHIN GROUP (ORDER BY items) AS common_size FROM sizes) "
                    + "SELECT LEAST(items, 5)::integer AS category, COUNT(*) AS orders, "
                    + "total_orders, average_items, median_items, common_size FROM sizes CROSS JOIN stats "
                    + "GROUP BY LEAST(items, 5), total_orders, average_items, median_items, common_size ORDER BY category"
    };

    private final JComboBox<String> choices = new JComboBox<>(REPORT_NAMES);
    private static final ZoneId STORE_ZONE = ZoneId.of("America/Chicago");
    private final NumberFormat currency = NumberFormat.getCurrencyInstance(Locale.US);
    private final JButton run = new JButton("Run report");
    private final CardLayout cards = new CardLayout();
    private final JPanel output = new JPanel(cards);
    private final IdentityHashMap<JTextArea, JLabel> loadTimes = new IdentityHashMap<>();

    private final JSpinner date = new JSpinner(new SpinnerDateModel());
    private final JLabel dailyTitle = new JLabel("Daily sales — choose a date and click Run report");
    private final JTextArea dailyMessage = reportText("Reporting timezone: America/Chicago.");
    private final JTextArea peak = reportText("Highest-revenue hour: —");
    private final JLabel[] totals = {new JLabel("—"), new JLabel("—"), new JLabel("—")};
    private final DefaultTableModel hours = UiSupport.tableModel(
            new Object[] {"Hour (America/Chicago)", "Revenue (USD)", "Completed orders"});
    private final BarChart chart = new BarChart("Hourly revenue", "Hour (America/Chicago)",
            "Revenue (USD)", currency);

    private final JSpinner week = new JSpinner(new SpinnerDateModel());
    private final JCheckBox compareWeek = new JCheckBox("Compare with previous week");
    private final JLabel weeklyTitle = new JLabel("Weekly sales — choose a week and click Run report");
    private final JTextArea weeklyMessage = reportText("Monday–Sunday, America/Chicago.");
    private final JTextArea weeklyInsight = reportText("Highest-revenue day: —");
    private final JLabel[] weeklyTotals = {new JLabel("—"), new JLabel("—"), new JLabel("—")};
    private final DefaultTableModel days = UiSupport.tableModel(
            new Object[] {"Date", "Day", "Orders", "Revenue (USD)", "Average Order Value (USD)"});
    private final BarChart weeklyChart = new BarChart("Daily revenue", "Day (America/Chicago)",
            "Revenue (USD)", currency);

    private final JSpinner itemFrom = new JSpinner(new SpinnerDateModel());
    private final JSpinner itemTo = new JSpinner(new SpinnerDateModel());
    private final JSpinner itemLimit = new JSpinner(new SpinnerNumberModel(10, 1, 100, 1));
    private final JComboBox<String> itemRanking = new JComboBox<>(new String[] {"Quantity sold", "Revenue"});
    private final JLabel itemTitle = new JLabel("Top selling menu items — choose a period and click Run report");
    private final JTextArea itemMessage = reportText("Share of units uses all sold items in the selected period.");
    private final JTextArea itemInsight = reportText("Names reflect current menu records; historical names are not stored. "
            + "The schema prevents deleting menu items referenced by sales.");
    private final JLabel[] itemTotals = {new JLabel("—"), new JLabel("—")};
    private final DefaultTableModel items = UiSupport.tableModel(
            new Object[] {"Rank", "Item Name", "Units Sold", "Revenue (USD)", "Share of Units"});
    private final BarChart itemChart = new BarChart("Top items by quantity sold", "Units sold", "Menu item",
            NumberFormat.getIntegerInstance(Locale.US));

    private final JSpinner shiftFrom = new JSpinner(new SpinnerDateModel());
    private final JSpinner shiftTo = new JSpinner(new SpinnerDateModel());
    private final JSpinner[] shiftStarts = {
            new JSpinner(new SpinnerNumberModel(11, 0, 23, 1)),
            new JSpinner(new SpinnerNumberModel(16, 0, 23, 1)),
            new JSpinner(new SpinnerNumberModel(21, 0, 23, 1))};
    private final JLabel shiftTitle = new JLabel("Sales by shift — time-of-day groups");
    private final JTextArea shiftMessage = reportText("No employee shifts or employee-to-order links are stored.");
    private final JTextArea shiftInsight = reportText("Highest-revenue group: —");
    private final JLabel[] shiftTotals = {new JLabel("—"), new JLabel("—")};
    private final DefaultTableModel shifts = UiSupport.tableModel(
            new Object[] {"Time-of-day group", "Time Range", "Orders", "Revenue (USD)", "Average Order Value (USD)"});
    private final BarChart shiftChart = new BarChart("Revenue by time-of-day group", "Time-of-day group",
            "Revenue (USD)", currency);

    private final NumberFormat usageQuantityFormat = NumberFormat.getNumberInstance(Locale.US);
    private final JComboBox<String> usageUnits = new JComboBox<>(new String[] {"All units"});
    private List<Object[]> usageRows = new ArrayList<>();
    private LocalDate[] usageRange;
    private final ReportView productUsage = new ReportView("Product Usage Chart",
            new String[] {"Inventory Item", "Quantity Used", "Unit"},
            new String[] {"Inventory items used"},
            new BarChart("Inventory usage by item", "Quantity used", "Inventory item", usageQuantityFormat));

    private final ReportView xReport = new ReportView("X-report",
            new String[] {"Hour (America/Chicago)", "Completed Orders", "Revenue (USD)"},
            new String[] {"Total Revenue Today", "Total Orders Today"},
            new BarChart("Hourly revenue today", "Hour (America/Chicago)", "Revenue (USD)", currency));

    private final ReportView restock = new ReportView("Restock Report",
            new String[] {"Status", "Item Name", "Current Quantity", "Unit", "Minimum Threshold", "Shortage"},
            new String[] {"Items needing restock", "Out of stock", "Low stock"}, null);

    private final ReportView weekdayAov = new ReportView("Average order value by weekday",
            new String[] {"Weekday", "Orders", "Revenue (USD)", "Average Order Value (USD)"},
            new String[] {"Overall AOV", "Highest-AOV weekday"},
            new BarChart("Average order value by weekday", "Weekday", "Average order value (USD)", currency));

    private final ReportView orderSizes = new ReportView("Order size distribution",
            new String[] {"Size Category", "Order Count", "Percentage of Orders"},
            new String[] {"Average items per order", "Median items per order", "Most common order size"},
            new BarChart("Order size distribution", "Items per order", "Order count", NumberFormat.getIntegerInstance(Locale.US)));

    ReportsPanel(Connection connection) {
        super(new BorderLayout(8, 8));
        setBorder(javax.swing.BorderFactory.createEmptyBorder(10, 10, 10, 10));

        run.addActionListener(event -> runReport());
        add(UiSupport.buttonBar(new JLabel("Report:"), choices, run), BorderLayout.NORTH);

        output.add(productUsage, "usage");
        usageUnits.addActionListener(event -> {
            if (usageUnits.isEnabled() && usageRange != null) { showUsageSelection(); }
        });
        output.add(xReport, "xreport");
        output.add(dailySales(), "daily");
        output.add(weeklySales(), "weekly");
        output.add(topItems(), "items");
        output.add(restock, "restock");
        output.add(weekdayAov, "aov");
        output.add(salesByShift(), "shifts");
        output.add(orderSizes, "sizes");

        String[] reportCards = {"usage", "xreport", "daily", "weekly", "items", "restock", "aov", "shifts", "sizes"};
        choices.addActionListener(event -> cards.show(output, reportCards[choices.getSelectedIndex()]));
        cards.show(output, reportCards[0]);
        add(output, BorderLayout.CENTER);
        usageQuantityFormat.setMaximumFractionDigits(4);
    }

    private void runReport() {
        int index = choices.getSelectedIndex();
        switch (index) {
            case 0:
                runProductUsage();
                break;
            case 1:
                runXReport();
                break;
            case 2:
                runDailySales();
                break;
            case 3:
                runWeeklySales();
                break;
            case 4:
                runTopItems();
                break;
            case 5:
                runRestock();
                break;
            case 6:
                runWeekdayAov();
                break;
            case 7:
                runSalesByShift();
                break;
            case 8:
                runOrderSizes();
                break;
        }
    }

    private JPanel dailySales() {
        configureDate(date, LocalDate.now(STORE_ZONE), 1);
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.add(UiSupport.buttonBar(new JLabel("Date (yyyy-MM-dd):"), date), BorderLayout.NORTH);
        panel.add(salesDashboard(dailyTitle, dailyMessage, peak, totals,
                new String[] {"Total revenue", "Completed orders", "Average order value"},
                chart, hours, "Hourly sales details"), BorderLayout.CENTER);
        return panel;
    }

    private JPanel weeklySales() {
        configureDate(week, LocalDate.now(STORE_ZONE).with(DayOfWeek.MONDAY), 7);
        week.setToolTipText("Arrows move one week. Any entered date selects its Monday–Sunday week.");
        weeklyChart.showLineSeries();
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.add(UiSupport.buttonBar(new JLabel("Week of (yyyy-MM-dd):"), week, compareWeek), BorderLayout.NORTH);
        panel.add(salesDashboard(weeklyTitle, weeklyMessage, weeklyInsight, weeklyTotals,
                new String[] {"Weekly revenue", "Completed orders", "Average daily revenue"},
                weeklyChart, days, "Daily sales details"), BorderLayout.CENTER);
        return panel;
    }

    private void configureDate(JSpinner picker, LocalDate initial, int stepDays) {
        Date value = Date.from(initial.atStartOfDay(STORE_ZONE).toInstant());
        picker.setModel(new SpinnerDateModel(value, null, null, Calendar.DAY_OF_MONTH) {
            private Date step(int direction) {
                LocalDate current = getDate().toInstant().atZone(STORE_ZONE).toLocalDate();
                return Date.from(current.plusDays(direction * stepDays).atStartOfDay(STORE_ZONE).toInstant());
            }
            @Override public Object getNextValue() { return step(1); }
            @Override public Object getPreviousValue() { return step(-1); }
        });
        JSpinner.DateEditor editor = new JSpinner.DateEditor(picker, "yyyy-MM-dd");
        editor.getFormat().setTimeZone(TimeZone.getTimeZone(STORE_ZONE));
        editor.getFormat().setLenient(false);
        editor.getTextField().setValue(value);
        picker.setEditor(editor);
    }

    private JScrollPane salesDashboard(JLabel title, JTextArea message, JTextArea insight,
            JLabel[] statistics, String[] titles, BarChart plot, DefaultTableModel model, String detailTitle) {
        return salesDashboard(title, message, insight, statistics, titles, plot, model, detailTitle,
                "Order basis: submitted orders. Payment/cancellation status is not stored. "
                        + "Timestamps are interpreted as America/Chicago store-local time.");
    }

    private JScrollPane salesDashboard(JLabel title, JTextArea message, JTextArea insight,
            JLabel[] statistics, String[] titles, BarChart plot, DefaultTableModel model, String detailTitle, String footer) {
        JPanel summary = new JPanel(new GridLayout(1, statistics.length, 8, 0));
        for (int i = 0; i < statistics.length; i++) {
            JPanel card = new JPanel(new BorderLayout());
            card.setBorder(BorderFactory.createTitledBorder(titles[i]));
            statistics[i].setFont(statistics[i].getFont().deriveFont(Font.BOLD, 20f));
            card.add(statistics[i]);
            summary.add(card);
        }
        JPanel heading = new JPanel(new BorderLayout(0, 6));
        JPanel description = new JPanel(new BorderLayout());
        title.setFont(title.getFont().deriveFont(Font.BOLD, 17f));
        description.add(title, BorderLayout.NORTH);
        description.add(message, BorderLayout.CENTER);
        JLabel loaded = new JLabel("Last successful load: —");
        loadTimes.put(message, loaded);
        description.add(loaded, BorderLayout.SOUTH);
        heading.add(description, BorderLayout.NORTH);
        heading.add(summary, BorderLayout.CENTER);
        heading.add(insight, BorderLayout.SOUTH);

        JTable table = UiSupport.table(model);
        table.setAutoCreateRowSorter(false);
        JPanel details = new JPanel(new BorderLayout());
        details.setBorder(BorderFactory.createTitledBorder(detailTitle));
        details.add(table.getTableHeader(), BorderLayout.NORTH);
        details.add(table, BorderLayout.CENTER);
        details.add(reportText(footer), BorderLayout.SOUTH);
        ReportBody body = new ReportBody();
        body.add(heading, BorderLayout.NORTH);
        if (plot != null) { body.add(plot, BorderLayout.CENTER); }
        body.add(details, BorderLayout.SOUTH);
        JScrollPane scroll = new JScrollPane(body);
        scroll.getVerticalScrollBar().setUnitIncrement(18);
        return scroll;
    }

    private void runProductUsage() {
        final LocalDate[] range;
        try {
            range = period(productUsage.from, productUsage.to);
        } catch (ParseException ex) {
            productUsage.message.setText("Enter valid dates. " + ex.getMessage());
            return;
        }
        usageRange = null;
        usageRows = new ArrayList<>();
        productUsage.table.setRowCount(0);
        for (JLabel statistic : productUsage.statistics) { statistic.setText("—"); statistic.setToolTipText(null); }
        productUsage.insight.setText("");
        if (productUsage.plot != null) { productUsage.plot.setData(new String[0], new double[0]); }
        runFilteredReport("Product Usage Chart", productUsage.message,
                new Component[] {productUsage.from, productUsage.to, usageUnits},
                () -> queryReport(REPORT_SQL[0], range[0].atStartOfDay(), range[1].atStartOfDay()),
                rows -> {
                    String selected = (String) usageUnits.getSelectedItem();
                    usageRows = rows;
                    usageRange = range;
                    usageUnits.removeAllItems();
                    usageUnits.addItem("All units");
                    rows.stream().map(row -> String.valueOf(row[3])).distinct().sorted().forEach(usageUnits::addItem);
                    usageUnits.setSelectedItem(selected);
                    showUsageSelection();
                });
    }

    private void showUsageSelection() {
        List<Object[]> selected = new ArrayList<>();
        for (Object[] row : usageRows) {
            if (usageUnits.getSelectedIndex() == 0 || row[3].equals(usageUnits.getSelectedItem())) { selected.add(row); }
        }
        showProductUsage(usageRange, selected);
    }

    private void showProductUsage(LocalDate[] range, List<Object[]> rows) {
        productUsage.table.setRowCount(0);
        String[] labels = new String[rows.size()];
        double[] values = new double[rows.size()];
        for (int index = 0; index < rows.size(); index++) {
            Object[] row = rows.get(index);
            BigDecimal quantity = (BigDecimal) row[2];
            String unit = String.valueOf(row[3]);
            productUsage.table.addRow(new Object[] {row[1], quantity.stripTrailingZeros().toPlainString(), unit});
            labels[index] = String.valueOf(row[1]) + " (" + unit + ")";
            values[index] = quantity.doubleValue();
        }
        productUsage.statistics[0].setText(Integer.toString(rows.size()));
        productUsage.plot.horizontalMetric("Inventory usage by item", "Quantity used", usageQuantityFormat);
        productUsage.plot.setData(labels, values);
        productUsage.message.setText(reportPeriod(range) + (rows.isEmpty()
                ? "No recipe-based inventory usage found for this period. "
                : "Usage is sold quantity multiplied by each item's current recipe quantity. ")
                + "Items without a configured recipe are omitted.");
        productUsage.insight.setText("Quantities are shown in each inventory item's unit. "
                + "Select one unit to compare compatible quantities. "
                + "Historical periods use current recipe definitions because recipe versions are not stored.");
        productUsage.message.append(" Unit filter: " + usageUnits.getSelectedItem() + ".");
    }

    private void runXReport() {
        LocalDate today = LocalDate.now(STORE_ZONE);
        xReport.table.setRowCount(0);
        for (JLabel statistic : xReport.statistics) { statistic.setText("—"); statistic.setToolTipText(null); }
        xReport.insight.setText("");
        if (xReport.plot != null) {
            xReport.plot.showLineSeries();
            xReport.plot.setData(new String[0], new double[0]);
        }
        runFilteredReport("X-report", xReport.message,
                new Component[0],
                () -> queryReport(REPORT_SQL[1], today.atStartOfDay(), today.plusDays(1).atStartOfDay()),
                rows -> showXReport(today, rows));
    }

    private void showXReport(LocalDate today, List<Object[]> rows) {
        xReport.table.setRowCount(0);
        BigDecimal totalRevenue = BigDecimal.ZERO;
        long totalOrders = 0;

        String[] labels = new String[24];
        double[] values = new double[24];
        long[] hourlyOrders = new long[24];
        BigDecimal[] hourlyRevenue = new BigDecimal[24];

        for (int i = 0; i < 24; i++) {
            labels[i] = String.format(Locale.US, "%02d", i);
            values[i] = 0.0;
            hourlyRevenue[i] = BigDecimal.ZERO;
        }

        for (Object[] row : rows) {
            int hour = ((Number) row[0]).intValue();
            long orders = ((Number) row[1]).longValue();
            BigDecimal revenue = (BigDecimal) row[2];

            totalOrders += orders;
            totalRevenue = totalRevenue.add(revenue);

            values[hour] = revenue.doubleValue();
            hourlyOrders[hour] = orders;
            hourlyRevenue[hour] = revenue;
        }
        for (int hour = 0; hour < 24; hour++) {
            xReport.table.addRow(new Object[] {hourLabel(hour), hourlyOrders[hour], currency.format(hourlyRevenue[hour])});
        }

        xReport.statistics[0].setText(currency.format(totalRevenue));
        xReport.statistics[1].setText(NumberFormat.getIntegerInstance(Locale.US).format(totalOrders));
        xReport.plot.setData(labels, values);
        xReport.message.setText("X-report: Sales activities per hour for " + today + " (America/Chicago). "
                + (totalOrders == 0 ? "No orders recorded. " : "") + "All 24 hours are shown, including hours without sales.");
        xReport.insight.setText("Tracks real-time sales revenue and transaction count across operating hours for the current business day.");
    }

    private void runDailySales() {
        final LocalDate selected;
        try {
            date.commitEdit();
            selected = ((Date) date.getValue()).toInstant().atZone(STORE_ZONE).toLocalDate();
        } catch (ParseException ex) {
            dailyMessage.setText("Enter a valid date as yyyy-MM-dd.");
            return;
        }
        dailyTitle.setText("Daily sales — " + selected + " (America/Chicago)");
        dailyMessage.setText("Loading orders…");
        for (JLabel total : totals) { total.setText("—"); }
        peak.setText("Highest-revenue hour: —");
        hours.setRowCount(0);
        chart.setData(new String[0], new double[0]);
        run.setEnabled(false);
        date.setEnabled(false);
        choices.setEnabled(false);
        new SwingWorker<Object[][], Void>() {
            @Override
            protected Object[][] doInBackground() throws Exception {
                Object[][] rows = new Object[24][3];
                for (int h = 0; h < 24; h++) { rows[h] = new Object[] {h, BigDecimal.ZERO, 0L}; }
                try (Connection read = DatabaseConnection.connect()) {
                    read.setReadOnly(true);
                    try (PreparedStatement statement = read.prepareStatement(REPORT_SQL[2])) {
                        statement.setQueryTimeout(30);
                        statement.setObject(1, selected.atStartOfDay());
                        statement.setObject(2, selected.plusDays(1).atStartOfDay());
                        try (ResultSet result = statement.executeQuery()) {
                            while (result.next()) {
                                int hour = result.getInt("hour");
                                rows[hour][1] = result.getBigDecimal("revenue");
                                rows[hour][2] = result.getLong("orders");
                            }
                        }
                    }
                }
                return rows;
            }

            @Override
            protected void done() {
                try {
                    showDailySales(selected, get());
                    markLoaded(dailyMessage);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    dailyMessage.setText("Loading interrupted. Click Run report to retry.");
                } catch (ExecutionException ex) {
                    dailyMessage.setText("Could not load daily sales. Click Run report to retry.");
                    UiSupport.showError(ReportsPanel.this, "Daily sales",
                            ex.getCause() instanceof Exception ? (Exception) ex.getCause() : ex);
                } finally {
                    run.setEnabled(true);
                    date.setEnabled(true);
                    choices.setEnabled(true);
                }
            }
        }.execute();
    }

    private void showDailySales(LocalDate selected, Object[][] rows) {
        BigDecimal revenue = BigDecimal.ZERO, highest = BigDecimal.ZERO;
        long count = 0;
        String[] labels = new String[24];
        double[] values = new double[24];
        for (int h = 0; h < 24; h++) {
            BigDecimal sales = (BigDecimal) rows[h][1];
            revenue = revenue.add(sales);
            highest = highest.max(sales);
            count += (Long) rows[h][2];
            labels[h] = String.format(Locale.US, "%02d", h);
            values[h] = sales.doubleValue();
            hours.addRow(new Object[] {hourLabel(h), currency.format(sales), rows[h][2]});
        }
        totals[0].setText(currency.format(revenue));
        totals[1].setText(NumberFormat.getIntegerInstance(Locale.US).format(count));
        totals[2].setText(count == 0 ? "—" : currency.format(
                revenue.divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_UP)));
        chart.setData(count == 0 ? new String[0] : labels, count == 0 ? new double[0] : values);
        dailyMessage.setText(count == 0 ? "No transactions on " + selected + ". Choose another date."
                : "Period: " + selected + " 00:00 to " + selected.plusDays(1)
                        + " 00:00 (exclusive), America/Chicago.");
        StringJoiner peaks = new StringJoiner(", ");
        for (int h = 0; h < 24; h++) {
            if ((Long) rows[h][2] > 0 && highest.compareTo((BigDecimal) rows[h][1]) == 0) {
                peaks.add(hourLabel(h));
            }
        }
        peak.setText(count == 0 ? "Highest-revenue hour: unavailable."
                : "Highest-revenue hour(s): " + peaks + " — " + currency.format(highest) + " per hour");
    }

    private void runWeeklySales() {
        final LocalDate monday;
        try {
            week.commitEdit();
            monday = ((Date) week.getValue()).toInstant().atZone(STORE_ZONE).toLocalDate().with(DayOfWeek.MONDAY);
            week.setValue(Date.from(monday.atStartOfDay(STORE_ZONE).toInstant()));
        } catch (ParseException ex) {
            weeklyMessage.setText("Enter a valid date as yyyy-MM-dd.");
            return;
        }
        LocalDateTime now = LocalDateTime.now(STORE_ZONE).withNano(0);
        boolean current = monday.equals(now.toLocalDate().with(DayOfWeek.MONDAY));
        LocalDateTime end = current ? now : monday.plusWeeks(1).atStartOfDay();
        int elapsedDays = current ? now.getDayOfWeek().getValue() : 7;
        boolean compare = compareWeek.isSelected();
        weeklyTitle.setText("Weekly sales — " + monday + " through " + monday.plusDays(6));
        weeklyMessage.setText("Loading orders…");
        weeklyInsight.setText("Highest-revenue day: —");
        for (JLabel total : weeklyTotals) { total.setText("—"); }
        days.setRowCount(0);
        weeklyChart.setData(new String[0], new double[0]);
        run.setEnabled(false);
        choices.setEnabled(false);
        week.setEnabled(false);
        compareWeek.setEnabled(false);
        new SwingWorker<Object[][], Void>() {
            @Override
            protected Object[][] doInBackground() throws Exception {
                Object[][] rows = new Object[14][2];
                for (int i = 0; i < rows.length; i++) { rows[i] = new Object[] {BigDecimal.ZERO, 0L}; }
                try (Connection read = DatabaseConnection.connect()) {
                    read.setReadOnly(true);
                    try (PreparedStatement statement = read.prepareStatement(REPORT_SQL[3])) {
                        statement.setQueryTimeout(30);
                        statement.setObject(1, monday.atStartOfDay());
                        statement.setObject(2, end);
                        statement.setBoolean(3, compare);
                        statement.setObject(4, monday.minusWeeks(1).atStartOfDay());
                        statement.setObject(5, end.minusWeeks(1));
                        try (ResultSet result = statement.executeQuery()) {
                            while (result.next()) {
                                int day = (int) ChronoUnit.DAYS.between(monday.minusWeeks(1),
                                        result.getObject("day", LocalDate.class));
                                rows[day][0] = result.getBigDecimal("revenue");
                                rows[day][1] = result.getLong("orders");
                            }
                        }
                    }
                }
                return rows;
            }

            @Override
            protected void done() {
                try {
                    showWeeklySales(monday, end, elapsedDays, compare, get());
                    markLoaded(weeklyMessage);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    weeklyMessage.setText("Loading interrupted. Click Run report to retry.");
                } catch (ExecutionException ex) {
                    weeklyMessage.setText("Could not load weekly sales. Click Run report to retry.");
                    UiSupport.showError(ReportsPanel.this, "Weekly sales",
                            ex.getCause() instanceof Exception ? (Exception) ex.getCause() : ex);
                } finally {
                    run.setEnabled(true);
                    choices.setEnabled(true);
                    week.setEnabled(true);
                    compareWeek.setEnabled(true);
                }
            }
        }.execute();
    }

    private void showWeeklySales(LocalDate monday, LocalDateTime end, int elapsedDays,
            boolean compare, Object[][] rows) {
        BigDecimal revenue = BigDecimal.ZERO, previous = BigDecimal.ZERO, highest = BigDecimal.ZERO;
        long count = 0;
        String[] labels = new String[7];
        double[] values = new double[7];
        for (int i = 0; i < 7; i++) {
            LocalDate day = monday.plusDays(i);
            BigDecimal sales = (BigDecimal) rows[i + 7][0];
            long orders = (Long) rows[i + 7][1];
            revenue = revenue.add(sales);
            previous = previous.add((BigDecimal) rows[i][0]);
            highest = highest.max(sales);
            count += orders;
            labels[i] = day.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.US);
            values[i] = sales.doubleValue();
            days.addRow(new Object[] {day, day.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.US),
                    orders, currency.format(sales), orders == 0 ? "—" : currency.format(
                            sales.divide(BigDecimal.valueOf(orders), 2, RoundingMode.HALF_UP))});
        }
        weeklyTotals[0].setText(currency.format(revenue));
        weeklyTotals[1].setText(NumberFormat.getIntegerInstance(Locale.US).format(count));
        weeklyTotals[2].setText(currency.format(revenue.divide(
                BigDecimal.valueOf(elapsedDays), 2, RoundingMode.HALF_UP)));
        weeklyChart.setData(labels, values);
        boolean partial = end.isBefore(monday.plusWeeks(1).atStartOfDay());
        weeklyMessage.setText((count == 0 ? "No transactions for this period. " : "")
                + "America/Chicago: " + monday + " 00:00 to " + end.withNano(0) + " (exclusive). "
                + (partial ? "Week to date; today is partial. Average uses " + elapsedDays
                        + " elapsed calendar day(s); remaining days show zero."
                        : "Average uses all seven calendar days, including zero-sales days."));
        StringJoiner peaks = new StringJoiner(", ");
        for (int i = 0; i < 7; i++) {
            if ((Long) rows[i + 7][1] > 0 && highest.compareTo((BigDecimal) rows[i + 7][0]) == 0) {
                peaks.add(labels[i]);
            }
        }
        String insight = count == 0 ? "Highest-revenue day: unavailable."
                : "Highest-revenue day(s): " + peaks + " — " + currency.format(highest) + " each.";
        if (compare) {
            BigDecimal change = previous.signum() == 0 ? null : revenue.subtract(previous)
                    .multiply(BigDecimal.valueOf(100)).divide(previous, 2, RoundingMode.HALF_UP);
            insight += "\nPrevious " + (partial ? "matching week-to-date period" : "week")
                    + " (" + monday.minusWeeks(1) + " 00:00 to " + end.minusWeeks(1).withNano(0)
                    + ", exclusive): " + currency.format(previous) + ". "
                    + (change == null ? "Percentage change unavailable: previous revenue is $0.00."
                            : "Revenue change: " + (change.signum() > 0 ? "+" : "") + change.toPlainString() + "%.");
        }
        weeklyInsight.setText(insight);
    }

    private JPanel topItems() {
        configureDate(itemFrom, LocalDate.now(STORE_ZONE).with(DayOfWeek.MONDAY), 1);
        configureDate(itemTo, LocalDate.now(STORE_ZONE), 1);
        itemChart.horizontalMetric("Top items by quantity sold", "Units sold", NumberFormat.getIntegerInstance(Locale.US));
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        JPanel filters = new JPanel(new GridLayout(0, 1));
        filters.add(UiSupport.buttonBar(new JLabel("From:"), itemFrom, new JLabel("Through:"), itemTo));
        filters.add(UiSupport.buttonBar(new JLabel("Rank by:"), itemRanking, new JLabel("Top:"), itemLimit));
        panel.add(filters, BorderLayout.NORTH);
        panel.add(salesDashboard(itemTitle, itemMessage, itemInsight, itemTotals,
                new String[] {"Total units sold (all items)", "Best-selling item by quantity"},
                itemChart, items, "Menu item ranking"), BorderLayout.CENTER);
        return panel;
    }

    private void runTopItems() {
        final LocalDate[] range;
        final int limit;
        try {
            range = period(itemFrom, itemTo);
            itemLimit.commitEdit();
            limit = ((Number) itemLimit.getValue()).intValue();
        } catch (ParseException ex) {
            itemMessage.setText("Enter valid dates and a top-item count from 1 to 100. " + ex.getMessage());
            return;
        }
        boolean quantity = itemRanking.getSelectedIndex() == 0;
        String metric = quantity ? "quantity sold" : "revenue";
        itemTitle.setText("Top " + limit + " menu items by " + metric);
        itemChart.horizontalMetric("Top items by " + metric, quantity ? "Units sold" : "Revenue (USD)",
                quantity ? NumberFormat.getIntegerInstance(Locale.US) : currency);
        items.setRowCount(0);
        for (JLabel total : itemTotals) { total.setText("—"); }
        itemTotals[1].setToolTipText(null);
        itemChart.setData(new String[0], new double[0]);
        runFilteredReport("Sales Report", itemMessage,
                new Component[] {itemFrom, itemTo, itemLimit, itemRanking},
                () -> queryReport(REPORT_SQL[4], range[0].atStartOfDay(), range[1].atStartOfDay(), quantity, limit),
                rows -> showTopItems(range, quantity, rows));
    }

    private void showTopItems(LocalDate[] range, boolean quantity, List<Object[]> rows) {
        long total = rows.isEmpty() ? 0 : ((Number) rows.get(0)[3]).longValue();
        itemTotals[0].setText(NumberFormat.getIntegerInstance(Locale.US).format(total));
        String best = rows.isEmpty() ? "—" : (String) rows.get(0)[4];
        itemTotals[1].setText(best);
        itemTotals[1].setToolTipText(rows.isEmpty() ? null : best + " — " + rows.get(0)[5] + " units sold");
        String[] labels = new String[rows.size()];
        double[] values = new double[rows.size()];
        for (int i = 0; i < rows.size(); i++) {
            Object[] row = rows.get(i);
            long units = ((Number) row[1]).longValue();
            BigDecimal revenue = (BigDecimal) row[2];
            BigDecimal share = total == 0 ? BigDecimal.ZERO : BigDecimal.valueOf(units).multiply(BigDecimal.valueOf(100))
                    .divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP);
            items.addRow(new Object[] {i + 1, row[0], units, currency.format(revenue), share.toPlainString() + "%"});
            labels[i] = (i + 1) + ". " + row[0];
            values[i] = quantity ? units : revenue.doubleValue();
        }
        itemChart.setData(labels, values);
        itemMessage.setText("Period: " + range[0] + " through " + range[1].minusDays(1) + ", America/Chicago. "
                + (rows.isEmpty() ? "No menu item sales recorded. " : "Ranked by " + (quantity ? "quantity sold" : "transaction revenue") + ". ")
                + "Unit shares use all " + total + " units sold, including items outside this ranking. "
                + "Revenue uses recorded order-item prices.");
    }

    private void runRestock() {
        restock.table.setRowCount(0);
        for (JLabel statistic : restock.statistics) { statistic.setText("—"); statistic.setToolTipText(null); }
        restock.insight.setText("");
        if (restock.plot != null) { restock.plot.setData(new String[0], new double[0]); }
        runFilteredReport("Restock Report", restock.message,
                new Component[0],
                () -> queryReport(REPORT_SQL[5]),
                this::showRestock);
    }

    private void showRestock(List<Object[]> rows) {
        long out = 0;
        for (Object[] row : rows) {
            if ("OUT OF STOCK".equals(row[0])) { out++; }
            restock.table.addRow(row);
        }
        restock.statistics[0].setText(Integer.toString(rows.size()));
        restock.statistics[1].setText(Long.toString(out));
        restock.statistics[2].setText(Long.toString(rows.size() - out));
        restock.message.setText(rows.isEmpty() ? "No items need restocking: no recorded inventory quantities are at or below their thresholds."
                : "Current inventory snapshot: " + rows.size() + " items need restocking. OUT OF STOCK items are most urgent.");
        restock.insight.setText("Out-of-stock items appear first, then the lowest stock-to-threshold ratio. "
                + "Shortage is the quantity needed to reach the stored threshold; an item exactly at threshold has zero shortage.");
    }

    private void runWeekdayAov() {
        final LocalDate[] range;
        try {
            range = period(weekdayAov.from, weekdayAov.to);
        } catch (ParseException ex) {
            weekdayAov.message.setText("Enter valid dates. " + ex.getMessage());
            return;
        }
        weekdayAov.table.setRowCount(0);
        for (JLabel statistic : weekdayAov.statistics) { statistic.setText("—"); statistic.setToolTipText(null); }
        weekdayAov.insight.setText("");
        if (weekdayAov.plot != null) { weekdayAov.plot.setData(new String[0], new double[0]); }
        runFilteredReport("Average order value by weekday", weekdayAov.message,
                new Component[] {weekdayAov.from, weekdayAov.to},
                () -> queryReport(REPORT_SQL[6], range[0].atStartOfDay(), range[1].atStartOfDay()),
                rows -> showWeekdayAov(range, rows));
    }

    private void showWeekdayAov(LocalDate[] range, List<Object[]> rows) {
        BigDecimal[] sales = new BigDecimal[7];
        long[] counts = new long[7];
        for (int i = 0; i < 7; i++) { sales[i] = BigDecimal.ZERO; }
        for (Object[] row : rows) {
            int day = ((Number) row[0]).intValue() - 1;
            counts[day] = ((Number) row[1]).longValue();
            sales[day] = (BigDecimal) row[2];
        }
        BigDecimal revenue = BigDecimal.ZERO;
        long orders = 0;
        int best = -1;
        String[] labels = new String[7];
        double[] values = new double[7];
        for (int i = 0; i < 7; i++) {
            DayOfWeek day = DayOfWeek.of(i + 1);
            labels[i] = day.getDisplayName(TextStyle.SHORT, Locale.US);
            revenue = revenue.add(sales[i]);
            orders += counts[i];
            values[i] = counts[i] == 0 ? Double.NaN : sales[i].doubleValue() / counts[i];
            weekdayAov.table.addRow(new Object[] {day.getDisplayName(TextStyle.FULL, Locale.US), counts[i],
                    currency.format(sales[i]), counts[i] == 0 ? "N/A" : currency.format(
                            sales[i].divide(BigDecimal.valueOf(counts[i]), 2, RoundingMode.HALF_UP))});
            if (counts[i] > 0 && (best < 0 || sales[i].multiply(BigDecimal.valueOf(counts[best]))
                    .compareTo(sales[best].multiply(BigDecimal.valueOf(counts[i]))) > 0)) { best = i; }
        }
        StringJoiner highest = new StringJoiner(", ");
        for (int i = 0; i < 7; i++) {
            if (best >= 0 && counts[i] > 0 && sales[i].multiply(BigDecimal.valueOf(counts[best]))
                    .compareTo(sales[best].multiply(BigDecimal.valueOf(counts[i]))) == 0) { highest.add(labels[i]); }
        }
        weekdayAov.statistics[0].setText(orders == 0 ? "N/A" : currency.format(
                revenue.divide(BigDecimal.valueOf(orders), 2, RoundingMode.HALF_UP)));
        weekdayAov.statistics[1].setText(best < 0 ? "N/A" : highest.toString());
        weekdayAov.statistics[1].setToolTipText(best < 0 ? null : highest.toString());
        weekdayAov.plot.setData(labels, values);
        weekdayAov.message.setText(reportPeriod(range) + (orders == 0 ? "No orders recorded. " : orders + " orders. ")
                + "AOV = total order revenue / order count; weekdays without orders show N/A.");
        weekdayAov.insight.setText(best < 0 ? "Highest-AOV weekday: unavailable."
                : "Highest-AOV weekday(s): " + highest + " — " + currency.format(
                        sales[best].divide(BigDecimal.valueOf(counts[best]), 2, RoundingMode.HALF_UP)) + " each.");
    }

    private JPanel salesByShift() {
        configureDate(shiftFrom, LocalDate.now(STORE_ZONE).with(DayOfWeek.MONDAY), 1);
        configureDate(shiftTo, LocalDate.now(STORE_ZONE), 1);
        JPanel filters = new JPanel(new GridLayout(0, 1));
        filters.add(UiSupport.buttonBar(new JLabel("From:"), shiftFrom, new JLabel("Through:"), shiftTo));
        filters.add(UiSupport.buttonBar(new JLabel("Start hours (0–23): Lunch"), shiftStarts[0],
                new JLabel("Dinner"), shiftStarts[1], new JLabel("Other"), shiftStarts[2]));
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.add(filters, BorderLayout.NORTH);
        panel.add(salesDashboard(shiftTitle, shiftMessage, shiftInsight, shiftTotals,
                new String[] {"Total sales", "Highest-revenue time group"}, shiftChart, shifts,
                "Time-of-day sales (not employee shifts)"), BorderLayout.CENTER);
        return panel;
    }

    private void runSalesByShift() {
        final LocalDate[] range;
        int[] starts = new int[3];
        try {
            range = period(shiftFrom, shiftTo);
            for (int i = 0; i < starts.length; i++) {
                shiftStarts[i].commitEdit();
                starts[i] = ((Number) shiftStarts[i].getValue()).intValue();
            }
            if (starts[0] < 0 || starts[2] > 23 || starts[0] >= starts[1] || starts[1] >= starts[2]) {
                throw new ParseException("Start hours must increase: Lunch < Dinner < Other, within 0–23.", 0);
            }
        } catch (ParseException ex) {
            shiftMessage.setText("Check the dates and start hours. " + ex.getMessage());
            return;
        }
        shifts.setRowCount(0);
        for (JLabel total : shiftTotals) { total.setText("—"); }
        shiftTotals[1].setToolTipText(null);
        shiftInsight.setText("Highest-revenue group: —");
        shiftChart.setData(new String[0], new double[0]);
        runFilteredReport("Sales by shift", shiftMessage,
                new Component[] {shiftFrom, shiftTo, shiftStarts[0], shiftStarts[1], shiftStarts[2]},
                () -> queryReport(REPORT_SQL[7], starts[0], starts[1], starts[1], starts[2],
                        range[0].atStartOfDay(), range[1].atStartOfDay()),
                rows -> showSalesByShift(range, starts, rows));
    }

    private void showSalesByShift(LocalDate[] range, int[] starts, List<Object[]> rows) {
        String[] labels = {"Lunch", "Dinner", "Other / overnight"};
        BigDecimal[] revenue = {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};
        long[] counts = new long[3];
        for (Object[] row : rows) {
            int block = ((Number) row[0]).intValue();
            counts[block] = ((Number) row[1]).longValue();
            revenue[block] = (BigDecimal) row[2];
        }
        BigDecimal total = BigDecimal.ZERO, highest = BigDecimal.ZERO;
        long orders = 0;
        double[] values = new double[3];
        for (int i = 0; i < 3; i++) {
            total = total.add(revenue[i]);
            highest = highest.max(revenue[i]);
            orders += counts[i];
            values[i] = revenue[i].doubleValue();
            String timeRange = String.format(Locale.US, "%02d:00–%02d:00", starts[i], starts[(i + 1) % 3])
                    + (i == 2 ? " (wraps midnight)" : "");
            shifts.addRow(new Object[] {labels[i], timeRange, counts[i], currency.format(revenue[i]),
                    counts[i] == 0 ? "—" : currency.format(revenue[i].divide(
                            BigDecimal.valueOf(counts[i]), 2, RoundingMode.HALF_UP))});
        }
        StringJoiner best = new StringJoiner(", ");
        for (int i = 0; i < 3; i++) {
            if (counts[i] > 0 && revenue[i].compareTo(highest) == 0) { best.add(labels[i]); }
        }
        shiftTotals[0].setText(currency.format(total));
        shiftTotals[1].setText(orders == 0 ? "—" : best.toString());
        shiftTotals[1].setToolTipText(orders == 0 ? null : best.toString());
        shiftChart.setData(labels, values);
        shiftMessage.setText("Period: " + range[0] + " through " + range[1].minusDays(1) + ", America/Chicago. "
                + (orders == 0 ? "No transactions recorded. " : orders + " orders, each counted once. ")
                + "These are configurable time-of-day groups, not employee shifts. "
                + "Date filters use each transaction's calendar date, including overnight transactions.");
        shiftInsight.setText(orders == 0 ? "Highest-revenue group: unavailable."
                : "Highest-revenue group(s): " + best + " — " + currency.format(highest) + " each.");
    }

    private void runOrderSizes() {
        final LocalDate[] range;
        try {
            range = period(orderSizes.from, orderSizes.to);
        } catch (ParseException ex) {
            orderSizes.message.setText("Enter valid dates. " + ex.getMessage());
            return;
        }
        orderSizes.table.setRowCount(0);
        for (JLabel statistic : orderSizes.statistics) { statistic.setText("—"); statistic.setToolTipText(null); }
        orderSizes.insight.setText("");
        if (orderSizes.plot != null) { orderSizes.plot.setData(new String[0], new double[0]); }
        runFilteredReport("Order size distribution", orderSizes.message,
                new Component[] {orderSizes.from, orderSizes.to},
                () -> queryReport(REPORT_SQL[8], range[0].atStartOfDay(), range[1].atStartOfDay()),
                rows -> showOrderSizes(range, rows));
    }

    private void showOrderSizes(LocalDate[] range, List<Object[]> rows) {
        long[] counts = new long[6];
        long orders = rows.isEmpty() ? 0 : ((Number) rows.get(0)[2]).longValue();
        for (Object[] row : rows) { counts[((Number) row[0]).intValue()] = ((Number) row[1]).longValue(); }
        int first = counts[0] > 0 ? 0 : 1;
        String[] labels = new String[6 - first];
        double[] values = new double[labels.length];
        for (int size = first; size <= 5; size++) {
            String label = size == 5 ? "5+ items" : size + (size == 1 ? " item" : " items");
            labels[size - first] = label;
            values[size - first] = counts[size];
            String percentage = orders == 0 ? "N/A" : BigDecimal.valueOf(counts[size]).multiply(BigDecimal.valueOf(100))
                    .divide(BigDecimal.valueOf(orders), 2, RoundingMode.HALF_UP).toPlainString() + "%";
            orderSizes.table.addRow(new Object[] {size == 0 ? "0 items (no item lines)" : label, counts[size], percentage});
        }
        orderSizes.statistics[0].setText(orders == 0 ? "N/A" : new BigDecimal(rows.get(0)[3].toString())
                .setScale(2, RoundingMode.HALF_UP).toPlainString());
        orderSizes.statistics[1].setText(orders == 0 ? "N/A" : new BigDecimal(rows.get(0)[4].toString())
                .setScale(2, RoundingMode.HALF_UP).toPlainString());
        long common = orders == 0 ? 0 : ((Number) rows.get(0)[5]).longValue();
        orderSizes.statistics[2].setText(orders == 0 ? "N/A" : common + (common == 1 ? " item" : " items"));
        orderSizes.plot.setData(labels, values);
        orderSizes.message.setText(reportPeriod(range) + (orders == 0 ? "No orders recorded." : orders + " orders, counted once each. ")
                + (counts[0] > 0 ? "Orders without item lines are included separately as 0 items." : ""));
        orderSizes.insight.setText("Size uses summed item quantities. Average, median, and most common size use actual sizes before the 5+ grouping. "
                + "If sizes tie for most common, the smallest is shown. Percentages may differ slightly from 100% due to rounding.");
    }

    private LocalDate[] period(JSpinner from, JSpinner to) throws ParseException {
        from.commitEdit();
        to.commitEdit();
        LocalDate start = ((Date) from.getValue()).toInstant().atZone(STORE_ZONE).toLocalDate();
        LocalDate last = ((Date) to.getValue()).toInstant().atZone(STORE_ZONE).toLocalDate();
        if (last.isBefore(start)) { throw new ParseException("The end date must be on or after the start date.", 0); }
        return new LocalDate[] {start, last.plusDays(1)};
    }

    private static String reportPeriod(LocalDate[] range) {
        return "Period: " + range[0] + " through " + range[1].minusDays(1) + ", America/Chicago. ";
    }

    /** Runs a parameterized report query and returns each result row as a list of values. */
    private List<Object[]> queryReport(String sql, Object... parameters) throws Exception {
        List<Object[]> rows = new ArrayList<>();
        try (Connection read = DatabaseConnection.connect()) {
            read.setReadOnly(true);
            try (PreparedStatement statement = read.prepareStatement(sql)) {
                statement.setQueryTimeout(30);
                for (int i = 0; i < parameters.length; i++) { statement.setObject(i + 1, parameters[i]); }
                try (ResultSet result = statement.executeQuery()) {
                    int columns = result.getMetaData().getColumnCount();
                    while (result.next()) {
                        Object[] row = new Object[columns];
                        for (int i = 0; i < columns; i++) { row[i] = result.getObject(i + 1); }
                        rows.add(row);
                    }
                }
            }
        }
        return rows;
    }

    private void runFilteredReport(String name, JTextArea message, Component[] controls,
            Callable<List<Object[]>> query, Consumer<List<Object[]>> display) {
        message.setText("Loading " + name.toLowerCase(Locale.US) + "…");
        run.setEnabled(false);
        choices.setEnabled(false);
        for (Component control : controls) { control.setEnabled(false); }
        new SwingWorker<List<Object[]>, Void>() {
            protected List<Object[]> doInBackground() throws Exception { return query.call(); }
            protected void done() {
                try {
                    display.accept(get());
                    markLoaded(message);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    message.setText("Loading interrupted. Click Run report to retry.");
                } catch (ExecutionException ex) {
                    message.setText("Could not load " + name.toLowerCase(Locale.US) + ". Click Run report to retry.");
                    UiSupport.showError(ReportsPanel.this, name,
                            ex.getCause() instanceof Exception ? (Exception) ex.getCause() : ex);
                } finally {
                    run.setEnabled(true);
                    choices.setEnabled(true);
                    for (Component control : controls) { control.setEnabled(true); }
                }
            }
        }.execute();
    }

    private void markLoaded(JTextArea message) {
        loadTimes.get(message).setText("Last successful load: " + LocalDateTime.now(STORE_ZONE)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) + " America/Chicago");
    }

    private static String hourLabel(int hour) {
        return String.format(Locale.US, "%02d:00–%02d:00", hour, hour + 1);
    }

    private static JTextArea reportText(String value) {
        JTextArea text = new JTextArea(value);
        text.setEditable(false);
        text.setOpaque(false);
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        text.setFont(new JLabel().getFont());
        return text;
    }

    /** Small shared view for the modular reports; no separate report/chart files needed. */
    private final class ReportView extends JPanel {
        final JLabel title;
        final JTextArea message = reportText("Click Run report to load current database records.");
        final JTextArea insight = reportText("");
        final JLabel[] statistics;
        final DefaultTableModel table;
        final BarChart plot;
        final JSpinner from = new JSpinner(new SpinnerDateModel());
        final JSpinner to = new JSpinner(new SpinnerDateModel());

        ReportView(String name, String[] columns, String[] metrics, BarChart plot) {
            super(new BorderLayout(0, 8));
            title = new JLabel(name);
            table = UiSupport.tableModel(columns);
            this.plot = plot;
            statistics = new JLabel[metrics.length];
            for (int i = 0; i < metrics.length; i++) { statistics[i] = new JLabel("—"); }
            if (plot != null && !name.contains("X-report")) {
                configureDate(from, LocalDate.now(STORE_ZONE).minusDays(29), 1);
                configureDate(to, LocalDate.now(STORE_ZONE), 1);
                JPanel filters = new JPanel(new GridLayout(0, 1));
                filters.add(UiSupport.buttonBar(new JLabel("From:"), from, new JLabel("Through:"), to));
                if (name.equals("Product Usage Chart")) {
                    filters.add(UiSupport.buttonBar(new JLabel("Measurement unit:"), usageUnits));
                }
                add(filters, BorderLayout.NORTH);
            }
            add(salesDashboard(title, message, insight, statistics, metrics, plot, table, name + " details",
                    plot == null ? "Thresholds are the stored reorder levels. Quantities and units are not modified."
                            : "Order basis: submitted orders; payment/cancellation status is not stored. "
                                    + "Dates use America/Chicago store-local timestamps."), BorderLayout.CENTER);
        }
    }

    /** Scroll the whole dashboard vertically while following the available window width. */
    private static final class ReportBody extends JPanel implements Scrollable {
        ReportBody() { super(new BorderLayout(0, 8)); }
        public Dimension getPreferredScrollableViewportSize() { return new Dimension(760, 550); }
        public int getScrollableUnitIncrement(Rectangle r, int axis, int direction) { return 18; }
        public int getScrollableBlockIncrement(Rectangle r, int axis, int direction) { return 150; }
        public boolean getScrollableTracksViewportWidth() { return true; }
        public boolean getScrollableTracksViewportHeight() { return false; }
    }

    /** Reusable bar renderer: callers supply titles, category labels, values and units. */
    static final class BarChart extends JPanel {
        private String title, xAxis;
        private final String yAxis;
        private NumberFormat format;
        private boolean horizontal;
        private boolean lineSeries;
        private String[] labels = new String[0];
        private double[] values = new double[0];

        BarChart(String title, String xAxis, String yAxis, NumberFormat format) {
            this.title = title;
            this.xAxis = xAxis;
            this.yAxis = yAxis;
            this.format = format;
            setBackground(Color.WHITE);
            setPreferredSize(new Dimension(760, 260));
        }

        void setData(String[] labels, double[] values) {
            this.labels = labels;
            this.values = values;
            if (horizontal) { setPreferredSize(new Dimension(760, Math.max(260, labels.length * 30 + 90))); }
            revalidate();
            repaint();
        }

        void horizontalMetric(String title, String axis, NumberFormat format) {
            this.title = title;
            this.xAxis = axis;
            this.format = format;
            horizontal = true;
        }

        void showLineSeries() { lineSeries = true; }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setColor(Color.DARK_GRAY);
                g.drawString(title, 12, 20);
                if (values.length == 0) {
                    g.drawString("No data to display.", 12, 50);
                    return;
                }
                if (horizontal) {
                    paintHorizontal(g);
                    return;
                }
                double maximum = 1;
                for (double value : values) { if (!Double.isNaN(value)) { maximum = Math.max(maximum, value); } }
                int left = g.getFontMetrics().stringWidth(format.format(maximum)) + 20;
                int width = Math.max(1, getWidth() - left - 15);
                int height = Math.max(1, getHeight() - 95), bottom = 45 + height;
                g.drawString(yAxis, 12, 38);
                for (int tick = 0; tick <= 4; tick++) {
                    int y = bottom - height * tick / 4;
                    g.setColor(new Color(225, 225, 225));
                    g.drawLine(left, y, left + width, y);
                    g.setColor(Color.DARK_GRAY);
                    String label = format.format(maximum * tick / 4);
                    g.drawString(label, left - g.getFontMetrics().stringWidth(label) - 8, y + 4);
                }
                double slot = width / (double) values.length;
                int labelWidth = 0;
                for (String label : labels) { labelWidth = Math.max(labelWidth, g.getFontMetrics().stringWidth(label)); }
                int interval = Math.max(1, (int) Math.ceil((labelWidth + 8) / slot));
                for (int i = 0; i < values.length; i++) {
                    int x = left + (int) (i * slot);
                    if (Double.isNaN(values[i])) {
                        g.setColor(Color.DARK_GRAY);
                        g.drawString("N/A", x + 2, bottom - 5);
                    } else {
                        int barHeight = (int) Math.round(values[i] / maximum * height);
                        g.setColor(new Color(57, 112, 151));
                        g.fillRect(x + 2, bottom - barHeight, Math.max(1, (int) slot - 4), barHeight);
                    }
                    if (i % interval == 0) {
                        g.setColor(Color.DARK_GRAY);
                        g.drawString(labels[i], x, bottom + 18);
                    }
                }
                if (lineSeries) {
                    g.setColor(new Color(204, 92, 42));
                    g.setStroke(new BasicStroke(2.5f));
                    int previousX = 0, previousY = 0;
                    boolean hasPrevious = false;
                    for (int i = 0; i < values.length; i++) {
                        if (Double.isNaN(values[i])) {
                            hasPrevious = false;
                            continue;
                        }
                        int x = left + (int) Math.round((i + 0.5) * slot);
                        int y = bottom - (int) Math.round(values[i] / maximum * height);
                        if (hasPrevious) { g.drawLine(previousX, previousY, x, y); }
                        g.fillOval(x - 4, y - 4, 8, 8);
                        previousX = x;
                        previousY = y;
                        hasPrevious = true;
                    }
                    g.setStroke(new BasicStroke(1f));
                    int legendX = getWidth() - 138;
                    g.drawLine(legendX, 18, legendX + 22, 18);
                    g.fillOval(legendX + 8, 14, 8, 8);
                    g.drawString("Revenue trend", legendX + 28, 22);
                }
                g.setColor(Color.DARK_GRAY);
                g.drawString(xAxis, (getWidth() - g.getFontMetrics().stringWidth(xAxis)) / 2, getHeight() - 10);
            } finally { g.dispose(); }
        }

        private void paintHorizontal(Graphics2D g) {
            double maximum = 1;
            int labelWidth = 0;
            for (int i = 0; i < values.length; i++) {
                maximum = Math.max(maximum, values[i]);
                labelWidth = Math.max(labelWidth, g.getFontMetrics().stringWidth(labels[i]));
            }
            int left = Math.min(labelWidth + 20, getWidth() / 2);
            int width = Math.max(1, getWidth() - left - 85);
            int bottom = getHeight() - 50;
            double slot = (bottom - 45.0) / values.length;
            int ticks = Math.max(1, (int) Math.min(4, maximum));
            for (int tick = 0; tick <= ticks; tick++) {
                int x = left + width * tick / ticks;
                g.setColor(new Color(225, 225, 225));
                g.drawLine(x, 40, x, bottom);
                g.setColor(Color.DARK_GRAY);
                String value = format.format(maximum * tick / ticks);
                g.drawString(value, x - g.getFontMetrics().stringWidth(value) / 2, bottom + 18);
            }
            for (int i = 0; i < values.length; i++) {
                int y = 45 + (int) (i * slot);
                int length = (int) Math.round(values[i] / maximum * width);
                g.setColor(new Color(57, 112, 151));
                g.fillRect(left, y, length, Math.max(1, (int) slot - 8));
                g.setColor(Color.DARK_GRAY);
                String label = labels[i];
                while (label.length() > 1 && g.getFontMetrics().stringWidth(label + "…") > left - 15) {
                    label = label.substring(0, label.length() - 1);
                }
                if (!label.equals(labels[i])) { label += "…"; }
                g.drawString(label, 8, y + 16);
                g.drawString(format.format(values[i]), left + length + 5, y + 16);
            }
            g.drawString(xAxis, (getWidth() - g.getFontMetrics().stringWidth(xAxis)) / 2, getHeight() - 10);
        }
    }
}
