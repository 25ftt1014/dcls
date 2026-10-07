# DCLS — Delivery Logistics Simulator

Java 21 and JavaFX desktop application for the DCLS mini project. The current classroom demo runs with local in-memory sample data and does not need a database or network connection.

## Run the demo

Requirements: Java 21 (JDK) and Maven 3.9 or later.

From this folder:

```powershell
mvn javafx:run
```

Sign in with:

- Admin: `admin@dcls.local` / `demo1234`
- Manager: `manager@dcls.local` / `demo1234`
- Dispatcher: `dispatcher@dcls.local` / `demo1234`
- Driver: `driver@dcls.local` / `demo1234`

The sign-in screen opens with the Admin demo credentials filled in. The local demo data resets when the app closes.

## Demo flow

1. Sign in as Admin and review the dashboard totals.
2. Open Fleet; search by plate, model, type, or driver and change a vehicle status.
3. Open Orders; filter/search, assign the pending sample shipment, and advance an assigned order.
4. Open Live Tracking. The active demo vehicle moves every second along a fictional Brunei route; the app does not use GPS.
5. Open Route Planning to add stops and view a nearest-stop route estimate.

The account menu provides profile editing and password changes. Admin can manage accounts; Manager can manage the fleet and dispatch; Dispatcher can manage orders and routes; Driver sees assigned orders and can update their delivery status.

## Build a Windows distribution

```powershell
mvn -DskipTests package
```

The build creates `target\distribution\delivery-logistics-simulator-1.0.0.jar` and copies runtime libraries into `target\distribution\lib`. Launch the JAR with:

```powershell
java -jar target\distribution\delivery-logistics-simulator-1.0.0.jar
```

Java 21 must be installed on the computer running the JAR. JavaFX runtime libraries are included in `lib` for Windows builds.

## Optional MySQL persistence

The demo does not need MySQL. To use the JDBC repository for development or the final submission:

1. Install and start MySQL Server.
2. Run `src\main\resources\db\schema.sql` in a MySQL client. The script creates the `dcls` database and its tables.
3. Set the connection variables in the PowerShell session, then start the app:

```powershell
$env:DCLS_DB_URL = 'jdbc:mysql://localhost:3306/dcls'
$env:DCLS_DB_USER = 'your_mysql_user'
$env:DCLS_DB_PASSWORD = 'your_mysql_password'
mvn javafx:run
```

When `DCLS_DB_URL` is absent, the app uses the in-memory demo repository. When the database has no users, it seeds the same demo accounts and sample data. Keep real database credentials out of source control. Import the schema before starting the JDBC mode.

## Source layout

- `Domain.java` — shared entities, roles, statuses, and repository contract
- `AppServices.java` — authentication, role access, fleet/order rules, route estimates, and tracking simulation
- `MockRepository.java` — classroom demo data
- `JdbcRepository.java` — MySQL persistence through JDBC
- `DclsApp.java` — JavaFX views, dialogs, tables, filters, and navigation
- `src/main/resources/db/schema.sql` — MySQL tables and role permission seed data
- `src/main/resources/edu/pb/dcls/styles.css` — application styles

The project report and signed task distribution sheet are separate group deliverables.
