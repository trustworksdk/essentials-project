# `_customer_ui` - the customer's browser UI (phase 7)

Not a slice: it owns no events, commands or views, so it takes the `_` prefix and is left out of slice enumeration,
like `_demo_harness`. Design and decisions: `docs/design/phase-7-customer-ui.md`.

- `CustomerPages` returns Thymeleaf shells (`templates/customer_ui/`) and reads nothing. Each page's script
  (`static/customer_ui/`) fills it from the customer JSON API (`/api/accounts/**`, `/api/market-data/**`), the same
  contract the load driver uses. Never add a server-side read path here; a missing read belongs in the owning view slice.
- It imports no context code except `identity.types.AuthenticatedUser`, the caller's `UserId` that opening an account
  needs. The contexts are reached only over HTTP from the browser.
- **CSRF:** every page renders the session's token in `<meta name="_csrf">` / `<meta name="_csrf_header">`; `api.js`
  sends it on writes. A session-authenticated write to the customer API without it is a 403
  (`config/TradingDemoSecurityConfiguration`). The load driver (`BrokerageHttp`) reads the same tags from `/app`.
- **Live updates are polling while the page is visible** (`poll` in `api.js`): quotes 1 s, an order just placed 500 ms
  until terminal, a payment just requested 1 s until the gateway answers, the rest 5 s.
- Plain ES modules and one stylesheet, served as they are. No Node, no bundler, no JS framework (repository rule).
- Tests: `CustomerUiIT` (pages, security, CSRF both ways, instruments as a customer). The screens themselves are checked
  by hand in a browser against the running demo with the simulators.
