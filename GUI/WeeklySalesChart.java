import java.awt.Color;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JPanel;

/** Bar chart showing total sales revenue for each week. */
final class WeeklySalesChart extends JPanel {
    private List<String> weekLabels = new ArrayList<>();
    private List<Double> salesValues = new ArrayList<>();

    WeeklySalesChart() {
        setBackground(Color.WHITE);
    }

    void setData(List<String> labels, List<Double> values) {
        weekLabels = new ArrayList<>(labels);
        salesValues = new ArrayList<>(values);
        repaint();
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);

        Graphics2D g = (Graphics2D) graphics.create();

        g.setRenderingHint(
                RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);

        if (salesValues.isEmpty()) {
            g.setColor(Color.DARK_GRAY);
            g.drawString("No weekly sales data available.", 20, 30);
            g.dispose();
            return;
        }

        int width = getWidth();
        int height = getHeight();

        int leftMargin = 75;
        int rightMargin = 25;
        int topMargin = 40;
        int bottomMargin = 95;

        int chartWidth = width - leftMargin - rightMargin;
        int chartHeight = height - topMargin - bottomMargin;

        double maxSales = 0;

        for (double sales : salesValues) {
            maxSales = Math.max(maxSales, sales);
        }

        if (maxSales <= 0) {
            maxSales = 1;
        }

        // Axes
        g.setColor(Color.DARK_GRAY);

        g.drawLine(
                leftMargin,
                topMargin,
                leftMargin,
                topMargin + chartHeight);

        g.drawLine(
                leftMargin,
                topMargin + chartHeight,
                leftMargin + chartWidth,
                topMargin + chartHeight);

        drawYAxis(
                g,
                maxSales,
                leftMargin,
                topMargin,
                chartWidth,
                chartHeight);

        drawBars(
                g,
                maxSales,
                leftMargin,
                topMargin,
                chartWidth,
                chartHeight);

        drawXAxisLabels(
                g,
                leftMargin,
                topMargin + chartHeight,
                chartWidth);

        drawAxisTitles(
                g,
                width,
                height,
                leftMargin,
                topMargin,
                chartHeight);

        g.dispose();
    }

    private void drawBars(
            Graphics2D g,
            double maxSales,
            int leftMargin,
            int topMargin,
            int chartWidth,
            int chartHeight) {

        int numberOfBars = salesValues.size();

        double slotWidth = (double) chartWidth / numberOfBars;

        int barWidth = Math.max(
                2,
                (int) (slotWidth * 0.75));

        for (int index = 0; index < numberOfBars; index++) {
            double sales = salesValues.get(index);

            int barHeight = (int) (
                    sales / maxSales * chartHeight);

            int x = leftMargin
                    + (int) (index * slotWidth)
                    + (int) ((slotWidth - barWidth) / 2);

            int y = topMargin
                    + chartHeight
                    - barHeight;

            g.setColor(new Color(80, 125, 180));

            g.fillRect(
                    x,
                    y,
                    barWidth,
                    barHeight);
        }
    }

    private void drawYAxis(
            Graphics2D g,
            double maxSales,
            int leftMargin,
            int topMargin,
            int chartWidth,
            int chartHeight) {

        int tickCount = 5;

        FontMetrics metrics = g.getFontMetrics();

        for (int tick = 0; tick <= tickCount; tick++) {

            double value = maxSales * tick / tickCount;

            int y = topMargin
                    + chartHeight
                    - (chartHeight * tick / tickCount);

            g.setColor(new Color(225, 225, 225));

            g.drawLine(
                    leftMargin,
                    y,
                    leftMargin + chartWidth,
                    y);

            g.setColor(Color.DARK_GRAY);

            String label = String.format(
                    "$%,.0f",
                    value);

            int labelWidth = metrics.stringWidth(label);

            g.drawString(
                    label,
                    leftMargin - labelWidth - 8,
                    y + 5);
        }
    }

    private void drawXAxisLabels(
            Graphics2D g,
            int leftMargin,
            int axisY,
            int chartWidth) {

        int numberOfBars = weekLabels.size();

        double slotWidth = (double) chartWidth / numberOfBars;

        /*
         * All weeks still receive a bar.
         * Only every fourth week gets a text label so
         * 52 dates do not overlap.
         */
        int labelEvery = 4;

        for (int index = 0; index < numberOfBars; index++) {

            if (index % labelEvery != 0
                    && index != numberOfBars - 1) {
                continue;
            }

            String label = weekLabels.get(index);

            int x = leftMargin
                    + (int) (
                            index * slotWidth
                            + slotWidth / 2);

            Graphics2D rotated =
                    (Graphics2D) g.create();

            rotated.setColor(Color.DARK_GRAY);

            rotated.rotate(
                    -Math.PI / 4,
                    x,
                    axisY + 12);

            rotated.drawString(
                    label,
                    x,
                    axisY + 12);

            rotated.dispose();
        }
    }

    private void drawAxisTitles(
            Graphics2D g,
            int width,
            int height,
            int leftMargin,
            int topMargin,
            int chartHeight) {

        String xTitle = "Week";
        FontMetrics metrics = g.getFontMetrics();

        g.setColor(Color.DARK_GRAY);

        g.drawString(
                xTitle,
                (width - metrics.stringWidth(xTitle)) / 2,
                height - 8);

        Graphics2D rotated =
                (Graphics2D) g.create();

        String yTitle = "Sales ($)";

        rotated.rotate(
                -Math.PI / 2);

        rotated.drawString(
                yTitle,
                -(topMargin
                        + chartHeight / 2
                        + metrics.stringWidth(yTitle) / 2),
                15);

        rotated.dispose();
    }
}