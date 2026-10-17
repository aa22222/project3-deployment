import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.sql.Connection;
import java.util.logging.Logger;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;

/** Main application window and navigation for the team 41 POS GUI. */
public class GUI extends JFrame {
    private static final Logger LOG = Logger.getLogger(GUI.class.getName());

    private final Connection connection;

    private GUI(Connection connection) {
        super("Boba Shop POS — Cashier / Manager");
        this.connection = connection;
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1040, 700);
        setLocationRelativeTo(null);
        showRoleChooser();
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                Connection connection = DatabaseConnection.connect();
                new GUI(connection).setVisible(true);
            } catch (Exception ex) {
                LOG.severe("Could not connect to the PostgreSQL database: " + UiSupport.messageFor(ex));
                JOptionPane.showMessageDialog(null,
                        "Could not connect to the store database. Check the database connection and credentials.\n\n"
                                + UiSupport.messageFor(ex),
                        "Database connection failed", JOptionPane.ERROR_MESSAGE);
            }
        });
    }

    private void showRoleChooser() {
        JPanel panel = new JPanel(new BorderLayout(0, 18));
        panel.setBorder(BorderFactory.createEmptyBorder(24, 36, 28, 36));

        JLabel heading = new JLabel("Choose the screen for this session:");
        heading.setFont(heading.getFont().deriveFont(java.awt.Font.BOLD, 26f));
        panel.add(heading, BorderLayout.NORTH);

        JPanel buttons = new JPanel(new GridLayout(2, 1, 12, 12));
        JButton cashier = new JButton("Cashier - take an order");
        JButton manager = new JButton("Manager - store management");
        cashier.setFont(cashier.getFont().deriveFont(java.awt.Font.BOLD, 24f));
        manager.setFont(manager.getFont().deriveFont(java.awt.Font.BOLD, 24f));
        cashier.addActionListener(event -> showCashier());
        manager.addActionListener(event -> showManager());
        buttons.add(cashier);
        buttons.add(manager);
        panel.add(buttons, BorderLayout.CENTER);

        setTitle("Boba Shop POS - Cashier / Manager");
        setContentPane(panel);
        revalidate();
        repaint();
    }

    private void showCashier() {
        setTitle("Boba Shop POS — Cashier");
        setContentPane(new CashierPanel(connection, this::showRoleChooser));
        revalidate();
        repaint();
    }

    private void showManager() {
        setTitle("Boba Shop POS — Manager");
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Menu", new CatalogPanel(connection, CatalogType.MENU));
        tabs.addTab("Inventory", new CatalogPanel(connection, CatalogType.INVENTORY));
        tabs.addTab("Employees", new CatalogPanel(connection, CatalogType.EMPLOYEE));
        tabs.addTab("Reports", new ReportsPanel(connection));
        tabs.addTab("Switch role", rolePanel());
        setContentPane(tabs);
        revalidate();
        repaint();
    }

    private JPanel rolePanel() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 20, 20));
        JButton back = new JButton("Return to role selection");
        back.addActionListener(event -> showRoleChooser());
        panel.add(back);
        return panel;
    }
}
