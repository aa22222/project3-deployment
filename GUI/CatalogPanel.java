import java.awt.BorderLayout;
import java.awt.GridLayout;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.table.DefaultTableModel;

/** Manager panel for viewing and maintaining one database catalog. */
final class CatalogPanel extends JPanel {
    private final Connection connection;
    private final CatalogType type;
    private final DefaultTableModel data;
    private final JTable grid;

    CatalogPanel(Connection connection, CatalogType type) {
        super(new BorderLayout(8, 8));
        this.connection = connection;
        this.type = type;
        this.data = UiSupport.tableModel(columns());
        this.grid = UiSupport.table(data);

        setBorder(javax.swing.BorderFactory.createEmptyBorder(10, 10, 10, 10));
        JButton add = new JButton("Add");
        JButton update = new JButton("Update selected");
        JButton delete = new JButton("Delete selected");
        JButton recipe = new JButton("Edit recipe");
        JButton refresh = new JButton("Refresh");
        
        JButton addSeasonal = new JButton("Add Seasonal Item");

        add.addActionListener(event -> editRecord(-1));
        update.addActionListener(event -> editSelected());
        delete.addActionListener(event -> deleteSelected());
        recipe.addActionListener(event -> editSelectedRecipe());
        refresh.addActionListener(event -> refresh());
        addSeasonal.addActionListener(event -> addSeasonalMenuItem());

        JPanel actions = type == CatalogType.MENU
                ? UiSupport.buttonBar(add, addSeasonal, recipe, update, delete, refresh)
                : UiSupport.buttonBar(add, update, delete, refresh);
        add(actions, BorderLayout.NORTH);
        add(new JScrollPane(grid), BorderLayout.CENTER);
        refresh();
    }

    private Object[] columns() {
        switch (type) {
            case MENU:
                return new Object[] {"ID", "Item name", "Price"};
            case INVENTORY:
                return new Object[] {"ID", "Item name", "Type", "Quantity", "Unit", "Reorder level"};
            case EMPLOYEE:
                return new Object[] {"ID", "First name", "Last name", "Hourly wage", "Hours worked"};
            default:
                throw new IllegalStateException("Unsupported catalog type: " + type);
        }
    }

    private String selectSql() {
        switch (type) {
            case MENU:
                return "SELECT menu_item_id, item_name, current_price FROM menu_item ORDER BY menu_item_id";
            case INVENTORY:
                return "SELECT inventory_item_id, item_name, item_type, quantity_in_stock, "
                        + "unit, reorder_level FROM inventory_item ORDER BY inventory_item_id";
            case EMPLOYEE:
                return "SELECT employee_id, first_name, last_name, hourly_wage, hours_worked "
                        + "FROM employee ORDER BY employee_id";
            default:
                throw new IllegalStateException("Unsupported catalog type: " + type);
        }
    }

    /**
     * Reloads the table from the database so users can see the latest records
     * after adding, updating, or deleting items. Database errors are displayed
     * to the user instead of being propagated.
     */
    private void refresh() {
        data.setRowCount(0);
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(selectSql())) {
            int columnCount = data.getColumnCount();
            while (result.next()) {
                Object[] row = new Object[columnCount];
                for (int column = 0; column < columnCount; column++) {
                    row[column] = result.getObject(column + 1);
                }
                data.addRow(row);
            }
        } catch (SQLException ex) {
            UiSupport.showError(this, "Load " + type.label.toLowerCase(), ex);
        }
    }

    private void addSeasonalMenuItem() {
        JTextField menuName = UiSupport.field("");
        JTextField menuPrice = UiSupport.field("");
        JTextField invName = UiSupport.field("");
        JTextField invStock = UiSupport.field("50.0");
        JTextField invUnit = UiSupport.field("Lbs");
        JTextField invReorder = UiSupport.field("10.0");
        JTextField recipeQty = UiSupport.field("0.5");

        JPanel form = new JPanel(new GridLayout(7, 2, 8, 8));
        form.add(new JLabel("Menu item name"));
        form.add(menuName);
        form.add(new JLabel("Menu item price"));
        form.add(menuPrice);
        form.add(new JLabel("Ingredient name"));
        form.add(invName);
        form.add(new JLabel("Ingredient starting stock"));
        form.add(invStock);
        form.add(new JLabel("Ingredient unit"));
        form.add(invUnit);
        form.add(new JLabel("Ingredient reorder level"));
        form.add(invReorder);
        form.add(new JLabel("Recipe quantity required"));
        form.add(recipeQty);

        int answer = JOptionPane.showConfirmDialog(this, form, "Add Seasonal Item",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        
        if (answer != JOptionPane.OK_OPTION) {
            return;
        }

        String insertInventory = "INSERT INTO inventory_item(inventory_item_id, item_name, item_type, quantity_in_stock, unit, reorder_level) VALUES (?, ?, ?, ?, ?, ?)";
        String insertMenu = "INSERT INTO menu_item(menu_item_id, item_name, current_price) VALUES (?, ?, ?)";
        String linkRecipe = "INSERT INTO menu_item_ingredient(menu_item_id, inventory_item_id, quantity_required) VALUES (?, ?, ?)";

        try {
            int invId = UiSupport.nextId(connection, "inventory_item", "inventory_item_id");
            try (PreparedStatement invStmt = connection.prepareStatement(insertInventory)) {
                invStmt.setInt(1, invId);
                invStmt.setString(2, UiSupport.required(invName));
                invStmt.setString(3, "ingredient");
                invStmt.setBigDecimal(4, UiSupport.decimal(invStock));
                invStmt.setString(5, UiSupport.required(invUnit));
                invStmt.setBigDecimal(6, UiSupport.decimal(invReorder));
                invStmt.executeUpdate();
            }

            int menuId = UiSupport.nextId(connection, "menu_item", "menu_item_id");
            try (PreparedStatement menuStmt = connection.prepareStatement(insertMenu)) {
                menuStmt.setInt(1, menuId);
                menuStmt.setString(2, UiSupport.required(menuName));
                menuStmt.setBigDecimal(3, UiSupport.decimal(menuPrice));
                menuStmt.executeUpdate();
            }

            try (PreparedStatement recipeStmt = connection.prepareStatement(linkRecipe)) {
                recipeStmt.setInt(1, menuId);
                recipeStmt.setInt(2, invId);
                recipeStmt.setBigDecimal(3, UiSupport.decimal(recipeQty));
                recipeStmt.executeUpdate();
            }
            
            JOptionPane.showMessageDialog(this, "Seasonal item added successfully!");
            refresh();
            
        } catch (Exception e) {
            UiSupport.showError(this, "Add seasonal item", e);
        }
    }

    private void editSelected() {
        int selectedRow = grid.getSelectedRow();
        if (selectedRow < 0) {
            JOptionPane.showMessageDialog(this, "Select a row first.");
            return;
        }
        editRecord(grid.convertRowIndexToModel(selectedRow));
    }

    private void editSelectedRecipe() {
        int selectedRow = grid.getSelectedRow();
        if (selectedRow < 0) {
            JOptionPane.showMessageDialog(this, "Select a menu item first.");
            return;
        }
        int modelRow = grid.convertRowIndexToModel(selectedRow);
        int menuItemId = (Integer) data.getValueAt(modelRow, 0);
        String menuItemName = String.valueOf(data.getValueAt(modelRow, 1));
        new RecipeEditorDialog(javax.swing.SwingUtilities.getWindowAncestor(this),
                connection, menuItemId, menuItemName).setVisible(true);
    }

    private void editRecord(int row) {
        boolean editing = row >= 0;
        String[] labels = formLabels();
        JTextField[] fields = formFields(editing, row);
        JPanel form = new JPanel(new GridLayout(labels.length, 2, 8, 8));
        for (int index = 0; index < labels.length; index++) {
            form.add(new JLabel(labels[index]));
            form.add(fields[index]);
        }

        int answer = JOptionPane.showConfirmDialog(this, form,
                (editing ? "Update " : "Add ") + type.label,
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (answer != JOptionPane.OK_OPTION) {
            return;
        }

        try {
            save(editing, row, fields);
            refresh();
        } catch (Exception ex) {
            UiSupport.showError(this, "Save " + type.label.toLowerCase(), ex);
        }
    }

    private String[] formLabels() {
        switch (type) {
            case MENU:
                return new String[] {"Item name", "Price"};
            case INVENTORY:
                return new String[] {"Item name", "Type (ingredient/topping/supply)",
                        "Quantity in stock", "Unit", "Reorder level"};
            case EMPLOYEE:
                return new String[] {"First name", "Last name", "Hourly wage", "Hours worked"};
            default:
                throw new IllegalStateException("Unsupported catalog type: " + type);
        }
    }

    private JTextField[] formFields(boolean editing, int row) {
        switch (type) {
            case MENU:
                return new JTextField[] {
                        UiSupport.field(editing ? data.getValueAt(row, 1) : ""),
                        UiSupport.field(editing ? data.getValueAt(row, 2) : "")
                };
            case INVENTORY:
                return new JTextField[] {
                        UiSupport.field(editing ? data.getValueAt(row, 1) : ""),
                        UiSupport.field(editing ? data.getValueAt(row, 2) : "ingredient"),
                        UiSupport.field(editing ? data.getValueAt(row, 3) : "0"),
                        UiSupport.field(editing ? data.getValueAt(row, 4) : "units"),
                        UiSupport.field(editing ? data.getValueAt(row, 5) : "0")
                };
            case EMPLOYEE:
                return new JTextField[] {
                        UiSupport.field(editing ? data.getValueAt(row, 1) : ""),
                        UiSupport.field(editing ? data.getValueAt(row, 2) : ""),
                        UiSupport.field(editing ? data.getValueAt(row, 3) : "0"),
                        UiSupport.field(editing ? data.getValueAt(row, 4) : "0")
                };
            default:
                throw new IllegalStateException("Unsupported catalog type: " + type);
        }
    }

    /**
     * Routes the save operation to the correct method based on catalog type,
     * keeping menu, inventory, and employee database logic separate.
     * @param editing whether an existing record is being updated
     * @param row the table model row of the selected record
     * @param fields the input fields containing the record data
     * @throws SQLException if saving the record fails
     */
    private void save(boolean editing, int row, JTextField[] fields) throws SQLException {
        switch (type) {
            case MENU:
                saveMenu(editing, row, fields);
                break;
            case INVENTORY:
                saveInventory(editing, row, fields);
                break;
            case EMPLOYEE:
                saveEmployee(editing, row, fields);
                break;
            default:
                throw new IllegalStateException("Unsupported catalog type: " + type);
        }
    }

    private void saveMenu(boolean editing, int row, JTextField[] fields) throws SQLException {
        String sql = editing
                ? "UPDATE menu_item SET item_name=?, current_price=? WHERE menu_item_id=?"
                : "INSERT INTO menu_item(menu_item_id, item_name, current_price) VALUES (?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            if (editing) {
                statement.setString(1, UiSupport.required(fields[0]));
                statement.setBigDecimal(2, UiSupport.decimal(fields[1]));
                statement.setInt(3, (Integer) data.getValueAt(row, 0));
            } else {
                statement.setInt(1, UiSupport.nextId(connection, type.tableName, type.idColumn));
                statement.setString(2, UiSupport.required(fields[0]));
                statement.setBigDecimal(3, UiSupport.decimal(fields[1]));
            }
            statement.executeUpdate();
        }
    }

    private void saveInventory(boolean editing, int row, JTextField[] fields) throws SQLException {
        String sql = editing
                ? "UPDATE inventory_item SET item_name=?, item_type=?, quantity_in_stock=?, unit=?, "
                        + "reorder_level=? WHERE inventory_item_id=?"
                : "INSERT INTO inventory_item(inventory_item_id, item_name, item_type, quantity_in_stock, "
                        + "unit, reorder_level) VALUES (?, ?, ?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            if (!editing) {
                statement.setInt(index++, UiSupport.nextId(connection, type.tableName, type.idColumn));
            }
            statement.setString(index++, UiSupport.required(fields[0]));
            statement.setString(index++, UiSupport.required(fields[1]).toLowerCase());
            statement.setBigDecimal(index++, UiSupport.decimal(fields[2]));
            statement.setString(index++, UiSupport.required(fields[3]));
            statement.setBigDecimal(index++, UiSupport.decimal(fields[4]));
            if (editing) {
                statement.setInt(index, (Integer) data.getValueAt(row, 0));
            }
            statement.executeUpdate();
        }
    }

    private void saveEmployee(boolean editing, int row, JTextField[] fields) throws SQLException {
        String sql = editing
                ? "UPDATE employee SET first_name=?, last_name=?, hourly_wage=?, hours_worked=? "
                        + "WHERE employee_id=?"
                : "INSERT INTO employee(employee_id, first_name, last_name, hourly_wage, hours_worked) "
                        + "VALUES (?, ?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            if (!editing) {
                statement.setInt(index++, UiSupport.nextId(connection, type.tableName, type.idColumn));
            }
            statement.setString(index++, UiSupport.required(fields[0]));
            statement.setString(index++, UiSupport.required(fields[1]));
            statement.setBigDecimal(index++, UiSupport.decimal(fields[2]));
            statement.setBigDecimal(index++, UiSupport.decimal(fields[3]));
            if (editing) {
                statement.setInt(index, (Integer) data.getValueAt(row, 0));
            }
            statement.executeUpdate();
        }
    }

    private void deleteSelected() {
        int selectedRow = grid.getSelectedRow();
        if (selectedRow < 0) {
            JOptionPane.showMessageDialog(this, "Select a row first.");
            return;
        }
        int answer = JOptionPane.showConfirmDialog(this,
                "Delete this " + type.label.toLowerCase() + " record?",
                "Confirm delete", JOptionPane.YES_NO_OPTION);
        if (answer != JOptionPane.YES_OPTION) {
            return;
        }

        String sql = "DELETE FROM " + type.tableName + " WHERE " + type.idColumn + "=?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int modelRow = grid.convertRowIndexToModel(selectedRow);
            statement.setInt(1, (Integer) data.getValueAt(modelRow, 0));
            statement.executeUpdate();
            refresh();
        } catch (SQLException ex) {
            UiSupport.showError(this, "Delete " + type.label.toLowerCase(), ex);
        }
    }
}