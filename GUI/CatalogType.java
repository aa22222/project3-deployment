/** Database-backed catalogs available in the manager interface. */
enum CatalogType {
    MENU("Menu", "menu_item", "menu_item_id"),
    INVENTORY("Inventory", "inventory_item", "inventory_item_id"),
    EMPLOYEE("Employee", "employee", "employee_id");

    final String label;
    final String tableName;
    final String idColumn;

    CatalogType(String label, String tableName, String idColumn) {
        this.label = label;
        this.tableName = tableName;
        this.idColumn = idColumn;
    }
}
