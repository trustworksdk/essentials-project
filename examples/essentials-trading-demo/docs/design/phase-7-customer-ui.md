# Phase 7 design — customer UI

**Status:** decided and built (2026-11-04) - every proposal accepted, Q-5 narrowed while recording it (§5), two
small departures while building (§7, §8). Decisions are marked
**(Q-n)** in the text and collected in §9.
**Plan:** [`../EXPANSION_PLAN.md`](../EXPANSION_PLAN.md) phase 7 ("Portfolio, order ticket, funding"); decision D12
(Thymeleaf + vanilla JS, per the repository rule: no Node, no bundler, no JS framework).
**Builds on:** the customer HTTP API of phases 1 to 4, which the phase 6 load driver already exercises end to end.

Until now a customer is an HTTP client: the load driver, `curl`, or the `.http` files. Phase 7 gives them a browser
UI over the same API. It is a demo UI - it has to show the system working (an order going from placed to filled to
booked, cash moving from pending to settled) - not a product. Nothing in the domain changes.

---

## 1. What exists

Everything the three screens need is already a customer endpoint under `/api/accounts` (customer or operator role,
ownership checked per account by `AccountOwners`):

| Screen | Reads | Writes |
|---|---|---|
| Portfolio | `GET /api/accounts` (my accounts: cash buckets, buying power, withdrawable), `/{id}/valuation`, `/{id}/positions`, `/{id}/activity` | `POST /api/accounts` (open an account) |
| Order ticket and orders | `GET /api/market-data/quotes`, `/{id}/orders`, `/{id}/orders/{orderId}` | `POST /{id}/orders`, `POST /{id}/orders/{orderId}/cancellation` |
| Funding | `GET /{id}/payments`, `/{id}/payments/{reference}` | `PUT /{id}/bank-account`, `POST /{id}/deposits`, `POST /{id}/withdrawals` |

Login is Spring Security's form login (`/login`, Spring's generated page) plus HTTP Basic for API clients; users are
seeded from `application.yml` (`DemoUsersSeeder`). The application has no templates of its own; Thymeleaf is on the
classpath for the admin UI starter's page.

Two gaps:

- **Instrument names are operator-only.** `InstrumentDetailsAPI` is mapped at `/api/admin/instruments`. The quotes a
  customer can read carry only an `InstrumentId`. An order ticket needs the list of tradable instruments, their names
  and whether they are suspended.
- **CSRF is off for `/api/**`.** That is right for Basic-authenticated API clients, but a browser holding a session
  cookie and calling the same `POST`s from page script would be open to cross-site request forgery. §5.

## 2. How pages are built (Q-1)

**Proposed: Thymeleaf renders each page's shell; vanilla JS fills it from the existing JSON API with `fetch`.** The
server side serves templates and nothing else - no new read paths, no view models of its own. The JSON API stays the
one contract, the one the load driver measures, and the UI cannot drift from it.

The alternative, server-side rendering from the views (a controller reads `AccountOverview` and hands it to the
template), gives pages that work without script, but every screen then needs a second read path beside the API, and
every action a form post plus redirect, which is clumsy for an order that changes status a second after it is placed.

Static assets (one stylesheet, one small JS module per page plus a shared `api.js` for `fetch`, JSON and errors) are
served from `static/`, not built.

## 3. Screens (Q-2)

Under `/app`, after login; `/` redirects there. The account is picked in a header select when the user has more than
one, and remembered in the URL (`/app/accounts/{accountId}/...`).

1. **Portfolio** - cash buckets (settled, pending in and out, held, uncleared), buying power, withdrawable; positions
   with the last closing-price valuation and its business date; recent activity. With no account yet: an "open
   account" button.
2. **Trade** - the order ticket (instrument, side, market or limit, quantity, limit price, time in force) beside the
   instrument's live quote, and the account's orders below it, open ones first, each cancellable while open. After a
   placement the new order's row follows its status until it is terminal (§4).
3. **Funding** - the linked bank account (link or change it), deposit and withdrawal forms, and the payments list with
   each payment's status.

Login gets a page of the demo's own (Q-3) only if Spring's generated one is in the way; proposed: keep Spring's for
now. Self-registration is not in scope - users stay seeded (`/api/admin/users` is operator-only and stays so).

## 4. Seeing things change (Q-4)

**Proposed: polling.** While a page is visible it re-reads what can change: quotes every second on the trade page, an
order just placed every 500 ms until terminal, the rest every 5 s. It costs a few reads per open tab, already the
shape the load driver measures (`--reads-per-order`), and needs nothing on the server.

The alternative, server-sent events from the views' projections, would show changes the moment they are projected
and is the better demo of the event flow - but it needs a notification path out of every view processor, across
instances, which is a feature in its own right. Candidate for later, not this phase.

## 5. CSRF (Q-5)

**Decided:** require a CSRF token on the customer surface the screens call - `/api/accounts/**` and
`/api/market-data/**` - when the request is authenticated by the session cookie, and keep it off for requests that carry
an `Authorization` header (Basic API clients, the `.http` files, the tests). The webhooks stay exempt (signed instead).

The token travels the way Spring documents for server-rendered pages: each Thymeleaf shell renders it in
`<meta name="_csrf">` / `<meta name="_csrf_header">`, and `api.js` sends it back in that header. It is held in the
session (Spring's default repository), not a cookie, so no page script ever needs to read a cookie.

Two corrections to the proposal, found while recording it:

- **Not all of `/api/**`.** The Essentials admin console (`spring-boot-starter-admin-ui`'s `admin.js`) calls
  `/api/essentials/admin/v1/**` from the browser on the operator's session and sends no token, and the demo's operator
  API is called the same way. Requiring the token there would break the console's writes. The operator surface keeps
  the phase 1 simplification; it is recorded in `TradingDemoSecurityConfiguration` and the module `CLAUDE.md`.
- **The load driver is affected.** The proposal said it was not, but `BrokerageHttp` logs customers in through the
  form and calls the API on their `JSESSIONID`, the way a browser does (phase 6 Q-1). It now reads the session's token
  from a page after each login and sends it on its writes, as the screens do.

## 6. Instruments for customers (Q-6)

**Proposed:** a customer-readable `GET /api/market-data/instruments` (and `/{instrumentId}`) served by the existing
`instrument_details` view, beside the quotes. The admin writes (register, rename, suspend) stay under
`/api/admin/instruments`; the admin `GET`s stay too, so nothing that uses them changes.

## 7. Where the code lives (Q-7)

**Proposed:** a top-level `_customer_ui` package holding page controllers (`@Controller`, return a template name, read
nothing), templates under `templates/customer_ui/`, assets under `static/customer_ui/`. It is not a slice - it owns
no events, no commands and no views - so it takes the slice law's `_` prefix for directories that are not slices, and
gets a `CLAUDE.md` saying so, like `_demo_harness`. It may not import any
context's code: it reaches the contexts only through HTTP from the browser, so the slice boundaries stay intact.

*As built:* one import from a context's public surface, `identity.types.AuthenticatedUser`: opening an account needs the
caller's `UserId`, which no API returns, so the page controller hands it to the page.

## 8. Testing (Q-8)

There is no JS test runner without a JS toolchain, and none is wanted. Proposed:

- MockMvc tests that each page renders for a customer, redirects to login without a session, and refuses an
  operator-only page to a customer;
- the CSRF rule (§5) tested both ways: a session-cookie `POST` to `/api/**` without the token is refused, a Basic one
  is not;
- the instruments endpoint tested as a customer;
- the screens checked by hand in a browser (Playwright, driven from the session) against the running demo with the
  simulators: place, fill, cancel, deposit, withdraw. Not automated.

*As built:* `CustomerUiIT` covers the first three points over real HTTP instead of MockMvc - form login, the session
cookie, the token read from the page's `<meta>` tag - which is the path the browser and the load driver take, and
needs no test dependencies beyond the ones the module has.

## 9. Decisions

| | Question | Decided |
|---|---|---|
| **Q-1** | How pages are built | Thymeleaf shell, vanilla JS over the existing JSON API; no server-side read paths (§2) |
| **Q-2** | Screens | Portfolio, Trade (ticket + orders), Funding, under `/app` (§3) |
| **Q-3** | Login page | Keep Spring's generated page; no self-registration (§3) |
| **Q-4** | Live updates | Polling while visible; server-sent events later (§4) |
| **Q-5** | CSRF | Required for session-authenticated requests to `/api/accounts/**` and `/api/market-data/**`, off for `Authorization`-header clients; the operator API keeps the phase 1 exemption (§5) |
| **Q-6** | Instruments for customers | `GET /api/market-data/instruments` from the existing view; admin paths unchanged (§6) |
| **Q-7** | Where the code lives | `_customer_ui` package, not a slice, imports no context code (§7) |
| **Q-8** | Testing | MockMvc for pages and security, manual browser check for the screens (§8) |
