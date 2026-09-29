# Fixture — `brownfield-layered`

A synthetic Maven/Spring/JPA service organised **by technical layer**, used to exercise
`/essentials:slice-discover`. It is deliberately *not* an Essentials project and deliberately does
not follow `rules/slice-design.md` — that is the point.

**Never built, never run, never shipped to a user.** No test runner exists in this repo; the fixture
is diffed against the ground truth in `TEST-GUIDE.md` by eye.

## Shape

```
pom.xml                                  Spring Boot 3 + web + data-jpa
src/main/java/com/acme/shop/
  controller/   OrderController          5 mappings — the god controller
                InvoiceController        2 mappings
                ReportController         1 mapping — the trap
  service/      OrderService             writes Order
                BillingService           writes Invoice AND Order
                PaymentReminderJob       @Scheduled, writes
  repository/   OrderRepository, InvoiceRepository
  model/        Order, OrderLine, Invoice
  integration/  PaymentGatewayClient + dto/ (foreign snake_case schema)
```

Fourteen Java files. Every package name is a **layer**, so nothing in the tree names a domain
boundary — a package-name-driven analyser finds exactly one context here and is wrong.

## Why each element is present

The fixture holds one instance of everything the heuristics claim to find, **plus traps**. A fixture
containing only findings proves nothing about false positives, which are the failure mode that
actually discredits an inference tool.

See `TEST-GUIDE.md` for the ground truth and the expected report.

## Editing rules

- Keep files short — these are signal carriers, not realistic code.
- **If you add a heuristic to `references/slice/discovery-heuristics.md`, add a fixture element that
  exercises it, and a row to `TEST-GUIDE.md`.** A heuristic with no fixture element is unexercised.
- Never make it compile-clean-by-accident into something a build could run; it must stay obviously
  synthetic so nobody mistakes it for a template.
