import java.awt.Component;
import java.awt.FlowLayout;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.logging.Logger;
import javax.swing.JPanel;
import javax.swing.JOptionPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.table.DefaultTableModel;

/** Shared Swing and input helpers used by the application panels. */
final class UiSupport {
    private static final Logger LOG = Logger.getLogger(UiSupport.class.getName());

    private UiSupport() { }

    static DefaultTableModel tableModel(Object[] columns) {
        return new DefaultTableModel(columns, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
    }

    static JTable table(DefaultTableModel model) {
        JTable table = new JTable(model);
        table.setFillsViewportHeight(true);
        table.setAutoCreateRowSorter(true);
        return table;
    }

    static JPanel buttonBar(Component... components) {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT));
        for (Component component : components) {
            bar.add(component);
        }
        return bar;
    }

    /** Returns the next identifier for a table by finding its current maximum ID. */
    static int nextId(Connection connection, String tableName, String idColumn) throws SQLException {
        // These identifiers are fixed constants selected by the application, not user input.
        String sql = "SELECT COALESCE(MAX(" + idColumn + "), 0) + 1 FROM " + tableName;
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getInt(1);
        }
    }

    static JTextField field(Object value) {
        return new JTextField(String.valueOf(value), 18);
    }

    static String required(JTextField field) {
        String value = field.getText().trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("Please fill in every required field.");
        }
        return value;
    }

    static BigDecimal decimal(JTextField field) {
        try {
            BigDecimal value = new BigDecimal(required(field));
            if (value.signum() < 0) {
                throw new IllegalArgumentException("Values cannot be negative.");
            }
            return value;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Enter a valid non-negative number.");
        }
    }

    static String escapeHtml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    static void showError(Component parent, String action, Exception ex) {
        String message = messageFor(ex);
        LOG.warning(action + " failed: " + message);
        JOptionPane.showMessageDialog(parent, action + " failed:\n" + message,
                "Could not complete action", JOptionPane.ERROR_MESSAGE);
    }

    static String messageFor(Exception ex) {
        String message = ex.getMessage();
        return message == null || message.trim().isEmpty()
                ? ex.getClass().getSimpleName()
                : message;
    }
}
