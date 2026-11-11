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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.spike.model;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.spike.model.SubscriptionModel.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase-0 spike P0.5 (docs/plans/event-store-phase0-model.md). Runs only with {@code -Dspike.run=true}; no Docker.
 * <pre>
 * mvn -o test -pl components/postgresql-event-store -Dtest=SubscriptionStateMachineModelTest -Dspike.run=true -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 * Optional: {@code -Dspike.model.seeds=N} (random walks per configuration), {@code -Dspike.model.maxStates=N}.
 */
@Tag("spike")
@EnabledIfSystemProperty(named = "spike.run", matches = "true")
class SubscriptionStateMachineModelTest {

    static final int MAX_STATES = Integer.getInteger("spike.model.maxStates", 5_000_000);
    static final int SEEDS      = Integer.getInteger("spike.model.seeds", 200_000);
    static final int MUTANT_MAX_STATES = Integer.getInteger("spike.model.mutantMaxStates", 1_000_000);

    static final int T0 = 0b01, ALL = 0b11;

    /** Three writers on two aggregates, one abortable; FRESH periodic (all types) and CONTIGUOUS in-tx (type 0 only, filtered catch-up). */
    static Config core() {
        return new Config("core")
                .writer(WriterSpec.of(0, 0).abortable())
                .writer(WriterSpec.of(0, 1))
                .writer(WriterSpec.of(1, 0))
                .sub(Mode.FRESH, Progress.PERIODIC, ALL)
                .sub(Mode.CONTIGUOUS, Progress.IN_TX, T0);
    }

    /** Core without sequence jumps, small enough to exhaust. */
    static Config coreNoJump() {
        Config c = core();
        c.name = "core-no-jump";
        c.maxJumps = 0;
        return c;
    }

    /** Sequence jump, abort and crash with two writers, both subscription kinds. */
    static Config jump() {
        Config c = new Config("jump")
                .writer(WriterSpec.of(0, 0).abortable())
                .writer(WriterSpec.of(0, 0))
                .sub(Mode.FRESH, Progress.PERIODIC, ALL)
                .sub(Mode.CONTIGUOUS, Progress.IN_TX, T0);
        c.maxJumps = 2;
        return c;
    }

    /** Two writers on two aggregates, both subscription kinds unfiltered; reaches deep reader states cheaply. */
    static Config small() {
        Config c = new Config("small")
                .writer(WriterSpec.of(0, 0))
                .writer(WriterSpec.of(1, 0))
                .sub(Mode.FRESH, Progress.PERIODIC, ALL)
                .sub(Mode.CONTIGUOUS, Progress.IN_TX, ALL);
        c.maxJumps = 0;
        return c;
    }

    /** Page per transaction with a handler that fails once on w1's event; CONTIGUOUS (all) and FRESH in-tx (type 0). */
    static Config pages() {
        Config c = new Config("pages")
                .writer(WriterSpec.of(0, 0))
                .writer(WriterSpec.of(0, 0).failing(1))
                .writer(WriterSpec.of(1, 0))
                .sub(Mode.CONTIGUOUS, Progress.IN_TX, ALL)
                .sub(Mode.FRESH, Progress.IN_TX, T0);
        c.maxJumps = 0;
        return c;
    }

    /** As pages(), but the failing event always fails and is dead-lettered after two attempts. */
    static Config deadLetter() {
        Config c = pages();
        c.name = "deadLetter";
        c.writers.set(1, WriterSpec.of(0, 0).failing(99));
        return c;
    }

    /** Bigger, for random walks only: five writers, three subscriptions, two crashes, two jumps, buffer 1. */
    static Config large() {
        Config c = new Config("large")
                .writer(WriterSpec.of(0, 0).abortable())
                .writer(WriterSpec.of(0, 1))
                .writer(WriterSpec.of(1, 0).failing(1))
                .writer(WriterSpec.of(1, 1).abortable())
                .writer(WriterSpec.of(0, 0))
                .sub(Mode.FRESH, Progress.PERIODIC, ALL)
                .sub(Mode.CONTIGUOUS, Progress.IN_TX, T0)
                .sub(Mode.FRESH, Progress.IN_TX, ALL);
        c.maxCrashes = 2;
        c.maxJumps = 2;
        c.bufferCap = 1;
        return c;
    }

    static Config designAsWritten(Config c) {
        Config d = c.copy();
        d.holeRereadOnProbe = false;
        d.name = c.name + "(as written: late commits only via hole read)";
        return d;
    }

    static void print(ModelExplorer.Result r) {
        System.out.println("[P0.5] " + r.headline());
        r.firstViolationByInvariant().values().forEach(t -> System.out.println("[P0.5] " + t));
    }

    @Test
    void exhaustive_with_hole_reread_all_invariants_hold() {
        for (Config c : List.of(small(), jump(), coreNoJump(), pages(), deadLetter(), core())) {
            var r = ModelExplorer.bfs(c, MAX_STATES, true);
            print(r);
            assertThat(r.firstViolationByInvariant()).as(r.headline()).isEmpty();
        }
    }

    @Test
    void random_walks_with_hole_reread_all_invariants_hold() {
        for (Config c : List.of(core(), pages(), large())) {
            var r = ModelExplorer.randomWalks(c, 1, SEEDS, 60);
            System.out.println("[P0.5] " + r.headline());
            r.firstViolationByInvariant().values().forEach(t -> System.out.println("[P0.5] " + t));
            assertThat(r.firstViolationByInvariant()).as(r.headline()).isEmpty();
        }
    }

    /** Finding F1: as written (§4.2, §7.1), FRESH breaks per-aggregate order (I2). */
    @Test
    void design_as_written_fresh_breaks_per_aggregate_order() {
        var r = ModelExplorer.bfs(designAsWritten(core()), MAX_STATES, false);
        print(r);
        assertThat(r.firstViolationByInvariant()).containsOnlyKeys("I2");
    }

    /**
     * F1, long-writer shape: the candidate covering A#0's hole cannot retire while an unrelated writer runs, so
     * publishing late commits ahead of the batch in the same probe would not help either.
     */
    @Test
    void design_as_written_fresh_long_writer_scenario() {
        Config c = new Config("F1-long-writer")
                .writer(WriterSpec.of(1, 0))  // w0: long writer, unrelated aggregate
                .writer(WriterSpec.of(0, 0))  // w1: A#0
                .writer(WriterSpec.of(1, 0))  // w2: commits above A#0's value
                .writer(WriterSpec.of(0, 0))  // w3: A#1
                .sub(Mode.FRESH, Progress.PERIODIC, ALL);
        c.maxCrashes = 0;
        c.maxJumps = 0;
        c.bufferCap = 4;
        String[] steps = {"s0.attach", "s0.bridgeRead(0..0]", "w0.xid", "w0.insert", "w1.xid", "w1.insert", "w2.xid", "w2.insert", "w2.commit",
                          "reader.probe.snapshot", "reader.probe.runningSet", "w1.commit", "w3.xid", "w3.insert", "w3.commit", "reader.probe.snapshot", "reader.probe.runningSet", "s0.handle", "s0.handle",
                          "w0.commit", "reader.probe.snapshot", "reader.probe.runningSet", "reader.holeRead", "s0.handle"};
        State asWritten = ModelExplorer.run(designAsWritten(c), steps);
        System.out.println("[P0.5] " + ModelExplorer.render(designAsWritten(c), List.of(steps), "expected I2 (as written)"));
        assertThat(String.join("\n", asWritten.transitionViolations)).contains("I2");
        State withReread = ModelExplorer.run(c, steps);
        System.out.println("[P0.5] " + ModelExplorer.render(c, List.of(steps), "none expected (hole re-read)"));
        assertThat(SubscriptionModel.violations(withReread)).isEmpty();
    }

    /**
     * Finding F3: with §6's literal attach rule (within buffer distance of the reader's cursor), a FRESH subscription
     * that is behind cannot attach while a long writer holds the watermark down, because catch-up stops at the
     * watermark; events above the hole are not delivered to it until the writer ends.
     */
    @Test
    void attach_rule_relative_to_cursor_starves_fresh_behind_a_long_writer() {
        Config c = new Config("F3-attach-distance")
                .writer(WriterSpec.of(1, 0))  // w0: long writer
                .writer(WriterSpec.of(0, 0))
                .writer(WriterSpec.of(0, 0))
                .writer(WriterSpec.of(0, 0))
                .sub(Mode.FRESH, Progress.PERIODIC, ALL);
        c.maxCrashes = 0;
        c.maxJumps = 0;
        c.attachDistance = 1;
        c.attachRule = AttachRule.DISTANCE_TO_CURSOR;
        String[] steps = {"w0.xid", "w0.insert", "w1.xid", "w1.insert", "w1.commit", "w2.xid", "w2.insert", "w2.commit", "w3.xid", "w3.insert", "w3.commit",
                          "reader.probe.snapshot", "reader.probe.runningSet", "reader.probe.snapshot", "reader.probe.runningSet"};
        State s = ModelExplorer.run(c, steps);
        System.out.println("[P0.5] F3 state: " + s.summary());
        var labels = SubscriptionModel.actions(s, false).stream().map(Action::label).toList();
        System.out.println("[P0.5] F3 enabled engine steps: " + labels);
        assertThat(labels).noneMatch(l -> l.startsWith("s0."));
        assertThat(s.subs[0].handled).isEmpty();
        Config atW = c.copy();
        atW.attachRule = AttachRule.AT_WATERMARK;
        State s2 = ModelExplorer.run(atW, steps);
        var labels2 = SubscriptionModel.actions(s2, false).stream().map(Action::label).toList();
        System.out.println("[P0.5] F3 with attach at the watermark, enabled engine steps: " + labels2);
        assertThat(labels2).contains("s0.attach");
    }

    static Config baseFor(Mutant m) {
        return switch (m) {
            case PAGE_FALLBACK_PARTIAL_COMMIT -> pages();
            case WATERMARK_BEFORE_HOLE_READ, CACHED_STAT_ACTIVITY -> small();
            default -> core();
        };
    }

    @ParameterizedTest
    @EnumSource(Mutant.class)
    void every_mutant_is_caught(Mutant m) {
        Config c = baseFor(m).with(m);
        var r = ModelExplorer.bfs(c, MUTANT_MAX_STATES, false);
        print(r);
        assertThat(r.firstViolationByInvariant()).as(r.headline()).isNotEmpty();
    }
}
