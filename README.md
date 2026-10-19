# DCLS — Delivery Logistics Simulator

Java 25 (LTS) and JavaFX desktop application for the DCLS mini project. MySQL is the normal data mode so app changes survive restarts. The in-memory classroom demo is available by explicit opt-in.

## Run the app with MySQL

Requirements: Java 25 (JDK), Maven 3.9 or later, and a running MySQL database initialized with `src\main\resources\db\schema.sql`.

Set the database connection variables in PowerShell, then start the app:

```powershell
$env:DCLS_DB_URL = 'jdbc:mysql://localhost:3306/dcls'
$env:DCLS_DB_USER = 'dcls_app'
$env:DCLS_DB_PASSWORD = 'choose-a-strong-password'
mvn javafx:run
```

If the database settings are missing or the connection fails, startup shows an error instead of silently using temporary data. (Note: double-clicking `run-demo.bat` instead defaults to demo mode unless `DCLS_DB_URL` is set.)

## Run the in-memory demo

Set the explicit demo flag in PowerShell, then start the app:

```powershell
$env:DCLS_DEMO_MODE = 'true'
mvn javafx:run
```

Sign in with:

- Admin: `admin@dcls.local` / `demo1234`
- Manager: `manager@dcls.local` / `demo1234`
- Dispatcher: `dispatcher@dcls.local` / `demo1234`
- Driver: `driver@dcls.local` / `demo1234`

The sign-in screen opens with the Admin demo credentials filled in. Demo data resets when the app closes. Clear `DCLS_DEMO_MODE` to return to MySQL mode.

Map data © OpenStreetMap contributors: live OSM Standard tiles (zoom 3–20) with a 90-pair road-network overlay; WiFi-first real-time GPS — no simulation. Tiles cache under `~/.dcls/tiles` for transient blips only. No map keys needed.

Live GPS feeds (Live Tracking → feed selector):
- NMEA: import `$GPGGA/$GPRMC` `.nmea/.txt` logs (checksum-validated). Live TCP streaming is planned, not implemented — there is no `DCLS_NMEA_HOST`/`DCLS_NMEA_PORT` support yet.
- Driver mobile feed: poll JSON with `DCLS_MOBILE_FEED_URL='https://your-server/feed'` returning `[{vehicleId,lon,lat,speed,heading,accuracy,ts}]`.
- GPX: import any GPX 1.1 track; export GPX/CSV/KML from the same screen.
A vehicle shows STALE when no fix arrives for >15s; off-route >0.3km triggers an automatic re-route suggestion.

Route geometry uses live OSRM routing (`router.project-osrm.org`, OSM data, no key) with the bundled road network as the offline fallback; OSRM legs cache under `~/.dcls/osrm`. To point at a self-hosted OSRM instead:

```powershell
$env:DCLS_OSRM_URL = 'https://your-osrm-host'
```

Maps work with no key: the app uses OSM Standard tiles across every screen.

## Demo flow

1. Sign in as Admin and review the dashboard totals.
2. Open Fleet; search by plate, model, type, or driver and change a vehicle status.
3. Open Orders; filter/search, assign the pending sample shipment, and advance an assigned order.
4. Open Live Tracking. Import a GPX/NMEA sample or set `DCLS_MOBILE_FEED_URL` and press Poll — markers move only on real fixes.
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

Java 25 or later must be installed on the computer running the JAR. JavaFX runtime libraries are included in `lib` for Windows builds.

## MySQL setup

The app uses the JDBC repository by default. To set up a local MySQL database:

1. Install and start MySQL Server.
2. Run `src\main\resources\db\schema.sql` in a MySQL client. The script creates the `dcls` database and its tables.
3. Give the app its own database user instead of `root`, so a leaked password cannot reach the rest of the server. Run this once as an administrative account, choosing your own password:

```sql
CREATE USER 'dcls_app'@'localhost' IDENTIFIED BY 'choose-a-strong-password';
GRANT SELECT, INSERT, UPDATE, DELETE ON dcls.* TO 'dcls_app'@'localhost';
FLUSH PRIVILEGES;
```

4. Set the connection variables in the PowerShell session, then start the app:

```powershell
$env:DCLS_DB_URL = 'jdbc:mysql://localhost:3306/dcls'
$env:DCLS_DB_USER = 'dcls_app'
$env:DCLS_DB_PASSWORD = 'choose-a-strong-password'
mvn javafx:run
```

On an empty database, the first account registered becomes its administrator. Later self-registered accounts must be approved by an administrator. MySQL mode does not insert demo users or sample business records. Keep real database credentials out of source control. Import the schema before starting the app; `schema.sql` needs an administrative account, while the app account only needs permission to read and write rows.

### Setting up on a new machine

Every developer runs their own local MySQL server. Nothing is shared over the network, so there is no port to open or credentials to distribute. Each person initializes an empty database and creates its first administrator through the app.

`mysql.exe` is usually not on `PATH`. Use MySQL Workbench (**File → Open SQL Script**, pick `src\main\resources\db\schema.sql`, then run), or the full path:

```powershell
$mysql = "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe"
Get-Content src\main\resources\db\schema.sql -Raw | & $mysql -u root -p
```

`schema.sql` is safe to re-run: every statement is `CREATE ... IF NOT EXISTS` or `INSERT IGNORE`, so it never duplicates or overwrites existing rows. On a machine that already has an older copy of `dcls`, drop it first if you need the foreign-key rules re-applied — `CREATE TABLE IF NOT EXISTS` skips tables that already exist, so edits to the schema are not picked up otherwise:

```powershell
& $mysql -u root -p -e "DROP DATABASE IF EXISTS dcls;"
```

Then create your own `dcls_app` user as shown in step 3 above, set the three variables, and run `mvn javafx:run`.

Because each developer has a separate copy, two people editing data at the same time will not see each other's changes. That is intentional — it avoids conflicting rows with no way to merge them. Share data by exporting and importing rather than by pointing two machines at one server.

## Source layout

- `Domain.java` — shared entities, roles, statuses, and repository contract
- `AppServices.java` — authentication, role access, fleet/order rules, route estimates, and tracking simulation
- `MockRepository.java` — classroom demo data
- `JdbcRepository.java` — MySQL persistence through JDBC
- `DclsApp.java` — JavaFX views, dialogs, tables, filters, and navigation
- `src/main/resources/db/schema.sql` — MySQL tables and role permission seed data
- `src/main/resources/edu/pb/dcls/styles.css` — application styles

The project report and signed task distribution sheet are separate group deliverables.
