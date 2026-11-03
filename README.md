# Boba Shop POS

## Description

This project is a point-of-sale application for a generic boba shop. Cashiers can
browse the available drinks, add drinks to an order, choose customizations such
as boba, ice, and sweetness options, and submit the order. In database mode,
menu items, orders, ingredients, and inventory are read from and written to a
PostgreSQL database.

Deployment URL: https://project3-deployment-xt3n.onrender.com/

## How to Run

### 1. Install dependencies

From the project directory, install the Node.js dependencies:

```powershell
npm install
```

### 2. Run with the PostgreSQL database

Create a `.env` file in the project root using the format below and make sure
the PostgreSQL database is running. On startup, the app creates any missing
POS tables from `db/schema.sql` and adds a small sample menu only when the menu
is empty. Existing menu and inventory rows are preserved.

Then start the application:

```powershell
npm start
```

Open the application at [http://localhost:3000](http://localhost:3000).

### 3. Run mock mode for general testing

Mock mode does not connect to PostgreSQL. It loads sample menu items and
processes orders in memory, making it useful for testing the interface without
database credentials:

```powershell
npm run start:mock
```

Open the application at [http://localhost:3000](http://localhost:3000).

Orders submitted in mock mode are shared between the cashier and kitchen while
the app is running. Mock orders and kitchen status changes are cleared when the
app stops. Database mode stores orders, items, and kitchen status in PostgreSQL.

At checkout, choose Cash, Credit or debit card, or Mobile pay. The selected
method is saved with the order. Card and mobile payments are recorded as the
selected method only; processing them requires connecting a payment provider.

## `.env` File Format

Create a file named `.env` in the project root. Replace the placeholder values
with the credentials and connection details for your PostgreSQL database:

```dotenv
PSQL_USER=your_database_username
PSQL_HOST=your_database_host
PSQL_DATABASE=your_database_name
PSQL_PASSWORD=your_database_password
PSQL_PORT=5432
```

