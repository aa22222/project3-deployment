import java.awt.BorderLayout;
import java.awt.GridLayout;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;

/** Cashier screen for selecting drinks, reviewing a cart, and submitting orders. */
final class CashierPanel extends JPanel {
    private static final Logger LOG = Logger.getLogger(CashierPanel.class.getName());

    private final Connection connection;
    private final JPanel menuGrid = new JPanel(new GridLayout(0, 3, 10, 10));
    private final JPanel cartList = new JPanel();
    private final List<CartLine> cart = new ArrayList<>();
    private final JLabel totalLabel = new JLabel("TOTAL  $0.00");

    CashierPanel(Connection connection, Runnable onSwitchRole) {
        super(new BorderLayout(10, 10));
        this.connection = connection;
        setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));
        buildHeader(onSwitchRole);
        buildMenu();
        buildCart();
        refreshMenu();
    }

    private void buildHeader(Runnable onSwitchRole) {
        JPanel top = new JPanel(new BorderLayout());
        JLabel heading = new JLabel("Tap a drink to add one");
        heading.setFont(heading.getFont().deriveFont(22f));
        top.add(heading, BorderLayout.WEST);

        JButton role = new JButton("Switch role");
        role.setFont(role.getFont().deriveFont(16f));
        role.setPreferredSize(new java.awt.Dimension(150, 52));
        role.addActionListener(event -> onSwitchRole.run());
        top.add(role, BorderLayout.EAST);
        add(top, BorderLayout.NORTH);
    }

    private void buildMenu() {
        menuGrid.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        JScrollPane menuScroll = new JScrollPane(menuGrid);
        menuScroll.setBorder(BorderFactory.createTitledBorder("Menu"));
        menuScroll.getVerticalScrollBar().setUnitIncrement(28);
        add(menuScroll, BorderLayout.CENTER);
    }

    private void buildCart() {
        cartList.setLayout(new javax.swing.BoxLayout(cartList, javax.swing.BoxLayout.Y_AXIS));
        JScrollPane cartScroll = new JScrollPane(cartList);
        cartScroll.setBorder(BorderFactory.createTitledBorder("Order — tap × to remove"));
        cartScroll.getVerticalScrollBar().setUnitIncrement(24);

        JPanel right = new JPanel(new BorderLayout(8, 8));
        right.add(cartScroll, BorderLayout.CENTER);
        right.setPreferredSize(new java.awt.Dimension(355, 0));

        totalLabel.setFont(totalLabel.getFont().deriveFont(java.awt.Font.BOLD, 23f));
        totalLabel.setBorder(BorderFactory.createEmptyBorder(8, 4, 8, 4));
        JButton submit = new JButton("Submit order");
        submit.setFont(submit.getFont().deriveFont(java.awt.Font.BOLD, 20f));
        submit.setPreferredSize(new java.awt.Dimension(320, 72));
        submit.addActionListener(event -> submitOrder());

        JPanel customizations = new JPanel(new GridLayout(2, 2, 6, 6));
        customizations.add(customizationButton("Add boba (+$0.99)", "Boba"));
        customizations.add(customizationButton("Less ice", "Less ice"));
        customizations.add(customizationButton("More ice", "More ice"));
        customizations.add(customizationButton("Less sweet", "Less sweet"));

        JPanel actions = new JPanel(new BorderLayout(6, 8));
        actions.add(totalLabel, BorderLayout.NORTH);
        actions.add(customizations, BorderLayout.CENTER);
        actions.add(submit, BorderLayout.SOUTH);
        right.add(actions, BorderLayout.SOUTH);
        add(right, BorderLayout.EAST);
    }

    private void refreshMenu() {
        menuGrid.removeAll();
        String sql = "SELECT menu_item_id, item_name, current_price FROM menu_item ORDER BY item_name";
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            while (result.next()) {
                int id = result.getInt("menu_item_id");
                String name = result.getString("item_name");
                BigDecimal price = result.getBigDecimal("current_price");
                JButton drink = new JButton("<html><center>" + UiSupport.escapeHtml(name)
                        + "<br><b>$" + price.setScale(2) + "</b></center></html>");
                drink.setFont(drink.getFont().deriveFont(java.awt.Font.PLAIN, 18f));
                drink.setPreferredSize(new java.awt.Dimension(190, 96));
                drink.setMargin(new java.awt.Insets(10, 10, 10, 10));
                drink.addActionListener(event -> addToCart(id, name, price));
                menuGrid.add(drink);
            }
        } catch (SQLException ex) {
            UiSupport.showError(this, "Load menu", ex);
        }
        menuGrid.revalidate();
        menuGrid.repaint();
    }

    private void addToCart(int id, String name, BigDecimal price) {
        for (CartLine line : cart) {
            if (line.menuId == id && line.customizations.isEmpty()) {
                line.quantity++;
                renderCart();
                return;
            }
        }
        cart.add(new CartLine(id, name, price));
        renderCart();
    }

    private void removeFromCart(CartLine line) {
        if (!needsQuantityPrompt(line)) {
            removeCartQuantity(cart, line, 1);
            renderCart();
            return;
        }

        String quantityText = JOptionPane.showInputDialog(this,
                "How many " + line.name + " drinks should be removed? Enter 1–" + line.quantity + ".",
                "Remove quantity", JOptionPane.QUESTION_MESSAGE);
        try {
            if (!removeCartQuantityInput(cart, line, quantityText)) { return; }
        } catch (IllegalArgumentException ex) {
            showInvalidCartQuantity(line.quantity);
            return;
        }
        renderCart();
    }

    private JButton customizationButton(String label, String customization) {
        JButton button = new JButton(label);
        button.setFont(button.getFont().deriveFont(14f));
        button.addActionListener(event -> customizeCartLine(customization));
        return button;
    }

    private void customizeCartLine(String customization) {
        if (cart.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Add a drink to the order first.");
            return;
        }

        Object[] choices = new Object[cart.size()];
        for (int i = 0; i < cart.size(); i++) {
            CartLine line = cart.get(i);
            String description = line.customizations.isEmpty() ? "" : ", "
                    + String.join(", ", line.customizations);
            choices[i] = (i + 1) + ". " + line.name + description + " (" + line.quantity + ")";
        }
        Object selected = JOptionPane.showInputDialog(this,
                "Which drink in the order should get " + customization.toLowerCase() + "?",
                "Choose a drink", JOptionPane.PLAIN_MESSAGE, null, choices, choices[0]);
        if (selected == null) {
            return;
        }

        int selectedIndex = java.util.Arrays.asList(choices).indexOf(selected);
        if (selectedIndex < 0) {
            return;
        }
        CartLine line = cart.get(selectedIndex);
        if (line.customizations.contains(customization)) {
            JOptionPane.showMessageDialog(this, line.name + " already has "
                    + customization.toLowerCase() + ".");
            return;
        }
        if (!needsQuantityPrompt(line)) {
            applyCustomization(cart, line, customization, 1);
        } else {
            String quantityText = JOptionPane.showInputDialog(this,
                    "How many of these " + line.name + " drinks should get "
                            + customization.toLowerCase() + "? Enter 1–" + line.quantity + ".",
                    "Choose quantity", JOptionPane.QUESTION_MESSAGE);
            try {
                if (!applyCustomizationInput(cart, line, customization, quantityText)) { return; }
            } catch (IllegalArgumentException ex) {
                showInvalidCartQuantity(line.quantity);
                return;
            }
        }
        renderCart();
    }

    static int parseCartQuantity(String input, int maximum) {
        final int quantity;
        try {
            quantity = Integer.parseInt(input.trim());
        } catch (NumberFormatException | NullPointerException ex) {
            throw new IllegalArgumentException("Enter a whole number from 1 to " + maximum + ".", ex);
        }
        if (quantity <= 0 || quantity > maximum) {
            throw new IllegalArgumentException("Enter a whole number from 1 to " + maximum + ".");
        }
        return quantity;
    }

    static boolean needsQuantityPrompt(CartLine line) {
        return line.quantity > 1;
    }

    static boolean applyCustomizationInput(List<CartLine> lines, CartLine line,
            String customization, String input) {
        if (input == null) { return false; }
        int quantity = parseCartQuantity(input, line.quantity);
        applyCustomization(lines, line, customization, quantity);
        return true;
    }

    static boolean removeCartQuantityInput(List<CartLine> lines, CartLine line, String input) {
        if (input == null) { return false; }
        int quantity = parseCartQuantity(input, line.quantity);
        removeCartQuantity(lines, line, quantity);
        return true;
    }

    static void removeCartQuantity(List<CartLine> lines, CartLine line, int quantity) {
        if (quantity <= 0 || quantity > line.quantity) {
            throw new IllegalArgumentException("Removal quantity is outside the cart line quantity.");
        }
        line.quantity -= quantity;
        if (line.quantity == 0) { lines.remove(line); }
    }

    static void applyCustomization(List<CartLine> lines, CartLine line, String customization, int quantity) {
        if (quantity <= 0 || quantity > line.quantity) {
            throw new IllegalArgumentException("Customization quantity is outside the cart line quantity.");
        }
        if (line.customizations.contains(customization)) {
            throw new IllegalArgumentException("This line already has that customization.");
        }
        CartLine customized = line.withCustomization(customization, quantity);
        line.quantity -= quantity;
        if (line.quantity == 0) { lines.remove(line); }
        for (CartLine existing : lines) {
            if (existing.menuId == customized.menuId && existing.hasSameCustomizations(customized)) {
                existing.quantity += customized.quantity;
                return;
            }
        }
        lines.add(customized);
    }

    private void showInvalidCartQuantity(int maximum) {
        JOptionPane.showMessageDialog(this,
                "Enter a whole number from 1 to " + maximum + ".",
                "Invalid quantity", JOptionPane.WARNING_MESSAGE);
    }

    private BigDecimal cartTotal() {
        return totalForCart(cart);
    }

    static BigDecimal totalForCart(List<CartLine> lines) {
        BigDecimal total = BigDecimal.ZERO;
        for (CartLine line : lines) {
            total = total.add(line.unitPrice().multiply(BigDecimal.valueOf(line.quantity)));
        }
        return total;
    }

    private void renderCart() {
        cartList.removeAll();
        for (CartLine line : cart) {
            JPanel row = new JPanel(new BorderLayout(8, 4));
            row.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 0, 1, 0, java.awt.Color.LIGHT_GRAY),
                    BorderFactory.createEmptyBorder(8, 6, 8, 6)));

            String customizationText = line.customizations.isEmpty()
                    ? "" : "<br><small>" + UiSupport.escapeHtml(String.join(", ", line.customizations)) + "</small>";
            JLabel details = new JLabel("<html><b>" + UiSupport.escapeHtml(line.name) + "</b><br>"
                    + line.quantity + " x $" + line.unitPrice().setScale(2) + " &nbsp; $"
                    + line.unitPrice().multiply(BigDecimal.valueOf(line.quantity)).setScale(2)
                    + customizationText + "</html>");
            details.setFont(details.getFont().deriveFont(16f));

            JButton remove = new JButton("×");
            remove.setFont(remove.getFont().deriveFont(java.awt.Font.BOLD, 28f));
            remove.setForeground(new java.awt.Color(150, 35, 35));
            remove.setPreferredSize(new java.awt.Dimension(58, 54));
            remove.setToolTipText("Remove " + line.name + " from this order");
            remove.addActionListener(event -> removeFromCart(line));

            row.add(details, BorderLayout.CENTER);
            row.add(remove, BorderLayout.EAST);
            row.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE, 78));
            cartList.add(row);
        }
        totalLabel.setText("TOTAL  $" + cartTotal().setScale(2));
        cartList.revalidate();
        cartList.repaint();
    }

    private void submitOrder() {
        if (cart.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Tap a drink to add it to the order.");
            return;
        }

        try {
            connection.setAutoCommit(false);
            lockOrderAndInventoryTables();
            long orderId = UiSupport.nextId(connection, "customer_order", "order_id");
            long nextOrderItemId = UiSupport.nextId(connection, "order_item", "order_item_id");
            insertOrder(orderId);
            insertOrderItemsAndDeductInventory(orderId, nextOrderItemId);
            connection.commit();
            connection.setAutoCommit(true);

            JOptionPane.showMessageDialog(this,
                    "Order #" + orderId + " submitted. Total: $" + cartTotal().setScale(2),
                    "Order complete", JOptionPane.INFORMATION_MESSAGE);
            cart.clear();
            renderCart();
        } catch (SQLException ex) {
            rollbackTransaction();
            UiSupport.showError(this, "Submit order (check inventory availability)", ex);
        }
    }

    private void lockOrderAndInventoryTables() throws SQLException {
        // Serialize ID allocation and inventory deductions for simultaneous registers.
        try (Statement lock = connection.createStatement()) {
            lock.execute("LOCK TABLE customer_order, order_item IN SHARE ROW EXCLUSIVE MODE");
            lock.execute("LOCK TABLE inventory_item IN ROW EXCLUSIVE MODE");
        }
    }

    private void insertOrder(long orderId) throws SQLException {
        String sql = "INSERT INTO customer_order(order_id, order_time, total_price) VALUES (?, ?, ?)";
        try (PreparedStatement order = connection.prepareStatement(sql)) {
            order.setLong(1, orderId);
            order.setTimestamp(2, Timestamp.valueOf(LocalDateTime.now()));
            order.setBigDecimal(3, cartTotal());
            order.executeUpdate();
        }
    }

    private void insertOrderItemsAndDeductInventory(long orderId, long nextOrderItemId)
            throws SQLException {
        String insertItemSql = "INSERT INTO order_item"
                + "(order_item_id, order_id, menu_item_id, quantity, unit_price, customizations) "
                + "VALUES (?, ?, ?, ?, ?, ?)";
        String deductStockSql = "UPDATE inventory_item i "
                + "SET quantity_in_stock = i.quantity_in_stock - (? * mii.quantity_required) "
                + "FROM menu_item_ingredient mii WHERE mii.menu_item_id = ? "
                + "AND i.inventory_item_id = mii.inventory_item_id "
                + "AND i.quantity_in_stock >= (? * mii.quantity_required)";
        String recipeCountSql = "SELECT COUNT(*) FROM menu_item_ingredient WHERE menu_item_id=?";

        try (PreparedStatement item = connection.prepareStatement(insertItemSql);
             PreparedStatement stock = connection.prepareStatement(deductStockSql);
             PreparedStatement recipe = connection.prepareStatement(recipeCountSql)) {
            for (CartLine line : cart) {
                bindOrderItem(item, nextOrderItemId++, orderId, line);
                item.executeUpdate();

                recipe.setInt(1, line.menuId);
                int recipeRows;
                try (ResultSet result = recipe.executeQuery()) {
                    result.next();
                    recipeRows = result.getInt(1);
                }
                if (recipeRows == 0) {
                    throw new SQLException("This menu item has no inventory recipe configured.");
                }

                stock.setInt(1, line.quantity);
                stock.setInt(2, line.menuId);
                stock.setInt(3, line.quantity);
                if (stock.executeUpdate() != recipeRows) {
                    throw new SQLException("There is not enough inventory for " + line.name + ".");
                }
            }
        }
    }

    static void bindOrderItem(PreparedStatement item, long itemId, long orderId, CartLine line)
            throws SQLException {
        item.setLong(1, itemId);
        item.setLong(2, orderId);
        item.setInt(3, line.menuId);
        item.setInt(4, line.quantity);
        item.setBigDecimal(5, line.unitPrice());
        item.setString(6, String.join(", ", line.customizations));
    }

    private void rollbackTransaction() {
        try {
            connection.rollback();
            connection.setAutoCommit(true);
        } catch (SQLException rollbackException) {
            LOG.warning("Could not restore database transaction state: "
                    + UiSupport.messageFor(rollbackException));
        }
    }

    static final class CartLine {
        private final int menuId;
        private final String name;
        private final BigDecimal basePrice;
        private final List<String> customizations = new ArrayList<>();
        private int quantity;

        private CartLine(int menuId, String name, BigDecimal price) {
            this(menuId, name, price, 1);
        }

        private CartLine(int menuId, String name, BigDecimal basePrice, int quantity) {
            this.menuId = menuId;
            this.name = name;
            this.basePrice = basePrice;
            this.quantity = quantity;
        }

        private BigDecimal unitPrice() {
            return customizations.contains("Boba")
                    ? basePrice.add(new BigDecimal("0.99")) : basePrice;
        }

        private CartLine withCustomization(String customization, int units) {
            CartLine customized = new CartLine(menuId, name, basePrice, units);
            customized.customizations.addAll(customizations);
            customized.customizations.add(customization);
            return customized;
        }

        private boolean hasSameCustomizations(CartLine other) {
            return customizations.size() == other.customizations.size()
                    && customizations.containsAll(other.customizations);
        }
    }
}
