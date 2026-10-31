# Verification record - 31 October 2026

## Change being checked

Documentation of the existing role access, Fleet and Orders workflows, dashboard
summaries, and wireframe differences, plus a manual demonstration checklist.
The README's reference to tracking simulation was corrected to GPS tracking
services. No production Java, schema, or test implementation was changed.

This review and test run was performed with Codex assistance on `sabiq-part`,
starting from commit `4e21ca4ed58997ae0bb6f28dd4360241ccf5bb45`.
It records verification of existing code, not authorship of that code or its tests.

## Environment issue and resolution

The first Maven invocation could not access its dependency-cache lock files in
the restricted execution environment. After allowing Maven cache access, the
test process selected Java 17 and stopped before executing tests: the existing
classes target Java 21 (class-file version 65), while Java 17 supports only
version 61. Neither attempt counted as a passing test run.

The installed JDK 26 was then selected for the command process only. System
settings and project requirements were not changed. The successful invocation
used:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-26'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
mvn '-Dtest=AppServicesTest,DashboardMetricsTest,OrdersSelectionTest,RepositoryParityTest' test
```

## Observed automated results

The Maven run finished at 16:06:31, UTC+08:00, on 31 October 2026, with
`BUILD SUCCESS` and the following results:

| Existing suite | Tests | Failures | Errors | Skipped |
| --- | ---: | ---: | ---: | ---: |
| AppServicesTest nested suites (Access, Auth, Fleet, Orders, Routes) | 50 | 0 | 0 | 0 |
| DashboardMetricsTest | 12 | 0 | 0 | 0 |
| OrdersSelectionTest | 1 | 0 | 0 | 0 |
| RepositoryParityTest | 3 | 0 | 0 | 0 |
| **Total** | **66** | **0** | **0** | **0** |

The run emitted JavaFX unnamed-module and native-access warnings. They did not
fail the selected tests. Maven used the existing compiled classes and reported
that nothing needed compiling; this was a test run, not a clean rebuild.

`git diff --check` passed, and all local Markdown links in the changed README
and documentation resolved to existing files.

## Limits and follow-up

- The selected suites verify existing service rules, dashboard calculations,
  Orders selection, and repository guards. They are not the full test suite.
- No live MySQL persistence test or manual end-to-end demonstration was run for
  this change. RepositoryParityTest uses the demo repository; it does not prove
  a MySQL connection works.
- The manual checklist is supplied for later use. Its expected outcomes are not
  completed manual test results or screenshots.
- No Warehouse or standalone Reports screen was added. The documentation
  distinguishes those wireframe concepts from the implemented application.
- For an individual reflection, cite the actual documentation diff and this
  assisted verification run. Add personal observations and screenshots only
  after performing the corresponding checks yourself.
