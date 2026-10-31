# Existing-feature verification checklist

Use this checklist to demonstrate the current application and record evidence.
The expected outcomes below are derived from source code; they are not claims
that the manual steps have already been performed.

## Preparation

1. Follow the README to start the in-memory demo on a disposable data set.
2. Note the branch and commit using `git branch --show-current` and
   `git log -1 --format=fuller`.
3. Record the run date, data mode, role, and any records created or changed.
4. Capture only non-sensitive sample data. Do not include passwords, database
   connection secrets, customer information, or the signed sheet in the repository.

## Manual checks

| ID | Steps | Expected outcome | Evidence to capture |
| --- | --- | --- | --- |
| M4-01 | Sign in as each demo role and compare the navigation. | Admin alone has Users & roles; Admin and Manager have Fleet; Driver has no Route planning. | Navigation screenshots labelled with role. |
| M4-02 | Open Dashboard as Admin. Count Assigned and In Transit records in Orders, then reopen Dashboard. | Active deliveries matches their combined count; Pending matches Orders to dispatch. | Orders status counts and Dashboard cards. |
| M4-03 | Compare Delivered records and their delivery dates with Completed today. | Only deliveries dated today count; older completions are excluded. | Relevant order details and card, with the local date recorded. |
| M4-04 | Sign in as Driver; compare Orders and Dashboard with that driver's assignments. | Other drivers' shipments are excluded. | Driver view and an Admin assignment view using sample records. |
| M4-05 | As Admin, inspect the service state and fuel/service history of a sample vehicle. | Reminder follows mileage thresholds; totals come from the selected vehicle's records. Efficiency is a dash when there is insufficient data. | Selected vehicle, history rows, and summary. |
| M4-06 | Create a disposable vehicle and edit its model or status. Search for it and change the status filter. | The matching record appears; the filter excludes non-matching statuses. | Before/after values and filtered list. |
| M4-07 | Create a disposable Pending order, then assign an available vehicle and active driver with sufficient capacity. | Assignment succeeds and the order becomes Assigned. | Order and vehicle status after assignment. |
| M4-08 | Attempt to assign a vehicle that is unavailable or below the shipment's required capacity. | Assignment is rejected with an explanatory message. | Validation message and sample input. |
| M4-09 | Advance the disposable Assigned order to In Transit and then Delivered. Reopen Dashboard. | Status advances; completion appears in today's count. Check the vehicle status after completion as well. | Status progression and resulting Dashboard. |
| M4-10 | Attempt cancellation of an Assigned/In Transit order and deletion of a Delivered order. | The UI disables the action or the service rejects it; records are retained. | Disabled action or validation message. |
| M4-11 | Try deleting a vehicle linked to an order or service history. | Deletion is blocked; linked history is retained. | Message and retained record. |
| M4-12 | Select an order and use Plan route or View vehicle tour where enabled. | The selected order/vehicle is handed to the existing destination screen. | Selected record and destination. |

Demo mode cannot prove database persistence. If checking persistence, use a
separate test MySQL database, record the data mode, change one disposable record,
restart the app with the same database settings, and verify that record remains.
Do not reset a shared database for this exercise.

## Existing automated checks

Run the current suites from the repository root:

```powershell
mvn '-Dtest=AppServicesTest,DashboardMetricsTest,OrdersSelectionTest,RepositoryParityTest' test
```

These suites already exist. Running them is verification work, not authorship of
their implementation. They cover service validation and transitions, dashboard
calculations, order selection behaviour, and repository behaviour. Check the
actual Surefire output for failures, errors, and skipped tests. In particular,
do not describe skipped checks as passed or infer a live MySQL test from demo
repository results. Reports are written under `target/surefire-reports/` and are
not committed build artifacts.

## Recording results and reflection evidence

For each manual check actually attempted, record:

| Check ID | Date / role / data mode | Actual result | Pass / fail / not run | Screenshot or issue reference |
| --- | --- | --- | --- | --- |
| | | | | |

Useful reflection evidence includes the branch commit URL, its file diff, the
commands run and their results, and screenshots from checks you performed.
The signed task distribution sheet establishes allocation, while a new commit
establishes only the changes recorded in that commit. Keep those distinctions
clear when describing your contribution.

A reflection can explain what you reviewed, which behaviour you verified, one
difficulty or mismatch you found, and what you learned. For example, distinguish
the proposed Reports wireframe from the operational summaries already present
in Dashboard and Fleet. Replace general statements with your own observations;
do not fill in results you have not obtained. Follow the course's AI-assistance
disclosure requirements when describing assisted work.
