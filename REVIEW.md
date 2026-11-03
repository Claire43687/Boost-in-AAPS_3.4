# REVIEW.md

The checks a change to this repository is reviewed against before it is committed. Each item is
written so that a reviewer can test a diff against it and name the item a finding breaks. Findings
are labelled important (fix before commit), nit (optional) or pre-existing (present before this
change; record it, do not block on it).

CLAUDE.md here is upstream's working guide with additions. The table at the end maps each correction
found in this work to what now catches it.

## What this repository is

Boost on upstream AndroidAPS 4.0.0. Boost is one selectable plugin: the V6 observe, confirm and commit
pipeline on the engine (`DetermineBasalBoost`, the ML risk and meal models, sleep and step tracking),
with every SMB outside the meal pipeline sized by the two oref tiers. The other six tiers decide only
the Boost high temp and the fast-carb scale. Dependency injection is Metro rather than Dagger, the project is Kotlin
Multiplatform, and the Boost engine sits in `plugins/aps/src/androidMain` with its tests in
`androidHostTest`.

## Tiers in this repository

High: anything that can change a dose, a safety limit or whether the loop runs.

| Area | Paths |
|---|---|
| Boost and upstream APS engines, the loop | `plugins/aps/**` (`openAPSBoost`, `openAPSBoostV5`, `loop/LoopPlugin.kt`, the SMB, AMA and AutoISF engines, `GlucoseStatusCalculatorSMB.kt`, the LightGBM model assets) |
| When the loop runs | `workflow/**` (`CalculationLoopTrigger.kt`, `PostCalculationRunner`, `PrepareGraphDataRunner`) |
| Glucose and IOB inputs | `plugins/main/**/iob/**` (`AutosensDataStoreObject`), `plugins/smoothing/**`, `plugins/sensitivity/**`, `plugins/source/**`, `plugins/calibration/**`, `core/data/**/model/ICfg.kt` and `InsulinModel.kt` (the insulin curve) |
| Limits and constraints | `plugins/constraints/**` (including `VersionCheckerPlugin`), `core/interfaces/**/utils/HardLimits.kt`, the Boost `BOOST_LIMIT_*` ranges, `core/keys/**` where a default changes dosing |
| Interfaces the engine writes through | `core/interfaces/**/aps/**` (including `RT.kt` and `AutosensDataStore.kt`) |
| Data that feeds the sleep detector or history | the Garmin `/hr` and `/steps` endpoints and `LoopHub` storage in `plugins/sync`, the NSClientV3 history backfill, the wear `HeartRateListener`, the Health Connect ingest |
| Persistence and drivers | `database/**` (schema and migrations), `pump/**` |
| Wiring | any `@SingleIn`, `@Inject`, `@AssistedInject` or plugin-map change touching the above, and `MetroGraphs` |

Before commit at this tier: the CI unit suite with `--continue` (item 17), the guard tests named
below, the checklist, a replay against real history when engine behaviour changes, `iosSimulatorArm64Test` on the Mac when `commonMain` changed, and a
fresh-context review of the diff. Before an APK is used: a clean committed tree, the build with its
exit code captured, a launch on the emulator checked for `VerifyError` and `FATAL EXCEPTION`, a
soak when dosing behaviour changes, and the previous APK named as
the way back. A build that raises `DATABASE_VERSION` has no in-place way back, since installing an
older build over it triggers Room's destructive fallback and drops the database; the way back for
such a build is stated before it is installed.

Medium: user interface (the Boost V2 overview, preference screens, the widget), wear complications
and data sent to the watch, telemetry, background services. Unit tests where they exist and the
checklist; the emulator launch when it ships in an APK.

Low: `_docs/`, this file and other documents. The items below on personal data.

## Dosing code and builds

1. Nothing sits between an annotation and the declaration it belongs to. `@SingleIn(AppScope::class)`
   stays directly above `open class OpenAPSBoostPlugin` and above `OpenAPSBoostV5Plugin`. Guard:
   `BoostEngineScopeTest`.
2. A collaborator shared between a writer and a reader is injected as `kotlin.Lazy`, not
   `Provider`; `OpenAPSBoostV5Plugin` holds the engine as `Lazy<OpenAPSBoostPlugin>`. Guard:
   `BoostEngineScopeTest`.
3. `RT` gains no field. New telemetry goes into the reason string as `tag=value; ` and is parsed
   downstream. `DetermineBasalBoost.determine_basal` constructs `RT` twice,
   so each field costs it a register. Guard: none in this repository. The comment in `RT.kt` names `DetermineBasalBoostV3MLG3`,
   which this port does not contain; the method at risk here is `DetermineBasalBoost.determine_basal`.
4. Nothing is added to `DetermineBasalBoost.determine_basal`; new logic goes in a separate function
   that it calls. Guard: an emulator launch only, since the register limit fails at ART
   verification and no unit test loads the class on ART.
5. A change to Boost dosing behaviour is named in its commit message with the reason.
6. A change to a hard limit, a constraint or a Boost range states the previous value, the upstream
   value and the new one, and checks that profile validation, the profile editor and the loop
   accept the same range. A limit loosened is important.
7. Anything ordered by position grows at the end: `EventData.Status` fields (the watch stores it as
   ProtoBuf, numbered by declaration order), `NotificationId` entries (upstream ordinals stay
   fixed), constructor and data-class parameters. A new plugin order key does not collide with an
   upstream one (230 is AutoISF; Boost is 232 and Boost V6 236). A new parameter gets a real
   Mockito matcher, preferably `eq(<default>)`.
8. A schema change carries a migration, a default that records what existing rows were computed
   with, and a migration test of the `Migration36To37Test` form that fails when the default is
   changed. `DATABASE_VERSION` never goes down.
9. A key added to JSON or to the Nightscout objects is omitted at its default value and read
   leniently when missing, so older readers and older stored data are unaffected.
10. Code that reads a glucose series names which one it reads: the five-minute `bucketedData` that
    the sensitivity chain indexes by position, or the native-cadence series. A change here is
    tested on a one-minute feed and on a five-minute feed, and five-minute behaviour is shown to be
    unchanged.
11. Insulin amounts are stored in IU at every concentration in 4.0, where 3.4 stored pump units.
    Any formula from 3.4 that scales by concentration is re-derived for IU, with a test that
    the same physical dose gives the same result at U100 and U200.
12. A value shown or entered in glucose units reads the unit from `ProfileUtil`, never infers it
    from a number, and is tested in mmol/L. A preference key's `UnitType` matches its value type
    (`PERCENT` is for Int keys).
13. A new or renamed key appears in `prefs-schema.txt`, keeps its stored name so that a
    3.4 export imports it, and a rename is grepped case-insensitively. Golden lists:
    `GraphScopeStabilityTest`, `prefs-schema.txt`, `PluginLifetimeWorkScanTest`.
14. A test that reads fixtures or scenario files fails or reports skipped when they are absent, and
    never passes on zero cases. A skipped backtest is not a pass. A test added for a fix is shown to
    fail without the fix.
15. A test that sends an event on a `SharedFlow` without replay waits for the collector, and a
    coroutine test does not call `advanceUntilIdle()` on a scheduler that re-arms itself.
16. The loop runs in `CalculationLoopTrigger`, after IOB and COB are published and before the
    graph-only data. An upstream merge that changes the loop start moves its change there, with its
    tests. Guard: the order test in `CalculationLoopTriggerTest`.
17. Gradle runs on a clean, committed tree with `--no-daemon`, one build at a time, and its exit
    code is captured by redirecting to a log (`./gradlew ... > log 2>&1; echo $?`), never through
    a pipe. `BoostBuildInfo` stamps the branch and commit hash, so a dirty build would report
    a hash whose code it does not contain.
18. A change to `commonMain` code is run under `iosSimulatorArm64Test` on the Mac before it is
    described as tested on iOS. An interface whose implementation does nothing on a target is
    reported, since a feature that silently stops on one platform is a safety problem here.
19. A mechanism described in a comment, commit message or reply has been read in the function, not
    inferred from logs or behaviour.
20. A soak or field log is read for outcomes: the `OpenAPSBoostPlugin.runEngine` "Result:" lines,
    what was enacted and the count of `NO APS SELECTED OR PROVIDED RESULT`. `LoopPlugin`'s "invoke
    end" is absent from some runs and is not counted on.

## Repository and publishing

21. The branch's upstream is read before any push (`git rev-parse --abbrev-ref @{u}`). Nothing is
    pushed to nightscout/AndroidAPS from a checkout of this repository.
22. Files are staged by explicit path. `git add -A` and `git add <directory>` are not used; the
    working tree can carry untracked tool directories that stay out of commits.
23. Personal data never enters the tree: backtest fixtures, soak output, decrypted settings exports
    and Nightscout tokens. Comments, tests and commit messages carry no person's glucose, doses, times
    or location.
24. An upstream merge lists its conflicts and their resolution in the merge commit, updates the
    upstream inventory tests for Boost, and is followed by the full CI unit suite.

## Incident record and what catches each

| Incident | Caught by |
|---|---|
| `@Singleton` drifted off the engine class and the loop discarded every result for five days (on 3.4, before the port) | Items 1 and 2, `BoostEngineScopeTest` |
| An `RT` field crashed the app at start (on 3.4) | Item 3; nothing in this repository yet. |
| Two APKs failed ART verification after code was added to `determine_basal` (on 3.4) | Item 4; nothing in this repository yet, no launch-check script here |
| July port had no BOOST result mapping, so saving a Boost result threw | Item 5; no test pins it |
| July port had lost the `runEngine` seam | Item 5 |
| Five Double keys typed `PERCENT` crashed three settings screens | Item 12; nothing yet |
| Night-mode offset unit inferred from its minimum, so mmol/L input was stored wrongly | Item 12; nothing yet |
| Overview glucose circle labelled mg/dL for every mmol/L reading | Item 12; nothing yet |
| Nested preference sub-screens drawn empty, hiding every Boost engine group | Nothing yet |
| Boost profile missing from the APS screen | `OpenAPSViewModelTest` |
| Expired-version check set max IOB to 0, which would stop Boost dosing on a beta | Item 6, `VersionCheckerPluginTest` |
| Lyumjev peak-time dose swapped between U100 and U200 since 2022 | Item 11, `LyumjevPdCurveTest` |
| A shared native-series reference would have left deltas unsmoothed on five-minute feeds | Item 10, `AutosensDataStoreTest` |
| Without the spacing rule, 120 one-minute readings gave 120 decisions | Item 10, `LoopTriggerCadenceTest` |
| UKF copied raw values through on a one-minute series | Item 10, `UnscentedKalmanFilterPluginTest` |
| A reading waited for the graph-only data before reaching the loop | Item 16, `CalculationLoopTriggerTest` |
| `ExerciseRegressionTest` returned no cases when its directory was missing | Item 14 |
| `CalibrationBlockTest` relied on synchronous Rx delivery | Item 15 |
| Overview insulin divisor read the empty `oapsProfile` | Item 19; nothing automated |
| Boost complications lagged the face by up to one 300 s period | Nothing yet |
| `LoopPlugin` "invoke end" absent from some soak runs | Item 20 |
| Three red CI rounds for one commit without `--continue` (CLAUDE.md) | Item 17 |
| Work shipped as "compiles for iOS" when it could have been run (CLAUDE.md) | Item 18 |
| A dead function hid a lost caller in the Bluetooth device picker (CLAUDE.md) | Item 19 |
| A piped gradle reported `tail`'s exit code (CLAUDE.md) | Item 17 |
| Insulin-need watch ran during a history download, and `TddCalculator` kept the partial days it computed for auto-config to read | Nothing automated; the `historyDownloadHolds` check in `scheduleConfigEvaluation` |

A new correction in this repository adds a row here naming its check, or "nothing yet".
