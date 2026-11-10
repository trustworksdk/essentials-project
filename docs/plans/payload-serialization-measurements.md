# Payload serialization and column type: measurements

**Status (2026-11-10):** measured. The two follow-ups in §5 are not built.

**Question:** could a different serializer for event-store and durable-queue payloads give a worthwhile gain? The
candidate was [Apache Fory](https://fory.apache.org/), a binary serialization framework. Fory is not a JSON library,
so adopting it would mean replacing JSON payloads with binary ones. Before weighing that, two things were measured:

1. What share of the client's CPU Jackson takes on the event-store append and load paths (§2).
2. What the PostgreSQL payload column type costs on the database side: `jsonb` versus `json` versus `bytea`, with
   identical bytes in each (§3).

**Answer:** no. Jackson is a small share of the cost, and on the database side a `bytea` column performs the same as
a `json` column for the same bytes. The cost that does show up is Postgres's `jsonb` conversion, and that can be
avoided without changing the serializer (§4, §5).

---

## 1. Setup

- Postgres 18.4 in Docker (Testcontainers), on the same 8-CPU arm64 machine as the benchmark JVM. JDK 25.0.5.
- JMH 1.37, 10 threads, one fork.
- Both benchmarks live in `components/postgresql-event-store/src/test/java/.../postgresql/` and are run with
  `org.openjdk.jmh.Main` on the module's test classpath (see the class javadoc of each).

## 2. Client side: Jackson's share of CPU

`PostgresqlEventStoreBenchmark` (the full event store: `appendToStream`, and `appendToStream` plus `fetchStream`
with payload deserialization), run under JMH's JFR profiler. The parameter is events per append, 1 or 20.
Payloads are the small `OrderEvent`s in `test_data`.

| Benchmark | ops/s | JVM CPU, % of machine | Machine CPU | Jackson, % of JVM CPU samples |
|---|---|---|---|---|
| append, 1 event | 36.7k (±19k) | 29% | 77% | 6.6% |
| append, 20 events | 8.7k (±1.1k) | 15% | 64% | 16.4% |
| append + load, 1 event | 34.0k (±20k) | 36% | 77% | 13.6% |
| append + load, 20 events | 7.7k (±1.1k) | 20% | 65% | 27.4% |

- The client is not the bottleneck. The JVM uses 15–36% of the machine, and its threads mostly wait for the
  database. Jackson is therefore roughly 1–5% of total machine CPU.
- Split for append + load with 20 events, as a share of all JVM samples: deserialization 15.8%, event serialization
  7.2%, metadata serialization 4.5%.
- Serializing the small metadata map costs about as much as serializing the event. Roughly 6 of the 27 points are
  fixed per-call costs: parser and generator construction, and the `BufferRecycler` pool. For small payloads,
  per-call overhead matters more than encoding speed, and that can be tuned inside Jackson without touching the
  wire format.
- Sample counts were low (570–960 per run), so the percentages are accurate to a few points.

## 3. Database side: `jsonb` vs `json` vs `bytea`

`PayloadColumnTypeBenchmark`: plain JDBC against a table shaped like an event-stream table, with the payload and
metadata columns typed `jsonb`, `json` or `bytea`. The same JSON bytes are written to every variant, and no
client-side (de)serialization happens on the measured path. Payload sizes: small 123 B, medium 2 KB, large 19 KB.
`insert` = one transaction appending 20 events (a JDBC batch). `read` = loading the 20 events of one aggregate.
Server execution time comes from `pg_stat_statements`.

Throughput (ops/s):

| | jsonb | json | bytea | jsonb vs json |
|---|---|---|---|---|
| insert, small | 9.5k (±4.4k) | 10.2k (±2.5k) | 10.3k (±2.5k) | −7% |
| insert, medium | 5.9k (±2.5k) | 7.2k (±2.9k) | 7.4k (±2.3k) | −17% |
| insert, large | 1.3k (±0.6k) | 1.7k (±0.6k) | 1.9k (±0.5k) | −25% |
| read, small | 253k (±16k) | 324k (±10k) | 324k (±15k) | −22% |
| read, medium | 50k (±2k) | 91k (±2k) | 94k (±4k) | −45% |
| read, large | 6.2k (±0.3k) | 11.7k (±1.6k) | 12.0k (±1.0k) | −47% |

Mean server execution time (ms):

| Statement | jsonb | json | bytea |
|---|---|---|---|
| insert one row, small / medium / large | 0.025 / 0.047 / 0.294 | 0.024 / 0.038 / 0.206 | 0.024 / 0.036 / 0.184 |
| select 20 rows, small / medium / large | 0.013 / 0.172 / 1.390 | 0.008 / 0.088 / 0.684 | 0.008 / 0.084 / 0.649 |

Storage per row:

| | jsonb | json | bytea |
|---|---|---|---|
| table bytes, small / medium / large | 389 / 1 451 / 8 534 | 368 / 1 256 / 8 511 | 368 / 1 256 / 8 504 |
| WAL bytes per inserted row, small / medium / large | 512 / 1 395 / 7 562 | 487 / 1 291 / 7 233 | 487 / 1 291 / 7 241 |

- `json` and `bytea` behave the same in every measurement. Both store the bytes as given.
- `jsonb` costs on both paths: it parses on the way in and renders text on the way out. Reads of medium and large
  payloads take twice the server time and run at about half the throughput.
- The insert numbers vary ±25–45%, so only the medium and large insert differences are clearly real. The read
  numbers are tight.
- `pg_stat_statements` was reset after pre-population but includes the warmup iterations.

## 4. Conclusions

- **Fory is not worth adopting for payloads.** On the client it could save at most the 1–5% of machine CPU that
  Jackson uses. On the database it would match a `json` column for the same bytes, and gain only through a smaller
  encoding (less I/O and WAL). Against that, it would cost `jsonb` querying, readable payloads, the CDC converters
  (which parse JSON), Jackson's tolerance of evolving event types, readers in other languages, and a second persisted
  format that existing streams would have to live alongside, because the JSON wire format is frozen.
- **The measurable cost is the `jsonb` column type, not the serializer.** Event streams can already opt out with
  `JSONColumnType.JSON`, and nothing about the wire format changes. The trade-off: no `jsonb` operators or GIN
  indexes on the payload, SQL queries into the payload parse it at query time, and the column returns the stored
  text verbatim instead of a normalised rendering.

## 5. Candidate follow-ups

| Item | Why | Compatibility |
|---|---|---|
| Document `JSONColumnType.JSON` as an option for read-heavy event streams, with the trade-off above (`LLM/LLM-components.md`, starter docs) | Up to ~2× faster loads and 7–25% faster appends for medium and large payloads, with config alone | Docs only. Applies to new tables; an existing table keeps its column type |
| Make the durable-queue payload and metadata column type configurable (`message_payload` and `meta_data` are hard-coded `JSONB` in `DurableQueuesSql`) | Queue payloads pay the same `jsonb` conversion on every enqueue and every consume | Additive, `JSONB` stays the default. Must also cover the shard-owned queue tables and the schema contributors |
| Trim Jackson's per-call overhead on the event-store write path (for example, reuse prepared `ObjectWriter`s, and check the cost of the `BufferRecycler` pool) | About a quarter of Jackson's samples are fixed per-call costs on small payloads | Internal. The persisted bytes must stay identical, guarded by the wire-format golden tests |
