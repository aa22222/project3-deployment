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
POS tables from `db/schema.sql`. Menu, inventory, orders, and employees always
come from PostgreSQL. Startup preserves existing data and never seeds products.

Then start the application:

```powershell
npm start
```

Open the application at [http://localhost:3000](http://localhost:3000).

At checkout, choose Cash, Credit or debit card, or Mobile pay. The selected
method is saved with the order. Card and mobile payments are recorded as the
selected method only; processing them requires connecting a payment provider.
After a cashier or kiosk order is submitted, use **Print receipt** to open the
browser's print dialog. The receipt includes the order number, time, items,
customizations, payment method, and total.

## Manager menu editing

Managers can open **Menu** in the manager portal (`/manager/menu`) to search
existing menu items and edit names and prices. Save each item separately; Reset
restores its last saved values. Changes are stored in PostgreSQL and appear on
cashier, kiosk, and mobile menus when those pages are next loaded.

## Manager X-report

Open **X-report** in the manager portal (`/manager/x-report`) for a read-only
sales snapshot. It defaults to today; choose a date and refresh to view another
day. Dates and hours use the PostgreSQL session timezone shown on the report.
The report includes sales after rewards discounts, order count, average order
value, items sold, discounts, hourly sales, payment totals, and the top ten items
by quantity. Item sales use the unit prices saved with orders, before discounts.
Cancelled orders are excluded. Viewing or printing never resets sales totals.
Use **Refresh report** to fetch new sales; the report does not refresh automatically.

## Mobile ordering

Open `/mobile` (also linked from the portal) for a phone-friendly menu with menu
photos, kiosk-style categories, per-item customization, rewards, and checkout. It uses the
same menu and order backend as the cashier and kiosk. Checkout retry identifiers
prevent duplicate submissions during a server session; they reset on restart. Mobile and
kiosk photos are matched by name in `public/images/menu`: for example, `Taro Milk Tea`
uses `taro-milk-tea.png`. PNG, JPG, and WebP are supported. Items without a matching
file retain the placeholder.

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

## Phone and email rewards

Both `/cashier` and `/kiosk` have **Boba Rewards** at checkout. A customer can
join or sign in with a phone number or email and a six-digit verification code.
Use **Link phone/email** after signing in to verify a second contact on the same
account. Separate accounts are not automatically merged.

- Earn one point per whole dollar actually paid, rounded down for each order.
- Redeem 50 existing points for $5 off an order of at least $5, once per order.
- Points are earned on the amount after the discount. New points cannot fund
  the same order's redemption.
- Reward texts are optional and require a verified phone and explicit opt-in.
  Customers can turn them off in the rewards panel or reply STOP to texts.
- Receipts include the discount, earned/redeemed points, and remaining balance.
- Checkout clears the member from the shared screen. Codes expire after five
  minutes and five failed attempts; sign-in tokens expire after 30 minutes.
  Codes and tokens are kept in server memory, so restarting requires sign-in
  again. PostgreSQL retains accounts, balances, and order history.

### Real messages and deployment

Use the names in `.env.example`; put real values in the git-ignored `.env`
locally and in Render's **Environment** settings when deployed.

For phone verification and rewards texts, configure an SMS-capable Twilio
sender and these variables:

```dotenv
TWILIO_ACCOUNT_SID=your_account_sid
TWILIO_AUTH_TOKEN=your_auth_token
TWILIO_FROM_NUMBER=your_twilio_number_in_plus_country_code_format
```

Complete the sender setup/registration required by Twilio for your account and
destination. Trial accounts can restrict recipients. Keep Twilio's built-in
STOP/HELP handling enabled. Provider acceptance is reported as **queued**, not
guaranteed delivery. See [Twilio messaging setup](https://www.twilio.com/docs/messaging/api/message-resource).

For email verification, create a Resend API key and configure a verified sender:

```dotenv
RESEND_API_KEY=your_resend_api_key
REWARDS_EMAIL_FROM=Boba Shop Rewards <rewards@your-verified-domain.example>
```

See [Resend sending documentation](https://resend.com/docs/api-reference/emails/send-email).
Email provides verification codes; post-order reward updates use opted-in SMS.
An unavailable provider reports an error instead of exposing a verification
code. Customers can still check out without rewards. A failed reward text
does not undo a successful order or point balance.

Run `npm start` for database mode. Startup adds `rewards_customer` and
`rewards_activity`; no existing orders are backfilled. Points, discounts,
order records, and inventory deductions share one transaction. Texts are
requested only after the transaction commits.

### Verification

`npm test` runs provider request, menu editing, and X-report page checks without
sending messages. Set `REWARDS_DB_TEST=true` to include PostgreSQL transaction,
X-report aggregation, and real menu checks
using your configured database. Transaction tests use connection-local temporary
tables and roll back; menu checks only read shop data. No shop records are changed.
