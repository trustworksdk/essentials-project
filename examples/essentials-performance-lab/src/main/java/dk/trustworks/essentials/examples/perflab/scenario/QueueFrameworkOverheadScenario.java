/*
 * Copyright 2021-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dk.trustworks.essentials.examples.perflab.scenario;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.essentials.components.foundation.messaging.queue.*;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.*;
import dk.trustworks.essentials.components.queue.postgresql.PostgresqlDurableQueues;
import dk.trustworks.essentials.components.foundation.json.JSONSerializer;
import dk.trustworks.essentials.examples.perflab.EssentialsPerformanceLabProperties;
import dk.trustworks.essentials.examples.perflab.queuedesign.QueueSchemaPrototype;
import org.slf4j.*;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/**
 * Quantifies the per-message framework overhead that bounds every schema-level result the queue
 * investigation has produced.
 *
 * <h2>Why this measurement gates the others</h2>
 * {@code QueueSchemaWriteCostScenario} and its cursor arm measure raw SQL on a single connection with no
 * consumers, no interceptors and no unit of work per message. That isolation is deliberate — it is the only
 * way to see index maintenance at all — but it means its ratios (the split's 1.38x, the cursor's 2.64x) are
 * an <strong>upper bound</strong> on what a full implementation could deliver. The measurements doc already
 * found the direction: removing 96% of the acknowledgement statements moved end-to-end throughput 13%,
 * because {@code acknowledgeMessageAsHandled} wraps the interceptor chain in its own
 * {@code UnitOfWork} and the per-message <em>operation</em> dominates the statement.
 * <p>
 * Direction is not a number. Without one, there is no way to say whether the cursor's 4.0x claim-phase win
 * arrives as 4.0x, 1.4x or 1.05x in the real component, and therefore no way to size the implementation
 * against the payoff. This scenario produces that number, and it deliberately produces it as a
 * <em>decomposition</em> rather than a single figure, because the overhead has two separable parts and they
 * have different fixes:
 * <ul>
 *     <li><strong>Transaction and connection granularity</strong> — a transaction per message rather than
 *     per batch. Fixable without touching the public API (batch the acknowledgement, hold one unit of work
 *     across a drain round).</li>
 *     <li><strong>Everything else the component does per message</strong> — interceptor chain, operation
 *     objects, payload and metadata serialization, row mapping. Fixable only by changing what an operation
 *     costs.</li>
 * </ul>
 *
 * <h2>Arms — the SQL is held constant, the granularity varies</h2>
 * The first three arms issue the <em>same</em> statements against the <em>same</em> schema
 * ({@link QueueSchemaPrototype#v1SingleTableDdl}, six secondary indexes, exactly v1) and differ only in how
 * many transactions those statements are spread across. Any difference between them is therefore
 * granularity and nothing else.
 * <ul>
 *     <li>{@code RAW_BATCHED} — one unit of work per batch claim, one per batched delete. This is precisely
 *     what the write-cost prototype does, so it is the baseline the published ratios were measured on.</li>
 *     <li>{@code RAW_BATCH_CLAIM_SINGLE_ACK} — batch claim in one unit of work, then one unit of work and
 *     one single-row {@code DELETE} per message. This is today's real shape: the centralized fetcher claims
 *     a batch in one statement and the workers acknowledge individually.</li>
 *     <li>{@code RAW_SINGLE} — claim {@code LIMIT 1} in its own unit of work, acknowledge in its own. Two
 *     transactions per message, still with no framework code in the path.</li>
 * </ul>
 * The last two arms run the real {@link DurableQueues} component over the same workload, and differ only in
 * whether an outer unit of work is held open:
 * <ul>
 *     <li>{@code COMPONENT_SHARED_UOW} — one outer unit of work per claim batch, so the component reuses a
 *     connection and transaction across a batch of operations rather than acquiring one per operation.
 *     Per-batch and not per-drain: a transaction spanning the whole drain pins the xmin horizon and blocks
 *     reclamation of the dead tuples the drain itself produces, which made the first version of this arm
 *     5.7x slower across three identical repetitions — an artefact of the harness, not a property of the
 *     component.</li>
 *     <li>{@code COMPONENT} — no outer unit of work, so {@code TransactionalMode.SingleOperationTransaction}
 *     opens one per operation. This is the production shape.</li>
 * </ul>
 * <h2>Which comparisons this scenario actually supports</h2>
 * <b>Raw-to-raw is sound.</b> Those three arms share a schema and a claim statement, so their ratios name
 * transaction granularity and nothing else. That is where the load-bearing results come from.
 * <p>
 * <b>Cross-family is not.</b> The component claims through its own split unordered query and partial covering
 * index; the raw arms use the v1 six-index claim. So {@code frameworkOverheadAtEqualGranularity} varies the
 * claim query as well as the framework, and at 9 repetitions it comes out <em>below</em> 1 — the component
 * looking cheaper than hand-written SQL, which is the signature of a confound rather than a finding. It is
 * still emitted, labelled, because knowing the two families are within noise of each other is worth
 * something; it just cannot be read as "the framework costs X".
 * <p>
 * <b>{@code componentTransactionGranularity} is inconclusive.</b> The shared-UoW arm holds one transaction
 * across a claim batch while claiming one message at a time, so it re-creates a milder version of the
 * xmin-pinning artefact described above. Its spread is the widest of any arm and the ratio's bounds straddle
 * 1 in both directions.
 *
 * <h2>Unordered only, and why that is the right choice</h2>
 * Per-message transaction cost has nothing to do with the ordered per-key barrier, and running ordered
 * traffic would add the barrier's cost — 10.7s against 2.5s on identical volume — to every arm, shrinking
 * the overhead being measured into the noise of a much larger number. Unordered isolates it. The resulting
 * tax applies to the ordered path too, which is what makes it a bound on the cursor result.
 *
 * <h2>What is recorded, and the caveats that bound it</h2>
 * Wall-clock per phase, plus {@code unitsOfWorkPerMessage} — the number of transactions the scenario causes
 * per drained message, so that "a transaction per message" is a recorded quantity rather than a claim about
 * the code. It is counted client-side at the call sites, exactly for the raw arms, and for the component arms
 * as the number of {@link DurableQueues} operations invoked, which is what
 * {@code TransactionalMode.SingleOperationTransaction} turns each call into.
 * <p>
 * <strong>It is deliberately not read from the server.</strong> The obvious implementation — {@code delta} of
 * {@code xact_commit} from {@code pg_stat_database} across the drain — was tried first and produced garbage:
 * PostgreSQL flushes backend statistics asynchronously, so three arms reported exactly {@code 0.0} commits
 * per message while the arms that happened to straddle a flush reported {@code 6.56} where the call pattern
 * can only produce {@code 2.0}. The counter is cumulative and correct in the long run, but it cannot be
 * differenced across a window this short, and there is no way to force a flush across a pooled set of
 * backends. Client-side counting answers the same question and cannot drift.
 * <p>
 * One caveat does remain. Every case gets a freshly created table that is dropped afterwards — the component
 * arms build their own {@code PostgresqlDurableQueues} on a per-case table rather than using the shared bean,
 * because the bean's table outlives a case and its accumulated dead tuples then land on whichever case runs
 * next. But the component's table is created by the component and the raw arms' by
 * {@code v1SingleTableDdl}, and while the two are equivalent by construction they are not the same DDL, so
 * component-versus-raw ratios carry that assumption. The two component arms compared against each other do
 * not.
 * A warmup case per arm, sized at a fraction of the real run and discarded, runs first: the component arms
 * execute far more Java per message than the raw ones and would otherwise charge their JIT and
 * connection-pool ramp to the first repetition.
 */
@Component
public class QueueFrameworkOverheadScenario implements LabScenario {
    private static final Logger log          = LoggerFactory.getLogger(QueueFrameworkOverheadScenario.class);
    private static final String FILLER       = "x".repeat(200);
    private static final String PAYLOAD_TYPE = "dk.trustworks.essentials.examples.perflab.scenario.QueueFrameworkOverheadScenario$LabOverheadItem";

    private final HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory;
    private final JSONSerializer                                                jsonSerializer;
    private final ObjectMapper                                                  objectMapper;

    public QueueFrameworkOverheadScenario(HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory,
                                          JSONSerializer jsonSerializer,
                                          ObjectMapper objectMapper) {
        this.unitOfWorkFactory = unitOfWorkFactory;
        this.jsonSerializer = jsonSerializer;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "queue-framework-overhead";
    }

    @Override
    public String description() {
        return "Decomposes per-message queue cost into transaction granularity and framework overhead, bounding the schema-level prototype ratios";
    }

    @Override
    public void run(EssentialsPerformanceLabProperties properties) throws Exception {
        var messages    = properties.getFrameworkOverheadMessages();
        var claimBatch  = properties.getFrameworkOverheadClaimBatchSize();
        var repetitions = properties.getFrameworkOverheadRepetitions();
        var runId       = Long.toHexString(System.nanoTime());
        // Enough to JIT the per-message path and fill the connection pool without materially extending the run.
        var warmupMessages = Math.max(200, messages / 10);

        log.info("queue-framework-overhead: messages={}, claimBatch={}, repetitions={}, warmupMessages={}",
                 messages, claimBatch, repetitions, warmupMessages);

        var results = new ArrayList<CaseResult>();
        for (var arm : Arm.values()) {
            runCase(runId, arm, warmupMessages, claimBatch, -1, true);
        }
        // Arms alternate WITHIN each repetition rather than each arm running its repetitions consecutively.
        // The dominant noise source here is autovacuum working through the dead tuples a drain produces, which
        // is time-correlated: consecutive repetitions of one arm share whatever background state happened to
        // exist during that stretch, so a slow patch lands entirely on one arm and reads as a property of the
        // arm. Interleaving spreads every such patch across all five. Same reason QueueSchemaWriteCostScenario
        // alternates its arms.
        for (var repetition = 0; repetition < repetitions; repetition++) {
            for (var arm : Arm.values()) {
                var result = runCase(runId, arm, messages, claimBatch, repetition, false);
                results.add(result);
                log.info("queue-framework-overhead {} rep {} => insert {} ms, claim {} ms, ack {} ms, drain {} ms, {} us/msg, {} uow/msg",
                         result.arm(), repetition, result.insertMillis(), result.claimMillis(), result.ackMillis(),
                         result.drainMillis(), result.microsPerMessage(), result.unitsOfWorkPerMessage());
            }
        }

        var report = new LinkedHashMap<String, Object>();
        report.put("scenario", name());
        report.put("capturedAt", Instant.now().toString());
        report.put("messages", messages);
        report.put("claimBatchSize", claimBatch);
        report.put("repetitions", repetitions);
        // Reported rather than assumed: the component arms build their own instance, so this records the
        // fetch strategy they actually ran with.
        report.put("useOrderedUnorderedQuery", PostgresqlDurableQueues.builder()
                                                                      .setUnitOfWorkFactory(unitOfWorkFactory)
                                                                      .setJsonSerializer(jsonSerializer)
                                                                      .build()
                                                                      .isUseOrderedUnorderedQuery());
        report.put("cases", results);
        report.put("decomposition", buildDecomposition(results));

        var json = toJson(report);
        System.out.println("############# [perf-lab] queue-framework-overhead: " + json);
        writeMetricsIfConfigured(properties.getMetricsOutputFile(), json);
    }

    private CaseResult runCase(String runId, Arm arm, int messages, int claimBatch, int repetition, boolean warmup) {
        return arm.usesComponent()
               ? runComponentCase(runId, arm, messages, claimBatch, repetition, warmup)
               : runRawCase(runId, arm, messages, claimBatch, repetition, warmup);
    }

    /**
     * Raw-SQL arms. The DDL, the insert, the claim statement and the acknowledgement statement are fixed
     * across all three; only the transaction boundaries move.
     */
    private CaseResult runRawCase(String runId, Arm arm, int messages, int claimBatch, int repetition, boolean warmup) {
        // PostgreSQL truncates identifiers at 63 bytes and the index names are derived from this one.
        var suffix    = runId + "_" + arm.ordinal() + "_r" + (warmup ? "w" : repetition);
        var table     = "fo_" + suffix;
        var queueName = "q_" + suffix;

        unitOfWorkFactory.usingUnitOfWork(unitOfWork -> QueueSchemaPrototype.v1SingleTableDdl(table, 100)
                                                                           .forEach(statement -> unitOfWork.handle().execute(statement)));

        var payload      = payload();
        var payloadType  = PAYLOAD_TYPE;
        var now          = OffsetDateTime.now();
        var insertSql    = QueueSchemaPrototype.insertUnorderedSql(table);
        var claimSql     = QueueSchemaPrototype.claimUnorderedSql(table, true);
        var deleteOneSql = QueueSchemaPrototype.deleteSingleSql(table);
        var deleteAllSql = QueueSchemaPrototype.deleteBatchSql(table);

        var insertStart = System.nanoTime();
        unitOfWorkFactory.usingUnitOfWork(unitOfWork -> {
            var batch = unitOfWork.handle().prepareBatch(insertSql);
            for (var i = 0; i < messages; i++) {
                batch.bind("id", UUID.randomUUID().toString())
                     .bind("queueName", queueName)
                     .bind("payload", payload)
                     .bind("payloadType", payloadType)
                     .bind("now", now)
                     .add();
            }
            batch.execute();
        });
        var insertMillis = millisSince(insertStart);

        var claimedRows   = 0;
        var claimNanos    = 0L;
        var ackNanos      = 0L;
        var unitsOfWork   = 0L;
        while (true) {
            // RAW_SINGLE claims one row at a time; the other two claim a batch. Same statement either way -
            // only the bound :limit differs, so the planner sees the same shape.
            var limit           = arm == Arm.RAW_SINGLE ? 1 : claimBatch;
            var claimRoundStart = System.nanoTime();
            var batchIds = unitOfWorkFactory.withUnitOfWork(unitOfWork -> unitOfWork.handle()
                                                                                    .createQuery(claimSql)
                                                                                    .bind("queueName", queueName)
                                                                                    .bind("now", OffsetDateTime.now())
                                                                                    .bind("limit", limit)
                                                                                    .mapTo(String.class)
                                                                                    .list());
            claimNanos += System.nanoTime() - claimRoundStart;
            unitsOfWork++;
            if (batchIds.isEmpty()) {
                break;
            }
            claimedRows += batchIds.size();

            var ackRoundStart = System.nanoTime();
            if (arm == Arm.RAW_BATCHED) {
                unitOfWorkFactory.usingUnitOfWork(unitOfWork -> unitOfWork.handle()
                                                                          .createUpdate(deleteAllSql)
                                                                          .bindList("ids", batchIds)
                                                                          .execute());
                unitsOfWork++;
            } else {
                // One unit of work - and therefore one connection acquisition, BEGIN and COMMIT - per message.
                for (var id : batchIds) {
                    unitOfWorkFactory.usingUnitOfWork(unitOfWork -> unitOfWork.handle()
                                                                              .createUpdate(deleteOneSql)
                                                                              .bind("id", id)
                                                                              .execute());
                }
                unitsOfWork += batchIds.size();
            }
            ackNanos += System.nanoTime() - ackRoundStart;
        }

        unitOfWorkFactory.usingUnitOfWork(unitOfWork -> unitOfWork.handle().execute("DROP TABLE IF EXISTS " + table));

        return caseResult(arm, repetition, warmup, messages, claimedRows, insertMillis, claimNanos, ackNanos, unitsOfWork);
    }

    /**
     * Component arms. Both drive {@link DurableQueues} operation-by-operation from this thread rather than
     * through a consumer, matching the raw arms' single-threaded shape — a consumer's threading would add a
     * second variable and this measurement is about per-operation cost, not concurrency.
     */
    private CaseResult runComponentCase(String runId, Arm arm, int messages, int claimBatch, int repetition, boolean warmup) {
        var suffix    = runId + "_" + arm.ordinal() + "_r" + (warmup ? "w" : repetition);
        var table     = "foc_" + suffix;
        var queueName = QueueName.of("q_" + suffix);

        // A dedicated instance on its own freshly created table, rather than the shared DurableQueues bean.
        // The bean's table survives between cases, so its accumulated dead tuples leak into whichever case
        // runs later: the first version of this scenario used it and the component arms degraded 2.7x from
        // one repetition to the next while the raw arms - which drop and recreate their table per case - held
        // steady. Per-case tables make the component arms structurally identical to the raw ones, which is
        // what the comparison between them requires.
        var durableQueues = PostgresqlDurableQueues.builder()
                                                   .setUnitOfWorkFactory(unitOfWorkFactory)
                                                   .setJsonSerializer(jsonSerializer)
                                                   .setSharedQueueTableName(table)
                                                   .build();
        durableQueues.start();
        try {

            var insertStart = System.nanoTime();
            // Enqueued in claim-batch-sized chunks rather than one call, so a large run does not build one
            // enormous PreparedBatch. Enqueue is outside the drain clock either way.
            for (var offset = 0; offset < messages; offset += claimBatch) {
                var chunk = new ArrayList<Message>();
                for (var i = offset; i < Math.min(offset + claimBatch, messages); i++) {
                    chunk.add(Message.of(new LabOverheadItem(i, FILLER)));
                }
                unitOfWorkFactory.usingUnitOfWork(unitOfWork -> durableQueues.queueMessages(queueName, chunk));
            }
            var insertMillis = millisSince(insertStart);

            var counters = new DrainCounters();
            if (arm == Arm.COMPONENT_SHARED_UOW) {
                // One unit of work per claim batch, deliberately NOT one across the whole drain. A single
                // transaction spanning the drain pins the xmin horizon, so none of the dead tuples the drain
                // produces can be reclaimed while it runs and the claim degrades as it goes - the first version of
                // this arm did exactly that and got 5.7x slower across three identical repetitions, measuring an
                // artefact of the harness instead of the component. Per-batch also matches RAW_BATCHED's
                // granularity, which is what makes the raw and component estimates comparable.
                while (!counters.exhausted) {
                    unitOfWorkFactory.usingUnitOfWork(unitOfWork -> drainViaComponent(durableQueues, queueName, counters, claimBatch));
                    counters.unitsOfWork++;
                }
            } else {
                // No outer unit of work: SingleOperationTransaction opens one per operation. Production shape.
                drainViaComponent(durableQueues, queueName, counters, Integer.MAX_VALUE);
                counters.unitsOfWork = counters.operations;
            }
            var unitsOfWork = counters.unitsOfWork;

            return caseResult(arm, repetition, warmup, messages, counters.drained, insertMillis, counters.claimNanos, counters.ackNanos, unitsOfWork);
        } finally {
            durableQueues.stop();
            unitOfWorkFactory.usingUnitOfWork(unitOfWork -> unitOfWork.handle().execute("DROP TABLE IF EXISTS " + table));
        }
    }

    /**
     * Claim-then-acknowledge, one message at a time, timing the two calls separately so the acknowledgement
     * cost stays attributable — it is the half the batching proposals target.
     */
    private void drainViaComponent(DurableQueues durableQueues, QueueName queueName, DrainCounters counters, int maxMessages) {
        for (var handled = 0; handled < maxMessages; handled++) {
            var claimStart = System.nanoTime();
            var next       = durableQueues.getNextMessageReadyForDelivery(queueName);
            counters.claimNanos += System.nanoTime() - claimStart;
            counters.operations++;
            if (next.isEmpty()) {
                counters.exhausted = true;
                return;
            }
            var ackStart = System.nanoTime();
            durableQueues.acknowledgeMessageAsHandled(next.get().getId());
            counters.ackNanos += System.nanoTime() - ackStart;
            counters.operations++;
            counters.drained++;
        }
    }

    /**
     * The comparisons the scenario exists to produce. Each divides two arms that differ in exactly one
     * respect, so the ratio names a cost rather than a mixture of costs.
     */
    private List<Map<String, Object>> buildDecomposition(List<CaseResult> results) {
        var decomposition = new ArrayList<Map<String, Object>>();
        var rawBatched    = medianOf(results, Arm.RAW_BATCHED);
        var rawSplitAck   = medianOf(results, Arm.RAW_BATCH_CLAIM_SINGLE_ACK);
        var rawSingle     = medianOf(results, Arm.RAW_SINGLE);
        var componentUow  = medianOf(results, Arm.COMPONENT_SHARED_UOW);
        var component     = medianOf(results, Arm.COMPONENT);

        addRatio(decomposition, "ackTransactionGranularity", rawBatched, rawSplitAck,
                 "Cost of one transaction per acknowledgement instead of one per batch. Raw SQL both sides, identical statements.");
        addRatio(decomposition, "fullTransactionGranularity", rawBatched, rawSingle,
                 "Cost of two transactions per message instead of two per batch. Raw SQL both sides - the granularity tax with no framework in the path.");
        addRatio(decomposition, "componentTransactionGranularity", componentUow, component,
                 "Same component code, differing only in whether an outer UnitOfWork is held. INCONCLUSIVE at 9 repetitions: "
                         + "the shared-UoW arm holds one transaction across a whole claim batch while still claiming one message at a "
                         + "time, so it pins the xmin horizon for that stretch and blocks reclamation of the dead tuples it is itself "
                         + "producing. Its spread is the widest of any arm and the ratio's bounds straddle 1. Do not quote it.");
        addRatio(decomposition, "frameworkOverheadAtEqualGranularity", rawSingle, component,
                 "INTENDED to isolate what the component adds over raw SQL - interceptor chain, operation objects, serialization, row "
                         + "mapping - but CONFOUNDED and not usable as measured: the component claims through its split unordered query "
                         + "and partial covering index, while the raw arms use the v1 six-index claim. The two families differ in the "
                         + "claim query as well as in the framework, which is why this comes out below 1. Comparing raw-to-raw and "
                         + "component-to-component is sound; comparing across the two is not.");
        addRatio(decomposition, "prototypeUpperBoundDeflator", rawBatched, component,
                 "The factor by which a schema-level prototype ratio must be deflated to describe the production component. This is the bound the cursor and split results need.");
        return decomposition;
    }

    private void addRatio(List<Map<String, Object>> into, String name, Medians baseline, Medians candidate, String meaning) {
        if (baseline == null || candidate == null) {
            return;
        }
        var entry = new LinkedHashMap<String, Object>();
        entry.put("ratio", name);
        entry.put("meaning", meaning);
        entry.put("baselineArm", baseline.arm());
        entry.put("candidateArm", candidate.arm());
        entry.put("baselineDrainMillis", baseline.drain());
        entry.put("candidateDrainMillis", candidate.drain());
        entry.put("drainCostMultiple", baseline.drain() == 0 ? null : (double) candidate.drain() / baseline.drain());
        // The spread matters as much as the point estimate: a ratio whose bounds straddle 1 is not a result.
        // Worst case divides the candidate's slowest run by the baseline's fastest, best case the reverse, so
        // the pair brackets what the repetitions actually support rather than what their medians suggest.
        entry.put("drainCostMultipleBestCase", baseline.drainMax() == 0 ? null : (double) candidate.drainMin() / baseline.drainMax());
        entry.put("drainCostMultipleWorstCase", baseline.drainMin() == 0 ? null : (double) candidate.drainMax() / baseline.drainMin());
        entry.put("baselineDrainRangeMillis", List.of(baseline.drainMin(), baseline.drainMax()));
        entry.put("candidateDrainRangeMillis", List.of(candidate.drainMin(), candidate.drainMax()));
        entry.put("samplesPerArm", candidate.samples());
        entry.put("baselineAckMillis", baseline.ack());
        entry.put("candidateAckMillis", candidate.ack());
        entry.put("baselineUnitsOfWorkPerMessage", baseline.unitsOfWorkPerMessage());
        entry.put("candidateUnitsOfWorkPerMessage", candidate.unitsOfWorkPerMessage());
        into.add(entry);
    }

    private CaseResult caseResult(Arm arm, int repetition, boolean warmup, int messages, int drained,
                                 long insertMillis, long claimNanos, long ackNanos, long unitsOfWork) {
        var claimMillis = Duration.ofNanos(claimNanos).toMillis();
        var ackMillis   = Duration.ofNanos(ackNanos).toMillis();
        var drainMillis = claimMillis + ackMillis;
        return new CaseResult(arm.name(),
                              repetition,
                              warmup,
                              messages,
                              drained,
                              insertMillis,
                              claimMillis,
                              ackMillis,
                              drainMillis,
                              drained == 0 ? 0.0d : (claimNanos + ackNanos) / 1_000.0d / drained,
                              unitsOfWork,
                              drained == 0 ? 0.0d : (double) unitsOfWork / drained);
    }

    private static Medians medianOf(List<CaseResult> results, Arm arm) {
        var matching = results.stream().filter(result -> result.arm().equals(arm.name()) && !result.warmup()).toList();
        if (matching.isEmpty()) {
            return null;
        }
        return new Medians(arm.name(),
                           median(matching.stream().map(CaseResult::claimMillis).sorted().toList()),
                           median(matching.stream().map(CaseResult::ackMillis).sorted().toList()),
                           median(matching.stream().map(CaseResult::drainMillis).sorted().toList()),
                           matching.stream().mapToLong(CaseResult::drainMillis).min().orElse(0L),
                           matching.stream().mapToLong(CaseResult::drainMillis).max().orElse(0L),
                           matching.size(),
                           matching.stream().mapToDouble(CaseResult::unitsOfWorkPerMessage).sorted().skip((matching.size() - 1) / 2).findFirst().orElse(0.0d));
    }

    private static long median(List<Long> sorted) {
        if (sorted.isEmpty()) {
            return 0L;
        }
        var middle = sorted.size() / 2;
        return sorted.size() % 2 == 1 ? sorted.get(middle) : (sorted.get(middle - 1) + sorted.get(middle)) / 2L;
    }

    private static long millisSince(long startNanos) {
        return Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
    }

    private static String payload() {
        return "{\"sequence\":0,\"filler\":\"" + FILLER + "\"}";
    }

    private void writeMetricsIfConfigured(String metricsOutputFile, String json) throws IOException {
        if (!StringUtils.hasText(metricsOutputFile)) {
            return;
        }
        var target = Paths.get(metricsOutputFile).toAbsolutePath().normalize();
        if (target.getParent() != null) {
            Files.createDirectories(target.getParent());
        }
        Files.writeString(target, json + System.lineSeparator(),
                          StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        log.info("Wrote queue-framework-overhead metrics to {}", target);
        System.out.println("############# [perf-lab] queue-framework-overhead metrics file: " + target);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize queue-framework-overhead metrics to JSON", e);
        }
    }

    public enum Arm {
        /**
         * The write-cost prototype's shape, and therefore the baseline the published schema ratios were
         * measured on: one unit of work per batch claim, one per batched delete.
         */
        RAW_BATCHED,
        /**
         * Today's real shape: the fetcher batch-claims, the workers acknowledge one at a time.
         */
        RAW_BATCH_CLAIM_SINGLE_ACK,
        /**
         * Two transactions per message, no framework code in the path.
         */
        RAW_SINGLE,
        /**
         * The real component with one outer unit of work held across the whole drain.
         */
        COMPONENT_SHARED_UOW,
        /**
         * The real component with no outer unit of work, so every operation opens its own. Production shape.
         */
        COMPONENT;

        boolean usesComponent() {
            return this == COMPONENT_SHARED_UOW || this == COMPONENT;
        }
    }

    private static final class DrainCounters {
        private long    claimNanos;
        private long    ackNanos;
        private long    operations;
        private long    unitsOfWork;
        private int     drained;
        private boolean exhausted;
    }

    private record Medians(String arm, long claim, long ack, long drain, long drainMin, long drainMax, int samples, double unitsOfWorkPerMessage) {
    }

    public record CaseResult(String arm,
                             int repetition,
                             boolean warmup,
                             int messagesInserted,
                             int messagesDrained,
                             long insertMillis,
                             long claimMillis,
                             long ackMillis,
                             long drainMillis,
                             double microsPerMessage,
                             long unitsOfWork,
                             double unitsOfWorkPerMessage) {
    }

    /**
     * Payload for the component arms. Deliberately the same shape and size as the raw arms' JSON string, so
     * the two are comparing the same bytes on the wire.
     */
    public record LabOverheadItem(int sequence, String filler) {
    }
}
