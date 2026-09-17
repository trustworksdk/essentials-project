# Images

Taken verbatim from the source module, `docs/presentation/Module 6 - Simplifying with Event Modeling,
Event Sourcing, and CQRS.pptx`, so the concept half of each slide pair is the material the room already
knows. Trustworks' own artwork.

| File | Source slide | What it is |
|---|---|---|
| `event-model-legend.jpg` | 16 | A complete event model with its legend — UI/API/Job → Command (blue) → Event (orange) → View (green), and the four patterns as Given/When/Then |
| `wireframe-products.png` | 22, 23, 70 | "My Webshop" product list with *Add to cart* |
| `wireframe-basket.png` | 18, 22, 70 | Shopping basket with quantities, line prices, total |
| `wireframe-checkout.png` | 18, 22, 70, 72 | "Complete Order" — invoice address and payment details |
| `composite-ui.png` | 72, 73 | Order confirmation, with each region colour-boxed by the view it comes from |
| `dual-write.png` | 87 | EventStore → SubscriptionManager → Outbox → Kafka topic, both backed by PostgreSQL |

The module's **swimlane timelines** (slides 18, 22, 70–72) are not here: they are drawn with PowerPoint
shapes rather than embedded images, so only the wireframes inside them could be extracted. The deck
redraws those timelines as inline SVG, using the same event names.

To re-extract after the pptx changes:

```bash
cd "$(mktemp -d)" && unzip -q "/workspace/docs/presentation/Module 6 - Simplifying with Event Modeling, Event Sourcing, and CQRS.pptx"
# ppt/media holds the images; ppt/slides/_rels/slideN.xml.rels maps a slide to the ones it uses
```
