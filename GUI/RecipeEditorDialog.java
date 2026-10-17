import java.awt.BorderLayout;
import java.awt.Dialog;
import java.awt.GridLayout;
import java.awt.Window;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.HashSet;
import java.util.Set;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.table.DefaultTableModel;

/** Modal editor for the inventory recipe attached to one menu item. */
final class RecipeEditorDialog extends JDialog {
    private final Connection connection;
    private final int menuItemId;
    private final DefaultTableModel ingredients = UiSupport.tableModel(
            new Object[] {"Inventory ID", "Inventory item", "Required quantity", "Unit"});
    private final JTable table = UiSupport.table(ingredients);

    RecipeEditorDialog(Window owner, Connection connection, int menuItemId, String menuItemName) {
        super(owner, "Recipe — " + menuItemName, Dialog.ModalityType.APPLICATION_MODAL);
        this.connection = connection;
        this.menuItemId = menuItemId;

        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout(8, 8));
        setSize(680, 420);
        setLocationRelativeTo(owner);

        JLabel instructions = new JLabel(
                "Set the amount of each inventory item used per menu item. Units match the inventory catalog.");
        instructions.setBorder(javax.swing.BorderFactory.createEmptyBorder(10, 10, 0, 10));
        add(instructions, BorderLayout.NORTH);
        add(new JScrollPane(table), BorderLayout.CENTER);

        JButton add = new JButton("Add ingredient");
        JButton edit = new JButton("Edit quantity");
        JButton remove = new JButton("Remove ingredient");
        JButton save = new JButton("Save recipe");
        JButton cancel = new JButton("Cancel");
        add.addActionListener(event -> addIngredient());
        edit.addActionListener(event -> editIngredient());
        remove.addActionListener(event -> removeIngredient());
        save.addActionListener(event -> saveRecipe());
        cancel.addActionListener(event -> dispose());

        JPanel actions = new JPanel(new GridLayout(1, 5, 6, 0));
        actions.setBorder(javax.swing.BorderFactory.createEmptyBorder(0, 10, 10, 10));
        actions.add(add);
        actions.add(edit);
        actions.add(remove);
        actions.add(save);
        actions.add(cancel);
        add(actions, BorderLayout.SOUTH);
        getRootPane().setDefaultButton(save);

        try {
            loadRecipe();
        } catch (SQLException ex) {
            UiSupport.showError(this, "Load recipe", ex);
            dispose();
        }
    }

    private void loadRecipe() throws SQLException {
        String sql = "SELECT i.inventory_item_id, i.item_name, mii.quantity_required, i.unit "
                + "FROM menu_item_ingredient mii JOIN inventory_item i "
                + "ON i.inventory_item_id=mii.inventory_item_id "
                + "WHERE mii.menu_item_id=? ORDER BY i.item_name";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, menuItemId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    ingredients.addRow(new Object[] {result.getInt(1), result.getString(2),
                            result.getBigDecimal(3).stripTrailingZeros().toPlainString(), result.getString(4)});
                }
            }
        }
    }

    private void addIngredient() {
        try {
            Set<Integer> existingIds = existingInventoryIds();
            JComboBox<InventoryChoice> choices = new JComboBox<>();
            loadInventoryChoices(choices, existingIds);
            if (choices.getItemCount() == 0) {
                JOptionPane.showMessageDialog(this,
                        "All inventory items are already in this recipe, or no inventory items exist.");
                return;
            }

            JTextField quantity = new JTextField("1", 10);
            JPanel form = new JPanel(new GridLayout(2, 2, 8, 8));
            form.add(new JLabel("Inventory item"));
            form.add(choices);
            form.add(new JLabel("Required quantity per menu item"));
            form.add(quantity);
            int answer = JOptionPane.showConfirmDialog(this, form, "Add recipe ingredient",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (answer != JOptionPane.OK_OPTION) {
                return;
            }

            InventoryChoice choice = (InventoryChoice) choices.getSelectedItem();
            BigDecimal amount = positiveQuantity(quantity.getText());
            ingredients.addRow(new Object[] {choice.id, choice.name, amount.stripTrailingZeros().toPlainString(),
                    choice.unit});
        } catch (Exception ex) {
            UiSupport.showError(this, "Add recipe ingredient", ex);
        }
    }

    private void loadInventoryChoices(JComboBox<InventoryChoice> choices, Set<Integer> existingIds)
            throws SQLException {
        String sql = "SELECT inventory_item_id, item_name, unit FROM inventory_item ORDER BY item_name";
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                int id = result.getInt("inventory_item_id");
                if (!existingIds.contains(id)) {
                    choices.addItem(new InventoryChoice(id, result.getString("item_name"), result.getString("unit")));
                }
            }
        }
    }

    private Set<Integer> existingInventoryIds() {
        Set<Integer> ids = new HashSet<>();
        for (int row = 0; row < ingredients.getRowCount(); row++) {
            ids.add(((Number) ingredients.getValueAt(row, 0)).intValue());
        }
        return ids;
    }

    private void editIngredient() {
        int selected = selectedModelRow();
        if (selected < 0) {
            return;
        }
        JTextField quantity = new JTextField(String.valueOf(ingredients.getValueAt(selected, 2)), 10);
        JPanel form = new JPanel(new GridLayout(2, 2, 8, 8));
        form.add(new JLabel("Inventory item"));
        form.add(new JLabel(String.valueOf(ingredients.getValueAt(selected, 1))));
        form.add(new JLabel("Required quantity per menu item"));
        form.add(quantity);
        int answer = JOptionPane.showConfirmDialog(this, form, "Edit recipe quantity",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (answer == JOptionPane.OK_OPTION) {
            try {
                ingredients.setValueAt(positiveQuantity(quantity.getText()).stripTrailingZeros().toPlainString(),
                        selected, 2);
            } catch (Exception ex) {
                UiSupport.showError(this, "Edit recipe quantity", ex);
            }
        }
    }

    private void removeIngredient() {
        int selected = selectedModelRow();
        if (selected >= 0) {
            ingredients.removeRow(selected);
        }
    }

    private int selectedModelRow() {
        int selected = table.getSelectedRow();
        if (selected < 0) {
            JOptionPane.showMessageDialog(this, "Select a recipe ingredient first.");
            return -1;
        }
        return table.convertRowIndexToModel(selected);
    }

    /** Saves the edited ingredient list for this menu item to the database. */
    private void saveRecipe() {
        boolean ownsTransaction = false;
        Savepoint savepoint = null;
        try {
            ownsTransaction = connection.getAutoCommit();
            if (ownsTransaction) {
                connection.setAutoCommit(false);
            } else {
                savepoint = connection.setSavepoint();
            }

            try (PreparedStatement delete = connection.prepareStatement(
                    "DELETE FROM menu_item_ingredient WHERE menu_item_id=?")) {
                delete.setInt(1, menuItemId);
                delete.executeUpdate();
            }
            String insertSql = "INSERT INTO menu_item_ingredient "
                    + "(menu_item_id, inventory_item_id, quantity_required) VALUES (?, ?, ?)";
            try (PreparedStatement insert = connection.prepareStatement(insertSql)) {
                for (int row = 0; row < ingredients.getRowCount(); row++) {
                    insert.setInt(1, menuItemId);
                    insert.setInt(2, ((Number) ingredients.getValueAt(row, 0)).intValue());
                    insert.setBigDecimal(3, positiveQuantity(String.valueOf(ingredients.getValueAt(row, 2))));
                    insert.addBatch();
                }
                insert.executeBatch();
            }

            if (ownsTransaction) {
                connection.commit();
            } else {
                connection.releaseSavepoint(savepoint);
            }
            dispose();
        } catch (Exception ex) {
            try {
                if (ownsTransaction) {
                    connection.rollback();
                } else if (savepoint != null) {
                    connection.rollback(savepoint);
                }
            } catch (SQLException rollbackError) {
                ex.addSuppressed(rollbackError);
            }
            UiSupport.showError(this, "Save recipe", ex);
        } finally {
            if (ownsTransaction) {
                try {
                    connection.setAutoCommit(true);
                } catch (SQLException ex) {
                    UiSupport.showError(this, "Restore database connection", ex);
                }
            }
        }
    }

    private static BigDecimal positiveQuantity(String text) {
        try {
            BigDecimal amount = new BigDecimal(text.trim());
            if (amount.signum() <= 0) {
                throw new IllegalArgumentException("Recipe quantities must be greater than zero.");
            }
            return amount;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Enter a valid positive quantity.");
        }
    }

    private static final class InventoryChoice {
        final int id;
        final String name;
        final String unit;

        InventoryChoice(int id, String name, String unit) {
            this.id = id;
            this.name = name;
            this.unit = unit;
        }

        @Override
        public String toString() {
            return name + " (" + unit + ")";
        }
    }
}
