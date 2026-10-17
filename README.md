# Project Documentation

### Overall Usage

Our project acts as a POS for a boba shop (i.e. Sharetea), with screens for management to track general store operations such as daily sales, tracking inventory, and managing employees, and screens for employees that allow for them to create orders for customers with ease. 

In this repo, we release the code for:

- **Java GUI**
  - A Java Swing based GUI system that connects both our frontend management screens to our database.
- **Database and Queries**
  - General database setup to create your own local version of our project and other helpful general queries in `queries.sql`.
- **Updates and Setup**
  - Information on how to run our project, with an `example.env` and scripts to run the project such as `run.sh`.
  - Updates on the project as it goes through each Phase.

---

## Phase 3 GUI

### Prerequisites & Credentials
The GUI reads database settings from environment variables. Keep actual credentials in a local `.env` file (ignored by Git).

1. Copy `.env.example` to `.env`:
   ```sh
   cp .env.example .env
   ```
2. Open `.env` and enter your database password. Do **not** commit or share this file.
3. On macOS or Linux, restrict file permissions:
   ```sh
   chmod 600 .env
   ```

> **Note:** The launcher reads `DB_URL`, `DB_USER`, and `DB_PASSWORD` directly from `.env` without executing it as shell code. Environment variables set in your terminal session take precedence over `.env`.

### Running the Application
Run the launcher script corresponding to your operating system from the project root directory:

- **macOS / Linux**:
  ```sh
  ./run.sh
  ```
- **Windows**:
  ```cmd
  .\run.bat
  ```

> **Existing database:** Before running a cashier build that stores drink customizations, add the `customizations` column to `order_item`. Run the migration in `Database Seeding/alter_tables.sql` against that database, or run this idempotent statement directly:
> ```sql
> ALTER TABLE order_item
> ADD COLUMN IF NOT EXISTS customizations TEXT NOT NULL DEFAULT '';
> ```

---

## Database Seeding

> **Important:** All commands in this section must be executed from the `Database Seeding` directory.

Follow these steps sequentially:

1. Generate initial sales data:
   ```sh
   python sales_generation.py
   ```
2. Execute table setup and data population scripts in `psql`:
   ```sql
   \i create_tables.sql
   \copy sales_history (sale_date, item_name, price) FROM './boba_sales.csv' WITH (FORMAT csv, HEADER true)
   \i populate_inventory.sql
   \i populate_menu.sql
   \i alter_tables.sql
   \i populate_orders.sql
   \i populate_recipes.sql
   ```
3. **Verification**: Confirm that `sales_history`, `customer_order`, and `order_item` have matching row counts and total revenue amounts.
4. **Finalize**:
   - If verification passes, execute `COMMIT;` to persist changes to the remote database.
   - If verification fails, execute `ROLLBACK;` to undo the import.

---

## Running Queries

> **Important:** Execute all query commands from the **repository root**.

### Run a Specific Query
Replace `<query_name>` with a variable name from the table below:
```sh
psql -h csce-315-db.engr.tamu.edu -U squad_41 -d squad_41_db -v <query_name>=1 -f queries.sql
```

### Run All Queries
```sh
psql -h csce-315-db.engr.tamu.edu -U squad_41 -d squad_41_db -v run_all=1 -v ON_ERROR_STOP=1 -P pager=off -f queries.sql
```

### Query Reference Table

| Query Variable | Description |
|---|---|
| `run_all` | Run all queries |
| `weekly_sale_history` | Weekly Sale History: Order Count Query |
| `peak_sales` | Peak Sales Day Query |
| `realistic_sales_history` | Realistic Sales History Query: Sum of Order Total per Hour (Mon–Thu: 11 AM–11 PM; Fri–Sat: 11 AM–12 AM) |
| `menu_item_inventory` | Menu Item Inventory: Inventory items used by each drink |
| `best_of_the_worst` | Best of the Worst |
| `top_seller` | Top Selling Menu Items |
| `restock` | Items That Need Restocking |
| `avg_order_by_day` | Average Order Value by Day of Week |
| `most_expensive` | Most Expensive Orders |
| `item_sale_by_month` | Menu Item Sales By Month |
| `least_ordered` | Least Ordered Item |
| `most_ordered` | Most Ordered Item |
| `item_percent` | Menu Item Revenue Contribution Percentage |
| `performance_by_shift` | Sales Performance by Shift |
| `order_size` | Order Size Distribution (Items per Ticket) |
