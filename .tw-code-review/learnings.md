# Learnings

Active learnings applied by the reviewer. Each entry was added via
`/tw-code-review:teach` or `/tw-code-review:resolve`.

## L-001 — don't flag todo in essentials-plugin/** — TODOs there are template placeholders …

don't flag todo in essentials-plugin/** — TODOs there are template placeholders the slice skills fill, their rendered goldens, or prose about that mechanism

_Added 2026-09-30; origin: teach_

## L-002 — don't flag print-call in .github/workflows/** — stdout is the output there

don't flag print-call in .github/workflows/** — stdout is the output there

_Added 2026-09-30; origin: teach_

## L-003 — don't flag print-call in essentials-plugin/scripts/** — stdout is the output the…

don't flag print-call in essentials-plugin/scripts/** — stdout is the output there

_Added 2026-09-30; origin: teach_

## D-feat-slice-atlas-F-003 — XXXXXX in a mktemp template is the placeholder pattern, not a hygiene marker

XXXXXX in a mktemp template is the placeholder pattern, not a hygiene marker

_Added 2026-10-11; origin: resolve:F-003_

## D-feat-slice-atlas-F-090 — TODO is the name of the marker constant prepare-release.py writes into the notes…

TODO is the name of the marker constant prepare-release.py writes into the notes and CHANGELOG on purpose; --check refuses to pass until it is filled in. Not a leftover TODO comment.

_Added 2026-10-13; origin: resolve:F-090_

## D-feat-slice-atlas-F-285 — Not a defect: The cascade cannot happen. ShardRuntime.SHARED is an IdentityHashM…

Not a defect: The cascade cannot happen. ShardRuntime.SHARED is an IdentityHashMap keyed by the DataSource instance, and ShardOwnedLifecycleIT builds a brand-new HikariDataSource in @BeforeEach and closes it in @AfterEach. A borrow leaked by this test leaves its stopped runtime mapped under a DataSource no later test ever passes to acquireShared, so no later test can get a stopped runtime. What is left is one stale map entry per regression in a test JVM, and only when the test is already failing.

_Added 2026-10-16; origin: resolve:F-285_

## D-feat-slice-atlas-F-354 — Not a defect: the reference comparison is the intended semantics. Run is a priva…

Not a defect: the reference comparison is the intended semantics. Run is a private class with no equals/hashCode whose identity is the object (javadoc 'Why the run's identity'); equals() would be the same identity test.

_Added 2026-10-17; origin: resolve:F-354_

## D-feat-slice-atlas-F-491 — Not a defect: tryLock(STOP_WAITS_FOR_START) returns as soon as the lock is relea…

Not a defect: tryLock(STOP_WAITS_FOR_START) returns as soon as the lock is released; the full 2 s is spent only when another holder outlasts it, which is the documented bounded wait (javadoc lines 239-240).

_Added 2026-10-18; origin: resolve:F-491_

## D-feat-slice-atlas-F-574 — Not a defect: next() result is checked via assertThat(...).isTrue() naming the b…

Not a defect: next() result is checked via assertThat(...).isTrue() naming the backend; PMD CheckResultSet does not see an AssertJ assertion as a use

_Added 2026-10-20; origin: resolve:F-574_

## D-feat-slice-atlas-F-650 — Not a defect: The only concatenated identifier is `table`, and the method reject…

Not a defect: The only concatenated identifier is `table`, and the method rejects any value other than the two ShardOwnedSchema constants before the SQL is built. The alias "o" is a literal, and ownedByClause concatenates only LEASE_TABLE and that alias. Every value is bound with set*. SpotBugs cannot see the guard, but injection is impossible.

_Added 2026-10-22; origin: resolve:F-650_

## D-feat-slice-atlas-F-693 — Not a defect: The IT is a scaffold stub: the autowired repository is the handle …

Not a defect: The IT is a scaffold stub: the autowired repository is the handle the three TODO bodies tell the user to assert against (assert the row, assert applied once, assert the store is cleared). It comes verbatim from the template, and the golden must byte-match it. Removing it would strip the scaffold of the thing the bodies are meant to use.

_Added 2026-10-22; origin: resolve:F-693_

## D-feat-slice-atlas-F-737 — Not a defect: The 500 is the catalog's stated contract, not an accident the test…

Not a defect: The 500 is the catalog's stated contract, not an accident the test invents. behaviours.json:146 marks java/view_service_entity view.query-by-id implemented with the summary "an unknown id throws NoSuchElementException, which no handler maps to 404". The IT's class javadoc says each test asserts one implemented entry against the rendered, untouched skeleton, so that the implemented entries stay honest. A template change that maps the exception to 404 must change that summary too, and this test failing is how the harness forces the two to move together. Loosening it to isNotEqualTo(404) would also accept 200 or 400 and stop checking the stated behaviour.

_Added 2026-10-22; origin: resolve:F-737_

## D-feat-slice-atlas-F-806 — Not a defect: Both printlns are inside `if (Boolean.getBoolean("openapi.regenera…

Not a defect: Both printlns are inside `if (Boolean.getBoolean("openapi.regenerate"))`, the opt-in regenerate mode a developer runs by hand with -Dopenapi.regenerate=true. They do not run on every test run. They are the feedback that regenerate run exists to give (whether it rewrote the spec, and where), and that branch returns before any assertion, so they stand in for no assertion. Moving them to SLF4J debug would hide that feedback from the developer who asked for it.

_Added 2026-10-25; origin: resolve:F-806_

## D-feat-slice-atlas-F-808 — Not a defect: The executor created at line 81 is shut down in the outer `finally…

Not a defect: The executor created at line 81 is shut down in the outer `finally` on every path (`executor.shutdownNow()`). The inner `finally` rolls back the writer and cancels the reapply first, so the one submitted task cannot keep the thread alive. PMD cannot see a shutdown in `finally` as a close.

_Added 2026-10-25; origin: resolve:F-808_

## D-feat-slice-atlas-F-810 — Not a defect: Line 195 is the `try {` around the backoff Thread.sleep and has no…

Not a defect: Line 195 is the `try {` around the backoff Thread.sleep and has no SQL. The applier's only Statement.execute is in execute() at line 244, and running raw DDL there is the class's contract: the statements are the schema changes the EssentialsSchemaContributors describe (framework or application code, not request data). DDL cannot take bind parameters, and they go straight to JDBC on purpose so PL/pgSQL bodies and `::` casts are not parsed as Jdbi parameters (javadoc at 238-241). Every objectName is checked with PostgresqlUtil.checkIsValidTableOrColumnName before anything runs (line 167). The build has no SpotBugs and no suppression convention to apply.

_Added 2026-10-25; origin: resolve:F-810_

## D-feat-slice-atlas-F-831 — Not a defect: `method` is the java.lang.reflect.Method the proxy invocation hand…

Not a defect: `method` is the java.lang.reflect.Method the proxy invocation handler is given for a call on the interface. A reflected method always has a non-null name, so `method.getName().equals(...)` cannot throw an NPE. Putting the literal first protects against nothing here.

_Added 2026-10-25; origin: resolve:F-831_

## D-feat-slice-atlas-F-838 — Not a defect: The pattern cannot backtrack catastrophically. It has a literal '$…

Not a defect: The pattern cannot backtrack catastrophically. It has a literal '$', then one optional group made of one required character from [A-Za-z_] followed by [A-Za-z0-9_]*, then a literal '$'. There is no nested or overlapping quantifier, and the only quantified run cannot match '$', so the attempt is linear in the length of the run. It is also only ever used with lookingAt() from a region starting at a '$' that startsDollarQuote has accepted, never with find() over the whole text. The spotbugs REDOS heuristic flags the shape `(X Y*)?`, not real ambiguity. A @SuppressFBWarnings would add a spotbugs-annotations dependency that the module does not have (no file in the repository uses it, and spotbugs is not in any pom - only the review tool runs it), so a suppression is not worth adding.

_Added 2026-10-25; origin: resolve:F-838_

## D-feat-slice-atlas-F-872 — Not a defect: On a processor's first start, `inboxes.getOrCreateInbox(config, in…

Not a defect: On a processor's first start, `inboxes.getOrCreateInbox(config, inboxMessageHandlerDelegate)` creates the Inbox with this processor's consumer and starts consuming in its constructor. So hasAMessageConsumer() is already true, and that consumption is this start's own. That is the case the pinned test models. stop() always calls inbox.stopConsuming() in a finally, so a restarted processor finds no consumer and takes the consume() branch. Assigning consumingInbox only inside that branch, as proposed, would leave a failed first start consuming with nothing subscribed. The only way a consumer could belong to someone else is a second processor or component with the same InboxName, which is a misconfiguration that start() cannot detect anyway.

_Added 2026-10-26; origin: resolve:F-872_

## D-feat-slice-atlas-F-932 — Not a defect: PMD UnusedAssignment premise is false - eventSubscriberAction is a…

Not a defect: PMD UnusedAssignment premise is false - eventSubscriberAction is a volatile field read on the confirmation thread by the sync subscriber; the test waits for that read's effect before the overwrite.

_Added 2026-10-26; origin: resolve:F-932_

## L-006 — Don't flag detekt PackageNaming in essentials-plugin/**: snake_case slice-id pac…

Don't flag detekt PackageNaming in essentials-plugin/**: snake_case slice-id package segments (use_cases.place_order, views.order_list) are the slice layout the plugin publishes as a contract (sdd-contract.md §5); hyphens are illegal in JVM package directories, and the goldens are byte-exact render-slice.py output.

_Added 2026-10-27; origin: teach_

## L-007 — Don't flag PMD CloseResource in test code: the tests shut their executors down w…

Don't flag PMD CloseResource in test code: the tests shut their executors down with shutdown()/shutdownNow() in a finally on every path, which PMD does not count as a close.

_Added 2026-10-27; origin: teach_

## L-008 — Don't flag PMD CompareObjectsWithEquals in test code: the tests compare Thread o…

Don't flag PMD CompareObjectsWithEquals in test code: the tests compare Thread objects, proxies and private run objects by identity on purpose; those types define no value equality, so equals() would mean the same and say it less plainly.

_Added 2026-10-27; origin: teach_
