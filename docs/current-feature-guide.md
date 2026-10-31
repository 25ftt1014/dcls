# DCLS current feature guide

This guide describes the implemented application so the project report and a
demonstration can use the same terminology. It documents existing behaviour;
it does not establish authorship of the original implementation.

## Navigation and wireframe linkage

The implemented screens are Dashboard, Users & roles, Fleet, Orders, Route
planning, and Live tracking. The account menu provides profile and password
controls. Navigation is filtered by role through `AccessService.canOpen`.

| Role | Screens available |
| --- | --- |
| Admin | Dashboard, Users & roles, Fleet, Orders, Route planning, Live tracking |
| Manager | Dashboard, Fleet, Orders, Route planning, Live tracking |
| Dispatcher | Dashboard, Orders, Route planning, Live tracking |
| Driver | Dashboard, Orders, Live tracking; shipment lists are limited to that driver's assignments |

The earlier wireframes include Warehouse and Reports concepts. Neither is a
standalone page in this build. Fleet inventory means vehicle records, not
warehouse stock. Operational summaries are available on the Dashboard and in
Fleet vehicle history. Describe those existing summaries rather than claiming
that the proposed Reports screen was implemented.

Source: [navigation and shipment visibility](../src/main/java/edu/pb/dcls/DclsApp.java),
[role access](../src/main/java/edu/pb/dcls/AppServices.java).

## Dashboard summaries

Dashboard values are based on the records visible to the signed-in user. Drivers
see their own shipments; other roles see the repository's shipments. The cards
change with the role:

| Role | First three cards | Fourth card |
| --- | --- | --- |
| Admin / Manager | Vehicles, Active deliveries, Orders to dispatch | Completed today |
| Dispatcher | Orders to dispatch, Active deliveries, GPS alerts | Completed today |
| Driver | My deliveries, Assigned, In transit | Completed today |

The calculations have specific meanings:

- **Active deliveries / My deliveries:** shipments in Assigned or In Transit.
- **Orders to dispatch:** shipments in Pending.
- **Vehicles on the road:** vehicles whose stored status is In Delivery. This
  count is based on vehicle status, not on whether GPS detects movement.
- **Completed today:** shipments in Delivered with a non-null delivery timestamp
  on the computer's current local date. Older deliveries and records without a
  timestamp do not count.
- **GPS alerts:** In Transit shipments without a vehicle or a position fix, or
  whose latest fix is older than the 15-second threshold. Assigned shipments do
  not contribute to this particular alert count. It counts shipments, so it is
  not necessarily a count of distinct vehicles.
- **Order status:** counts of Pending, Assigned, In Transit, and Delivered.
  Cancelled is excluded. Bar lengths are scaled against the largest status count;
  they are not percentages of all orders.

The active-deliveries preview shows at most six shipments, ordered by ETA and
then creation time. Records without a fix sort last. The total active count can
therefore exceed the number of preview rows.

The operations map shows vehicles associated with active shipments. A vehicle
without a fix uses the depot as a placeholder, not as proof of its location.
The Dashboard is an operational overview, not an on-time-delivery or revenue
report. Reopen the page when checking it after an action on another screen; do
not treat its displayed timestamp as proof that an external database was polled.

Source: [DashboardView](../src/main/java/edu/pb/dcls/DashboardView.java),
[existing metric tests](../src/test/java/edu/pb/dcls/DashboardMetricsTest.java).

## Fleet records and summaries

Admins and Managers can open Fleet. The screen supports vehicle searches,
status filters, vehicle editing, maintenance and fuel records, and a selected
vehicle's history. Vehicle history displays the recorded service costs, fuel
litres, fuel costs, mileage, and next service point.

Service reminders use mileage rather than calendar dates:

| Condition | Service status |
| --- | --- |
| Current mileage is at or above next service mileage | Overdue |
| Current mileage is within 500 km below next service mileage | Due soon |
| Current mileage is more than 500 km below next service mileage | OK |

The displayed fuel-efficiency estimate is `(highest recorded mileage - lowest
recorded mileage) / total recorded litres` for that vehicle. At least two fuel
records, positive total litres, and an increasing mileage range are required;
otherwise the screen shows a dash. This is an estimate from the stored records,
not a measured trip-specific fuel-economy figure.

Vehicle deletion is rejected when shipments, maintenance records, or fuel
records still reference that vehicle. Use a disposable demo vehicle to show
deletion rather than removing operational history.

Source: [FleetView](../src/main/java/edu/pb/dcls/FleetView.java),
[FleetService](../src/main/java/edu/pb/dcls/AppServices.java).

## Orders and connected workflows

Admins, Managers, and Dispatchers can create and assign orders. The Orders
screen supports searching by order/customer details and filtering by status and
priority. Drivers see only their own assigned shipments and can update delivery
progress; they cannot dispatch other people's orders.

Assignment links a shipment with its driver and vehicle. The order service checks
vehicle availability and shipment weight against capacity. A normal delivery
progresses through Pending, Assigned, In Transit, and Delivered. Assignment is
required before a Pending order can progress, and completing delivery releases
the vehicle according to the order service's rules.

Cancellation is limited to Pending orders. Active and Delivered orders are
retained by the deletion rules. In Orders, Plan route passes the selected order
to Route planning, while View vehicle tour opens Live tracking for its assigned
vehicle. These are links to existing modules, not separate reporting features.

Source: [OrdersView](../src/main/java/edu/pb/dcls/OrdersView.java),
[OrderService](../src/main/java/edu/pb/dcls/AppServices.java),
[existing service tests](../src/test/java/edu/pb/dcls/AppServicesTest.java).

## Data and export boundaries

Normal startup uses MySQL through the JDBC repository. The explicit demo mode
uses in-memory sample records that reset when the process closes. The cached
repository supplies UI snapshots and delegates writes to the selected backend.
Use MySQL, rather than demo mode, when demonstrating persistence after restart.

Live tracking has an existing GPX, CSV, and KML track export. It exports recorded
vehicle positions, not a delivery-performance report or a warehouse inventory
report. There is no new reporting export introduced by this documentation.

Source: [repository selection and track export](../src/main/java/edu/pb/dcls/DclsApp.java),
[CachedRepository](../src/main/java/edu/pb/dcls/CachedRepository.java).
