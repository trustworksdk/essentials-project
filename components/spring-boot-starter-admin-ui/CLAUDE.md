## spring-boot-starter-admin-ui

Optional default admin UI: Thymeleaf shell + vanilla JavaScript, no Node anywhere.
Maven: `spring-boot-starter-admin-ui`. Reuses the artifactId of the removed Vaadin starter — same
coordinates, entirely new implementation.

Consumer-facing docs: `LLM/LLM-admin-api.md`. Visual language: `docs/openapi/admin-ui-mockup/`.

## Architecture

**The browser calls the admin API; the server renders only the shell.** There is no server-side path to
the `*Api` SPI beans in this module at all — the UI is a client of the published contract, exactly like any
other consumer. That is the point: it proves the contract is sufficient, and it makes a second
implementation that could drift impossible.

| Piece | Role |
|---|---|
| `AdminUiController` | Serves one Thymeleaf view. Puts the API base path and role flags in the model |
| `templates/essentials-admin/index.html` | Layout + role-gated nav. The entire server-rendered surface |
| `static/essentials-admin/admin.css` | Design tokens and components. Extracted from the mockup |
| `static/essentials-admin/admin.js` | Fetch layer, seven views, detail drawer, dialogs |
| `EssentialsAdminUiAutoConfiguration` | Gated on both `essentials.admin-ui.enabled` and `essentials.admin-api.enabled` |

## Test Structure

No Docker, no database.

| Test | Gates |
|---|---|
| `AdminUiContractParityTest` | Every contract path is called by `admin.js` **and** every path it calls is declared — both directions. Includes a count assertion so it cannot pass vacuously |
| `AutoConfigurationRegistrationTest` | The `.imports` file is at `META-INF/spring/…` and names a loadable class |
| `EssentialsAdminUiAutoConfigurationTest` | Bean wiring; `enabled=false`; backs off when the API is disabled |
| `AdminUiDemoApplication` | Not a test — a `main()` that runs the real UI against Mockito-stubbed SPIs, for driving and screenshotting |

## Gotchas

- **The parity gate reads `admin.js` statically**, so a path and its query string must appear *literally* in the `api(...)` call. Assembling a path from variables makes it unverifiable — which is the thing the gate exists to prevent. This bit once already: a computed `listPath` hid the dead-letter endpoint from the gate.
- **Static assets are served from `target/classes`.** Editing `src/main/resources` and restarting the demo app serves the *old* file — run `mvn test-compile` first. Cost an hour of chasing a CSS fix that was never deployed.
- **`min-width: 0` on `.main` is load-bearing.** It is a grid item, so its automatic minimum size is min-content; without it a wide table stretches the column past its track and the whole page scrolls horizontally, even though the table has its own scroll container. The same applies to `.tile` inside `.kpi-row`.
- **Role flags are presentation only.** They stop the UI offering an operation that would 403; authorization still happens in `EssentialsSecurityProvider` inside the SPI beans, where a hand-crafted request meets it too. Never treat the flags as a security boundary.
- **Nav items the caller cannot read stay visible but disabled**, with the required role in the tooltip — the UI explains the gap instead of silently hiding capability.
- Keep `admin.css` in step with the mockup; the mockup is where visual changes get reviewed before landing here.
