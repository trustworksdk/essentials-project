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

import java.util.*;
import java.util.function.Consumer;

/**
 * Phase-0 spike P0.5: an explicit, deterministic state-machine model of the redesigned subscription engine
 * (docs/plans/event-store-subscription-redesign.md, sections 4, 6, 7, 8, 10.1 and 16). No engine code exists;
 * this model exists to find design flaws before any is written. See docs/plans/event-store-phase0-model.md.
 * <p>
 * Every step is atomic. A {@link State} is a value: {@link #actions} lists the enabled steps, each applied to a
 * copy. Invariant checks run after every step ({@link #violations}); I1 (no loss) is checked by draining a copy to
 * quiescence once every writer has finished ({@link #drainAndCheckNoLoss}).
 */
final class SubscriptionModel {

    enum Mode {FRESH, CONTIGUOUS}

    enum Progress {PERIODIC, IN_TX}

    /**
     * When a detached subscription may attach to the tail reader. §6 says "within its buffer's distance of the
     * tail"; AT_WATERMARK is the rule the model needs for liveness (see the model document, finding F3).
     */
    enum AttachRule {AT_WATERMARK, DISTANCE_TO_CURSOR}

    /** Deliberately broken variants. Each must be caught by at least one invariant. */
    enum Mutant {
        /** Writer takes its global_order before its xid (§4.3 prerequisite 1 violated). */
        VALUE_BEFORE_XID,
        /** Writer takes its global_order before deriving event_order from committed state (§4.5 / §5.1 violated). */
        VALUE_BEFORE_EVENT_ORDER,
        /** The probe reads the running set X before the tail read S, as two statements (§4.10 reversed). */
        X_BEFORE_S,
        /** The probe runs in a transaction whose pg_stat_activity snapshot was cached by an earlier probe (P0.2). */
        CACHED_STAT_ACTIVITY,
        /** An empty tail read returns no running set and the reader treats that as "nobody running" (§4.10). */
        EMPTY_TAIL_NO_RUNNING_SET,
        /** The watermark advances when a candidate retires, before its hole read has run (§4.2). */
        WATERMARK_BEFORE_HOLE_READ,
        /** A restarted tail reader starts at the head with the watermark at the head and no initial candidate. */
        READER_RESTART_AT_HEAD,
        /** The resume point is the highest handled order + 1 instead of §4.7's formula. */
        RESUME_AT_HIGHEST_HANDLED,
        /** The resume point is the reader's cursor + 1 instead of §4.7's formula. */
        RESUME_AT_CURSOR,
        /** On overflow, catch-up resumes from the current watermark, ignoring buffered, unhandled events. */
        DETACH_AT_WATERMARK,
        /** Attach reads up to the head first and registers with the reader afterwards (§6 "attach first" reversed). */
        ATTACH_AFTER_BRIDGE_READ,
        /** Filtered catch-up reads above the watermark (up to the head) and advances there on a short page (§10.1). */
        CATCHUP_ABOVE_WATERMARK,
        /** CONTIGUOUS delivers buffered events above the watermark (§7.1). */
        CONTIGUOUS_ABOVE_WATERMARK,
        /** CONTIGUOUS applies its projection per event but saves progress periodically, outside that transaction (§8). */
        CONTIGUOUS_PROGRESS_OUTSIDE_TX,
        /** On a failing event, the page commits the projection of the events before it, then replays per event (§8.3). */
        PAGE_FALLBACK_PARTIAL_COMMIT,
        /** A full subscriber buffer blocks the reader instead of detaching the subscriber (§6, I9). */
        BACKPRESSURE
    }

    /** Most events one writer (one transaction) may insert; event ids are {@code writer * MAX_EVENTS + k}. */
    static final int MAX_EVENTS = 4;

    /**
     * One writer is one transaction. It inserts {@code events} events, each in its own step with its own value from the
     * shared sequence (other writers' values can interleave), all committing or aborting together. Every event goes to
     * {@code agg}, except the last when {@code agg2 >= 0} (two aggregates in one unit of work, §5.2). {@code failures}
     * applies to the writer's first event only.
     */
    record WriterSpec(int agg, int type, boolean mayAbort, int failures, int events, int agg2) {
        static WriterSpec of(int agg, int type) {
            return new WriterSpec(agg, type, false, 0, 1, -1);
        }

        WriterSpec abortable() {
            return new WriterSpec(agg, type, true, failures, events, agg2);
        }

        /** The handler of an in-transaction subscriber throws on this writer's first event's first {@code n} attempts. */
        WriterSpec failing(int n) {
            return new WriterSpec(agg, type, mayAbort, n, events, agg2);
        }

        /** The writer inserts {@code n} events in one transaction. */
        WriterSpec events(int n) {
            if (n < 1 || n > MAX_EVENTS) throw new IllegalArgumentException("events must be 1.." + MAX_EVENTS);
            return new WriterSpec(agg, type, mayAbort, failures, n, agg2);
        }

        /** The writer's last event goes to aggregate {@code other}. */
        WriterSpec lastEventOn(int other) {
            if (events < 2) throw new IllegalArgumentException("lastEventOn needs events >= 2");
            return new WriterSpec(agg, type, mayAbort, failures, events, other);
        }

        int aggOf(int k) {
            return agg2 >= 0 && k == events - 1 ? agg2 : agg;
        }

        @Override
        public String toString() {
            return "W(" + (char) ('A' + agg) + (agg2 >= 0 ? "+" + (char) ('A' + agg2) : "") + ",type=" + type + (events > 1 ? ",events=" + events : "") +
                    (mayAbort ? ",abortable" : "") + (failures > 0 ? ",failing=" + failures : "") + ")";
        }
    }

    record SubSpec(Mode mode, Progress progress, int typeMask) {
        boolean matches(int type) {
            return (typeMask & (1 << type)) != 0;
        }

        @Override
        public String toString() {
            return mode + "/" + progress + "/types=" + Integer.toBinaryString(typeMask);
        }
    }

    static final class Config {
        String             name;
        final List<WriterSpec> writers = new ArrayList<>();
        final List<SubSpec>    subs    = new ArrayList<>();
        int tailLimit      = 2;
        int bufferCap      = 2;
        int pageSize       = 2;
        int catchUpLimit   = 1;
        int attachDistance = 1;
        int maxCrashes     = 1;
        int maxJumps       = 1;
        int jumpSize       = 2;
        int maxAttempts    = 2;
        int aggregates     = 2;
        /** Design alternative (finding F1): the tail probe also re-reads outstanding holes. */
        boolean    holeRereadOnProbe = true;
        AttachRule attachRule        = AttachRule.AT_WATERMARK;
        EnumSet<Mutant> mutants = EnumSet.noneOf(Mutant.class);

        Config(String name) {
            this.name = name;
        }

        Config writer(WriterSpec w) {
            writers.add(w);
            return this;
        }

        Config sub(Mode m, Progress p, int typeMask) {
            subs.add(new SubSpec(m, p, typeMask));
            return this;
        }

        Config with(Mutant m) {
            Config c = copy();
            c.mutants.add(m);
            c.name = name + "+" + m;
            return c;
        }

        Config copy() {
            Config c = new Config(name);
            c.writers.addAll(writers);
            c.subs.addAll(subs);
            c.tailLimit = tailLimit;
            c.bufferCap = bufferCap;
            c.pageSize = pageSize;
            c.catchUpLimit = catchUpLimit;
            c.attachDistance = attachDistance;
            c.maxCrashes = maxCrashes;
            c.maxJumps = maxJumps;
            c.jumpSize = jumpSize;
            c.maxAttempts = maxAttempts;
            c.aggregates = aggregates;
            c.holeRereadOnProbe = holeRereadOnProbe;
            c.attachRule = attachRule;
            c.mutants = EnumSet.copyOf(mutants.isEmpty() ? EnumSet.noneOf(Mutant.class) : mutants);
            return c;
        }

        boolean has(Mutant m) {
            return mutants.contains(m);
        }

        Progress progress(SubSpec s) {
            return s.mode() == Mode.CONTIGUOUS && has(Mutant.CONTIGUOUS_PROGRESS_OUTSIDE_TX) ? Progress.PERIODIC : s.progress();
        }

        @Override
        public String toString() {
            return name + " {writers=" + writers + ", subs=" + subs + ", tailLimit=" + tailLimit + ", bufferCap=" + bufferCap +
                    ", pageSize=" + pageSize + ", catchUpLimit=" + catchUpLimit + ", crashes<=" + maxCrashes + ", jumps<=" + maxJumps +
                    "x" + jumpSize + ", holeReread=" + holeRereadOnProbe + ", attach=" + attachRule + ", mutants=" + mutants + "}";
        }
    }

    record Action(String label, Consumer<State> effect) {
    }

    // ------------------------------------------------------------------------------------------------------------
    // State
    // ------------------------------------------------------------------------------------------------------------

    static final int DETACHED = 0, ATTACHING = 1, ATTACHED = 2, BRIDGED_UNREGISTERED = 3;
    static final int W_NEW = 0, W_STEP1 = 1, W_INSERTED = 2, W_COMMITTED = 3, W_ABORTED = 4;

    static final class Sub {
        int                       state;
        int                       catchFrom;      // every matching order <= catchFrom is handled or buffered (detached)
        int                       attachCursor;
        List<Integer>             buffer   = new ArrayList<>();
        List<Integer>             inflight = new ArrayList<>();
        boolean                   inflightDeadLetter;
        int                       rp       = 1;   // durable resume point: resume from and including
        TreeMap<Integer, Integer> proj     = new TreeMap<>(); // durable projection: order -> times applied
        TreeSet<Integer>          handled  = new TreeSet<>(); // side effects done (periodic subscribers)
        TreeSet<Integer>          dl       = new TreeSet<>(); // durable dead letters
        int                       lastApplied;    // CONTIGUOUS: highest order applied in order (memory)
        int                       highestHandled;
        int                       perEventUntil;  // page fallback active until this order is committed
        int[]                     attempts;
        int[]                     maxEo;          // I2 tracking per incarnation
        TreeSet<Integer>          deliveredInc = new TreeSet<>();

        Sub(int writers, int aggregates) {
            attempts = new int[writers];
            maxEo = new int[aggregates];
            Arrays.fill(maxEo, -1);
        }

        Sub(Sub o) {
            state = o.state;
            catchFrom = o.catchFrom;
            attachCursor = o.attachCursor;
            buffer = new ArrayList<>(o.buffer);
            inflight = new ArrayList<>(o.inflight);
            inflightDeadLetter = o.inflightDeadLetter;
            rp = o.rp;
            proj = new TreeMap<>(o.proj);
            handled = new TreeSet<>(o.handled);
            dl = new TreeSet<>(o.dl);
            lastApplied = o.lastApplied;
            highestHandled = o.highestHandled;
            perEventUntil = o.perEventUntil;
            attempts = o.attempts.clone();
            maxEo = o.maxEo.clone();
            deliveredInc = new TreeSet<>(o.deliveredInc);
        }
    }

    static final class State {
        final Config c;
        int seq = 1, nextXid = 1, crashes, jumps;
        int[] wPhase, wXid, wIns;  // per writer; wIns = events inserted so far
        int[] wG, wEo;             // per event id (writer * MAX_EVENTS + k)
        TreeMap<Integer, Integer> committed = new TreeMap<>(); // global_order -> event id
        // tail reader (one per aggregate type per JVM)
        int              cursor, wm;
        boolean          initialized = true;
        TreeSet<Integer> holes        = new TreeSet<>();
        TreeSet<Integer> pendingHoles = new TreeSet<>();
        boolean          holeReadPending;
        List<int[]>      cands = new ArrayList<>(); // {S, X as writer bitmask, initial?1:0}
        int              stashX = -1, cachedX = -1;
        boolean          stashed;   // the probe statement has taken its snapshot, running set not yet read
        List<Integer>    stashTail = new ArrayList<>(), stashLate = new ArrayList<>();
        Sub[]            subs;
        List<String>     transitionViolations = new ArrayList<>(); // not part of the key
        String           key;

        State(Config c) {
            this.c = c;
            int n = c.writers.size();
            wPhase = new int[n];
            wXid = new int[n];
            wIns = new int[n];
            wG = new int[n * MAX_EVENTS];
            wEo = new int[n * MAX_EVENTS];
            Arrays.fill(wEo, -1);
            subs = new Sub[c.subs.size()];
            for (int i = 0; i < subs.length; i++) subs[i] = new Sub(n * MAX_EVENTS, c.aggregates);
        }

        State(State o) {
            c = o.c;
            seq = o.seq;
            nextXid = o.nextXid;
            crashes = o.crashes;
            jumps = o.jumps;
            wPhase = o.wPhase.clone();
            wXid = o.wXid.clone();
            wIns = o.wIns.clone();
            wG = o.wG.clone();
            wEo = o.wEo.clone();
            committed = new TreeMap<>(o.committed);
            cursor = o.cursor;
            wm = o.wm;
            initialized = o.initialized;
            holes = new TreeSet<>(o.holes);
            pendingHoles = new TreeSet<>(o.pendingHoles);
            holeReadPending = o.holeReadPending;
            cands = new ArrayList<>();
            for (int[] cd : o.cands) cands.add(cd.clone());
            stashX = o.stashX;
            cachedX = o.cachedX;
            stashed = o.stashed;
            stashTail = new ArrayList<>(o.stashTail);
            stashLate = new ArrayList<>(o.stashLate);
            subs = new Sub[o.subs.length];
            for (int i = 0; i < subs.length; i++) subs[i] = new Sub(o.subs[i]);
        }

        String key() {
            if (key != null) return key;
            var b = new StringBuilder(256);
            b.append(seq).append(',').append(nextXid).append(',').append(crashes).append(',').append(jumps).append('|');
            for (int w = 0; w < wPhase.length; w++) {
                b.append(wPhase[w]).append(':').append(wXid[w]);
                for (int k = 0; k < c.writers.get(w).events(); k++) b.append(':').append(wG[w * MAX_EVENTS + k]).append(':').append(wEo[w * MAX_EVENTS + k]);
                b.append(';');
            }
            b.append('|').append(committed.keySet()).append('|').append(cursor).append(',').append(wm).append(',').append(initialized)
             .append(holes).append(pendingHoles).append(holeReadPending).append(',').append(stashX).append(',').append(cachedX).append(stashed).append(stashTail).append(stashLate);
            for (int[] cd : cands) b.append('(').append(cd[0]).append(',').append(cd[1]).append(',').append(cd[2]).append(')');
            for (Sub s : subs) {
                b.append("|S").append(s.state).append(',').append(s.catchFrom).append(',').append(s.attachCursor).append(s.buffer).append(s.inflight)
                 .append(s.inflightDeadLetter).append(',').append(s.rp).append(s.proj).append(s.handled).append(s.dl).append(',').append(s.lastApplied)
                 .append(',').append(s.highestHandled).append(',').append(s.perEventUntil).append(Arrays.toString(s.attempts)).append(Arrays.toString(s.maxEo))
                 .append(s.deliveredInc);
            }
            key = b.toString();
            return key;
        }

        boolean writersDone() {
            for (int p : wPhase) if (p < W_COMMITTED) return false;
            return true;
        }

        int runningMask() {
            int m = 0;
            for (int w = 0; w < wPhase.length; w++) if (wXid[w] > 0 && wPhase[w] < W_COMMITTED) m |= 1 << w;
            return m;
        }

        /** The unfinished writer holding global_order g, or -1. */
        int holder(int g) {
            for (int w = 0; w < wPhase.length; w++) {
                if (wPhase[w] >= W_COMMITTED) continue;
                for (int k = 0; k < c.writers.get(w).events(); k++) if (wG[w * MAX_EVENTS + k] == g) return w;
            }
            return -1;
        }

        WriterSpec writerOfEvent(int e) {
            return c.writers.get(e / MAX_EVENTS);
        }

        int aggOfEvent(int e) {
            return writerOfEvent(e).aggOf(e % MAX_EVENTS);
        }

        boolean matches(int sub, int g) {
            Integer e = committed.get(g);
            return e != null && c.subs.get(sub).matches(writerOfEvent(e).type());
        }

        String summary() {
            var b = new StringBuilder();
            b.append("seq=").append(seq).append(" writers=[");
            for (int w = 0; w < wPhase.length; w++) {
                if (w > 0) b.append(' ');
                var spec = c.writers.get(w);
                b.append('w').append(w).append(':').append(switch (wPhase[w]) {
                    case W_NEW -> "new";
                    case W_STEP1 -> "s1";
                    case W_INSERTED -> "ins";
                    case W_COMMITTED -> "C";
                    default -> "A";
                });
                for (int k = 0; k < spec.events(); k++) {
                    int e = w * MAX_EVENTS + k;
                    if (wG[e] > 0) b.append(k == 0 ? "@g" : ",g").append(wG[e]);
                    if (wEo[e] >= 0) b.append("=").append((char) ('A' + spec.aggOf(k))).append('#').append(wEo[e]);
                }
                if (wXid[w] > 0) b.append("/x").append(wXid[w]);
            }
            b.append("] committed=").append(committed.keySet());
            b.append(" | reader cursor=").append(cursor).append(" W=").append(wm).append(initialized ? "" : "(uninit)")
             .append(" holes=").append(holes);
            if (!pendingHoles.isEmpty() || holeReadPending) b.append(" pendingHoleRead=").append(pendingHoles);
            if (!cands.isEmpty()) {
                b.append(" cands=");
                for (int[] cd : cands) b.append("(S=").append(cd[0]).append(",X=").append(maskStr(cd[1])).append(cd[2] == 1 ? ",init" : "").append(')');
            }
            for (int i = 0; i < subs.length; i++) {
                Sub s = subs[i];
                b.append(" | s").append(i).append(':').append(switch (s.state) {
                    case DETACHED -> "detached";
                    case ATTACHING -> "attaching";
                    case ATTACHED -> "attached";
                    default -> "bridged";
                }).append(" from=").append(s.catchFrom).append(" buf=").append(s.buffer);
                if (!s.inflight.isEmpty()) b.append(" tx=").append(s.inflight).append(s.inflightDeadLetter ? "(DL)" : "");
                b.append(" rp=").append(s.rp);
                if (!s.proj.isEmpty()) b.append(" proj=").append(s.proj);
                if (!s.handled.isEmpty()) b.append(" handled=").append(s.handled);
                if (!s.dl.isEmpty()) b.append(" dl=").append(s.dl);
            }
            return b.toString();
        }

        private String maskStr(int mask) {
            var b = new StringBuilder("{");
            for (int w = 0; w < 31; w++) if ((mask & (1 << w)) != 0) b.append(b.length() > 1 ? "," : "").append('w').append(w);
            return b.append('}').toString();
        }
    }

    static State initial(Config c) {
        return new State(c);
    }

    // ------------------------------------------------------------------------------------------------------------
    // Enabled steps
    // ------------------------------------------------------------------------------------------------------------

    /**
     * @param environment include writer steps, crashes and sequence jumps (false while draining to quiescence)
     */
    static List<Action> actions(State s, boolean environment) {
        Config c = s.c;
        var out = new ArrayList<Action>();
        if (environment) {
            for (int w = 0; w < s.wPhase.length; w++) {
                final int ww = w;
                switch (s.wPhase[w]) {
                    case W_NEW -> out.add(new Action("w" + w + "." + step1Label(c), t -> writerStep1(t, ww)));
                    case W_STEP1 -> out.add(new Action("w" + w + "." + step2Label(c) + (s.wIns[w] > 0 ? String.valueOf(s.wIns[w] + 1) : ""), t -> writerStep2(t, ww)));
                    case W_INSERTED -> {
                        out.add(new Action("w" + w + ".commit", t -> writerCommit(t, ww)));
                        if (c.writers.get(w).mayAbort()) out.add(new Action("w" + w + ".abort", t -> t.wPhase[ww] = W_ABORTED));
                    }
                    default -> {
                    }
                }
            }
            if (s.crashes < c.maxCrashes) out.add(new Action("CRASH+restart", SubscriptionModel::crash));
            if (s.jumps < c.maxJumps) out.add(new Action("sequenceJump(+" + c.jumpSize + ")", t -> {
                t.seq += c.jumpSize;
                t.jumps++;
            }));
        }
        // the tail reader: a single thread
        if (s.holeReadPending) {
            out.add(new Action("reader.holeRead", SubscriptionModel::holeRead));
        } else if (c.has(Mutant.X_BEFORE_S) && s.stashX >= 0) {
            out.add(new Action("reader.tailRead(after X)", t -> {
                int x = t.stashX;
                t.stashX = -1;
                snapshot(t);
                probe(t, x);
            }));
        } else if (s.stashed) {
            out.add(new Action("reader.probe.runningSet", t -> probe(t, null)));
        } else if (probeEnabled(s)) {
            if (c.has(Mutant.X_BEFORE_S)) out.add(new Action("reader.readRunningSet", t -> t.stashX = t.runningMask()));
            else out.add(new Action("reader.probe.snapshot", SubscriptionModel::snapshot));
        }
        for (int i = 0; i < s.subs.length; i++) subActions(s, i, out);
        return out;
    }

    private static String step1Label(Config c) {
        if (c.has(Mutant.VALUE_BEFORE_XID)) return "nextval";
        if (c.has(Mutant.VALUE_BEFORE_EVENT_ORDER)) return "xid+nextval";
        return "xid";
    }

    private static String step2Label(Config c) {
        if (c.has(Mutant.VALUE_BEFORE_XID)) return "xid+insert";
        if (c.has(Mutant.VALUE_BEFORE_EVENT_ORDER)) return "deriveEventOrder+insert";
        return "insert";
    }

    private static int headEo(State s, int agg) {
        int max = -1;
        for (int e : s.committed.values()) if (s.aggOfEvent(e) == agg) max = Math.max(max, s.wEo[e]);
        return max;
    }

    /** A writer's xid (the mutants take the first event's value here instead of, or as well as, the xid). */
    private static void writerStep1(State s, int w) {
        int e0 = w * MAX_EVENTS;
        if (s.c.has(Mutant.VALUE_BEFORE_XID)) {
            s.wG[e0] = s.seq++;
        } else if (s.c.has(Mutant.VALUE_BEFORE_EVENT_ORDER)) {
            s.wXid[w] = s.nextXid++;
            s.wG[e0] = s.seq++;
        } else {
            s.wXid[w] = s.nextXid++;
        }
        s.wPhase[w] = W_STEP1;
    }

    /**
     * One insert: the writer's next event. The first event of an aggregate derives its event_order from committed
     * state (§5.1); a later one of the same aggregate in the same transaction follows its own previous one, as the
     * statement's {@code ok.v + n} does. Each event takes its own identity value, so one transaction's values need not
     * be consecutive. The mutants apply to the first event.
     */
    private static void writerStep2(State s, int w) {
        WriterSpec spec = s.c.writers.get(w);
        int k = s.wIns[w];
        int e = w * MAX_EVENTS + k;
        int agg = spec.aggOf(k);
        int eo = headEo(s, agg) + 1;
        for (int j = 0; j < k; j++) if (spec.aggOf(j) == agg) eo = Math.max(eo, s.wEo[w * MAX_EVENTS + j] + 1);
        if (k == 0 && s.c.has(Mutant.VALUE_BEFORE_XID)) {
            s.wXid[w] = s.nextXid++;
            s.wEo[e] = eo;
        } else if (k == 0 && s.c.has(Mutant.VALUE_BEFORE_EVENT_ORDER)) {
            s.wEo[e] = eo;
        } else {
            // §5.1: event_order derived from committed state, then the identity value, in one statement
            s.wEo[e] = eo;
            s.wG[e] = s.seq++;
        }
        s.wIns[w] = k + 1;
        if (s.wIns[w] == spec.events()) s.wPhase[w] = W_INSERTED;
    }

    /** Commits all of a writer's events, or aborts all of them on a unique (aggregate_id, event_order) violation. */
    private static void writerCommit(State s, int w) {
        WriterSpec spec = s.c.writers.get(w);
        for (int k = 0; k < spec.events(); k++) {
            int e = w * MAX_EVENTS + k;
            for (int v : s.committed.values()) {
                if (s.aggOfEvent(v) == spec.aggOf(k) && s.wEo[v] == s.wEo[e]) {
                    s.wPhase[w] = W_ABORTED;
                    return;
                }
            }
        }
        s.wPhase[w] = W_COMMITTED;
        for (int k = 0; k < spec.events(); k++) s.committed.put(s.wG[w * MAX_EVENTS + k], w * MAX_EVENTS + k);
    }

    private static boolean backpressureBlocked(State s) {
        if (!s.c.has(Mutant.BACKPRESSURE)) return false;
        for (Sub sub : s.subs) if ((sub.state == ATTACHED || sub.state == ATTACHING) && sub.buffer.size() >= s.c.bufferCap) return true;
        return false;
    }

    private static boolean probeEnabled(State s) {
        if (s.holeReadPending || s.stashed || backpressureBlocked(s)) return false;
        if (s.committed.higherKey(s.cursor) != null || !s.cands.isEmpty()) return true;
        if (s.c.holeRereadOnProbe) for (int h : s.holes) if (s.committed.containsKey(h)) return true;
        return false;
    }

    /**
     * First half of the combined probe of §4.10: the statement's MVCC snapshot (tail rows above the cursor, and with
     * the hole re-read variant the outstanding holes that are now visible). P0.2 showed the running set is read after
     * this snapshot, within the same statement, so a writer can commit in between: that is the next step.
     */
    private static void snapshot(State s) {
        Config c = s.c;
        s.stashTail.clear();
        s.stashLate.clear();
        for (int g : s.committed.tailMap(s.cursor, false).keySet()) {
            if (s.stashTail.size() >= c.tailLimit) break;
            s.stashTail.add(g);
        }
        if (c.holeRereadOnProbe) for (int h : s.holes) if (s.committed.containsKey(h)) s.stashLate.add(h);
        s.stashed = true;
    }

    /** Second half: the running set X, then cursor, holes, publication, retirement and new candidates. */
    private static void probe(State s, Integer forcedX) {
        Config c = s.c;
        var rows = new ArrayList<>(s.stashTail);
        var late = new ArrayList<>(s.stashLate);
        s.stashed = false;
        s.stashTail.clear();
        s.stashLate.clear();
        late.forEach(s.holes::remove);
        int running = forcedX != null ? forcedX : s.runningMask();
        if (c.has(Mutant.CACHED_STAT_ACTIVITY)) {
            if (s.cachedX < 0) s.cachedX = running;
            else {
                running = s.cachedX;
                s.cachedX = -1;
            }
        }
        if (c.has(Mutant.EMPTY_TAIL_NO_RUNNING_SET) && rows.isEmpty() && late.isEmpty()) running = 0;
        for (int g : rows) {
            for (int v = s.cursor + 1; v < g; v++) s.holes.add(v);
            s.cursor = g;
        }
        for (int g : late) publish(s, g);
        for (int g : rows) publish(s, g);
        // retirement by set disjointness
        int maxRetired = -1;
        for (Iterator<int[]> it = s.cands.iterator(); it.hasNext(); ) {
            int[] cd = it.next();
            if ((cd[1] & running) == 0) {
                if (cd[2] == 1) s.initialized = true;
                else maxRetired = Math.max(maxRetired, cd[0]);
                it.remove();
            }
        }
        if (maxRetired >= 0) {
            final int mr = maxRetired;
            s.cands.removeIf(cd -> cd[0] <= mr);
            var resolve = new TreeSet<>(s.holes.headSet(maxRetired, true));
            if (!resolve.isEmpty()) {
                s.holes.removeAll(resolve);
                s.pendingHoles.addAll(resolve);
                s.holeReadPending = true;
            }
        }
        // a new candidate when a hole is not yet covered by an outstanding candidate
        int covered = 0;
        for (int[] cd : s.cands) covered = Math.max(covered, cd[0]);
        if (!s.holes.isEmpty() && s.holes.last() > covered) s.cands.add(new int[]{s.cursor, running, 0});
        recomputeWatermark(s);
    }

    private static void holeRead(State s) {
        var late = new ArrayList<Integer>();
        for (int h : s.pendingHoles) if (s.committed.containsKey(h)) late.add(h);
        s.pendingHoles.clear();
        s.holeReadPending = false;
        for (int g : late) publish(s, g);
        recomputeWatermark(s);
    }

    private static void recomputeWatermark(State s) {
        if (!s.initialized) return;
        int low = s.cursor;
        if (!s.holes.isEmpty()) low = Math.min(low, s.holes.first() - 1);
        if (!s.c.has(Mutant.WATERMARK_BEFORE_HOLE_READ) && !s.pendingHoles.isEmpty()) low = Math.min(low, s.pendingHoles.first() - 1);
        if (low < s.wm) s.transitionViolations.add("M0: watermark moved backwards " + s.wm + " -> " + low);
        s.wm = low;
    }

    private static void crash(State s) {
        s.crashes++;
        // tail reader restarts at the head with one initial candidate covering everything below it
        s.cursor = s.committed.isEmpty() ? 0 : s.committed.lastKey();
        s.holes.clear();
        s.pendingHoles.clear();
        s.holeReadPending = false;
        s.cands.clear();
        s.stashX = -1;
        s.cachedX = -1;
        s.stashed = false;
        s.stashTail.clear();
        s.stashLate.clear();
        int running = s.runningMask();
        if (running == 0 || s.c.has(Mutant.READER_RESTART_AT_HEAD)) {
            s.initialized = true;
            s.wm = s.cursor;
        } else {
            s.initialized = false;
            s.wm = 0;
            s.cands.add(new int[]{s.cursor, running, 1});
        }
        for (int i = 0; i < s.subs.length; i++) {
            Sub old = s.subs[i];
            Sub n = new Sub(s.wPhase.length * MAX_EVENTS, s.c.aggregates);
            n.rp = old.rp;
            n.proj = old.proj;
            n.handled = old.handled;
            n.dl = old.dl;
            n.catchFrom = old.rp - 1;
            n.lastApplied = s.c.subs.get(i).mode() == Mode.CONTIGUOUS ? old.rp - 1 : 0;
            n.highestHandled = old.rp - 1;
            s.subs[i] = n;
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // Subscriptions
    // ------------------------------------------------------------------------------------------------------------

    private static void publish(State s, int g) {
        for (int i = 0; i < s.subs.length; i++) {
            Sub sub = s.subs[i];
            if (sub.state != ATTACHED && sub.state != ATTACHING) continue;
            if (!s.matches(i, g)) continue;
            offer(s, i, g, true);
        }
    }

    private static void offer(State s, int i, int g, boolean bounded) {
        Sub sub = s.subs[i];
        boolean contiguous = s.c.subs.get(i).mode() == Mode.CONTIGUOUS;
        if (contiguous && (g <= sub.lastApplied || sub.buffer.contains(g) || sub.inflight.contains(g))) return;
        if (bounded && sub.buffer.size() >= s.c.bufferCap) {
            overflow(s, i, g);
            return;
        }
        if (contiguous) {
            sub.buffer.add(g);
            Collections.sort(sub.buffer);
        } else sub.buffer.add(g);
    }

    /** §6: a full buffer detaches the subscription; it goes back to catch-up. */
    private static void overflow(State s, int i, int dropped) {
        Sub sub = s.subs[i];
        if (s.c.has(Mutant.DETACH_AT_WATERMARK)) sub.catchFrom = Math.max(sub.catchFrom, s.wm);
        else sub.catchFrom = safePos(s, i, dropped) - 1;
        sub.state = DETACHED;
        sub.buffer.clear();
    }

    /** §4.7: min(watermark + 1, lowest delivered-but-not-yet-handled order); inflight pages are excluded. */
    static int safePos(State s, int i, int extra) {
        Sub sub = s.subs[i];
        int pos = sub.state == ATTACHED ? Math.max(s.wm, sub.catchFrom) + 1 : sub.catchFrom + 1;
        for (int g : sub.buffer) pos = Math.min(pos, g);
        if (extra > 0) pos = Math.min(pos, extra);
        return pos;
    }

    private static int resumePointToWrite(State s, int i) {
        Sub sub = s.subs[i];
        if (s.c.has(Mutant.RESUME_AT_HIGHEST_HANDLED)) return Math.max(sub.rp, sub.highestHandled + 1);
        if (s.c.has(Mutant.RESUME_AT_CURSOR)) return sub.state == ATTACHED ? s.cursor + 1 : sub.catchFrom + 1;
        return safePos(s, i, -1);
    }

    private static List<Integer> deliverable(State s, int i, int max) {
        Sub sub = s.subs[i];
        var out = new ArrayList<Integer>();
        if (sub.state == ATTACHING || sub.state == BRIDGED_UNREGISTERED) return out;
        boolean contiguous = s.c.subs.get(i).mode() == Mode.CONTIGUOUS;
        int ceiling = contiguous && !s.c.has(Mutant.CONTIGUOUS_ABOVE_WATERMARK) ? s.wm : Integer.MAX_VALUE;
        for (int g : sub.buffer) {
            if (out.size() >= max || g > ceiling) break;
            out.add(g);
        }
        return out;
    }

    private static void deliver(State s, int i, int g) {
        Sub sub = s.subs[i];
        if (s.c.subs.get(i).mode() == Mode.CONTIGUOUS && g > s.wm)
            s.transitionViolations.add("I12: s" + i + " (CONTIGUOUS) delivered g" + g + " above watermark " + s.wm);
        if (sub.deliveredInc.add(g)) {
            int e = s.committed.get(g);
            int agg = s.aggOfEvent(e);
            if (s.wEo[e] < sub.maxEo[agg])
                s.transitionViolations.add("I2: s" + i + " first-delivered " + (char) ('A' + agg) + "#" + s.wEo[e] + " (g" + g + ") after " +
                                           (char) ('A' + agg) + "#" + sub.maxEo[agg]);
            sub.maxEo[agg] = Math.max(sub.maxEo[agg], s.wEo[e]);
        }
    }

    private static void apply(State s, int i, int g) {
        Sub sub = s.subs[i];
        if (s.c.subs.get(i).mode() == Mode.CONTIGUOUS) {
            int last = Math.max(sub.proj.isEmpty() ? 0 : sub.proj.lastKey(), sub.dl.isEmpty() ? 0 : sub.dl.last());
            if (g <= last) s.transitionViolations.add("I12: s" + i + " applied g" + g + " after g" + last + " (not in global order)");
        }
        sub.proj.merge(g, 1, Integer::sum);
    }

    private static void subActions(State s, int i, List<Action> out) {
        Config c = s.c;
        Sub sub = s.subs[i];
        SubSpec spec = c.subs.get(i);
        String p = "s" + i + ".";
        switch (sub.state) {
            case DETACHED -> {
                if (sub.buffer.isEmpty() && sub.inflight.isEmpty()) {
                    int ceiling = c.has(Mutant.CATCHUP_ABOVE_WATERMARK) ? (s.committed.isEmpty() ? 0 : s.committed.lastKey()) : s.wm;
                    if (ceiling > sub.catchFrom) out.add(new Action(p + "catchUp(<=" + ceiling + ")", t -> catchUp(t, i, ceiling)));
                    boolean may = s.initialized && switch (c.attachRule) {
                        case AT_WATERMARK -> sub.catchFrom >= s.wm;
                        case DISTANCE_TO_CURSOR -> s.cursor - sub.catchFrom <= c.attachDistance;
                    };
                    if (may) out.add(new Action(p + "attach", t -> attach(t, i)));
                }
            }
            case ATTACHING -> out.add(new Action(p + "bridgeRead(" + sub.catchFrom + ".." + sub.attachCursor + "]", t -> bridge(t, i)));
            case BRIDGED_UNREGISTERED -> out.add(new Action(p + "register", t -> t.subs[i].state = ATTACHED));
            default -> {
            }
        }
        if (c.progress(spec) == Progress.PERIODIC) {
            if (!deliverable(s, i, 1).isEmpty()) out.add(new Action(p + "handle", t -> handle(t, i)));
            if (resumePointToWrite(s, i) != sub.rp) out.add(new Action(p + "saveResumePoint", t -> t.subs[i].rp = resumePointToWrite(t, i)));
        } else {
            if (sub.inflight.isEmpty()) {
                if (!deliverable(s, i, 1).isEmpty()) out.add(new Action(p + "pageBegin", t -> pageBegin(t, i)));
            } else out.add(new Action(p + "pageCommit", t -> pageCommit(t, i)));
        }
    }

    /** §10.1: filtered catch-up up to the watermark; a short page advances to the ceiling. */
    private static void catchUp(State s, int i, int ceiling) {
        Sub sub = s.subs[i];
        var rows = new ArrayList<Integer>();
        for (int g : s.committed.subMap(sub.catchFrom, false, ceiling, true).keySet()) {
            if (!s.matches(i, g)) continue;
            if (rows.size() >= s.c.catchUpLimit) break;
            rows.add(g);
        }
        for (int g : rows) offer(s, i, g, false);
        sub.catchFrom = rows.size() < s.c.catchUpLimit ? ceiling : rows.getLast();
    }

    /** §6: attach first, then read up to the head, then drain. */
    private static void attach(State s, int i) {
        Sub sub = s.subs[i];
        sub.attachCursor = s.cursor;
        if (s.c.has(Mutant.ATTACH_AFTER_BRIDGE_READ)) {
            bridgeRows(s, i);
            sub.state = BRIDGED_UNREGISTERED;
        } else sub.state = ATTACHING;
    }

    private static void bridge(State s, int i) {
        bridgeRows(s, i);
        Sub sub = s.subs[i];
        if (sub.state == ATTACHING) sub.state = ATTACHED;
        if (s.c.subs.get(i).mode() == Mode.CONTIGUOUS && sub.buffer.size() > s.c.bufferCap) overflow(s, i, -1);
    }

    private static void bridgeRows(State s, int i) {
        Sub sub = s.subs[i];
        var rows = new ArrayList<Integer>();
        if (sub.attachCursor > sub.catchFrom)
            for (int g : s.committed.subMap(sub.catchFrom, false, sub.attachCursor, true).keySet()) if (s.matches(i, g)) rows.add(g);
        if (s.c.subs.get(i).mode() == Mode.FRESH) sub.buffer.addAll(0, rows);
        else for (int g : rows) offer(s, i, g, false);
    }

    private static void handle(State s, int i) {
        Sub sub = s.subs[i];
        int g = deliverable(s, i, 1).getFirst();
        deliver(s, i, g);
        sub.buffer.remove(Integer.valueOf(g));
        sub.handled.add(g);
        sub.highestHandled = Math.max(sub.highestHandled, g);
        if (s.c.subs.get(i).mode() == Mode.CONTIGUOUS) {
            sub.lastApplied = g;
            apply(s, i, g); // projection committed per event; progress saved separately (mutant)
        }
    }

    /** §8.3: a page per transaction; a failing event rolls the page back and the page is replayed per event. */
    private static void pageBegin(State s, int i) {
        Sub sub = s.subs[i];
        int size = sub.perEventUntil > 0 ? 1 : s.c.pageSize;
        var page = deliverable(s, i, size);
        for (int g : page) deliver(s, i, g);
        int poisonIdx = -1;
        for (int k = 0; k < page.size(); k++) {
            int w = s.committed.get(page.get(k));
            if (sub.attempts[w] < failuresOf(s, w)) {
                poisonIdx = k;
                break;
            }
        }
        if (poisonIdx < 0) {
            sub.buffer.removeAll(page);
            sub.inflight.addAll(page);
            return;
        }
        int pg = page.get(poisonIdx);
        int w = s.committed.get(pg);
        sub.attempts[w]++;
        if (page.size() > 1) {
            if (s.c.has(Mutant.PAGE_FALLBACK_PARTIAL_COMMIT)) for (int k = 0; k < poisonIdx; k++) apply(s, i, page.get(k));
            sub.perEventUntil = pg; // page rolled back; events stay buffered
        } else if (sub.attempts[w] >= s.c.maxAttempts) {
            sub.buffer.remove(Integer.valueOf(pg));
            sub.inflight.add(pg);
            sub.inflightDeadLetter = true;
        }
        // else: per-event transaction rolled back, redelivered later
    }

    /** Event id e's handler failures: a writer's failures apply to its first event only. */
    private static int failuresOf(State s, int e) {
        return e % MAX_EVENTS == 0 ? s.writerOfEvent(e).failures() : 0;
    }

    private static void pageCommit(State s, int i) {
        Sub sub = s.subs[i];
        boolean contiguous = s.c.subs.get(i).mode() == Mode.CONTIGUOUS;
        for (int g : sub.inflight) {
            if (sub.inflightDeadLetter) sub.dl.add(g);
            else apply(s, i, g);
            if (contiguous) sub.lastApplied = Math.max(sub.lastApplied, g);
            sub.highestHandled = Math.max(sub.highestHandled, g);
            if (sub.perEventUntil == g) sub.perEventUntil = 0;
        }
        sub.inflight.clear();
        sub.inflightDeadLetter = false;
        sub.rp = resumePointToWrite(s, i); // in the same transaction as the projection
    }

    // ------------------------------------------------------------------------------------------------------------
    // Invariants
    // ------------------------------------------------------------------------------------------------------------

    /** Every invariant violated by this state, including those raised by the step that produced it. */
    static List<String> violations(State s) {
        var out = new ArrayList<>(s.transitionViolations);
        Config c = s.c;
        for (int i = 0; i < s.subs.length; i++) {
            Sub sub = s.subs[i];
            SubSpec spec = c.subs.get(i);
            // I3: the durable resume point never passes an order that could still be delivered
            for (int g = 1; g < sub.rp; g++) {
                int h = s.holder(g);
                if (h >= 0 && spec.matches(c.writers.get(h).type())) {
                    out.add("I3: s" + i + " resume point " + sub.rp + " passes g" + g + " held by running writer w" + h);
                    break;
                }
                if (s.matches(i, g) && !sub.handled.contains(g) && !sub.proj.containsKey(g) && !sub.dl.contains(g)) {
                    out.add("I3: s" + i + " resume point " + sub.rp + " passes committed, unhandled g" + g);
                    break;
                }
            }
            // I13: a catch-up position never passes a matching order that is still running or committed and unseen
            if (sub.state != ATTACHED) {
                for (int g = 1; g <= sub.catchFrom; g++) {
                    int h = s.holder(g);
                    if (h >= 0 && spec.matches(c.writers.get(h).type())) {
                        out.add("I13: s" + i + " catch-up position " + sub.catchFrom + " passes g" + g + " held by running writer w" + h);
                        break;
                    }
                    if (s.matches(i, g) && !sub.handled.contains(g) && !sub.proj.containsKey(g) && !sub.dl.contains(g) &&
                        !sub.buffer.contains(g) && !sub.inflight.contains(g)) {
                        out.add("I13: s" + i + " catch-up position " + sub.catchFrom + " passes committed, unseen g" + g);
                        break;
                    }
                }
            }
            // I12: a CONTIGUOUS projection always equals the exactly-once fold of the events below its resume point
            if (spec.mode() == Mode.CONTIGUOUS) {
                for (var e : sub.proj.entrySet()) {
                    if (e.getValue() > 1) out.add("I12: s" + i + " applied g" + e.getKey() + " " + e.getValue() + " times");
                    if (e.getKey() >= sub.rp) out.add("I12: s" + i + " projection holds g" + e.getKey() + " at or above its resume point " + sub.rp +
                                                      " (a crash now replays it)");
                }
                for (int g = 1; g < sub.rp; g++) {
                    if (s.matches(i, g) && !sub.proj.containsKey(g) && !sub.dl.contains(g)) {
                        out.add("I12: s" + i + " projection lacks committed g" + g + " below its resume point " + sub.rp);
                        break;
                    }
                }
            }
        }
        // I9: the reader is never blocked by a subscriber while there is something to read
        if (s.committed.higherKey(s.cursor) != null && !s.holeReadPending && !s.stashed && !probeEnabled(s) && !(c.has(Mutant.X_BEFORE_S) && s.stashX >= 0))
            out.add("I9: committed rows above the cursor and the reader cannot read them");
        return out;
    }

    /**
     * Drives a copy to quiescence (no crashes, no writer activity) with a fair, deterministic schedule, then checks
     * I1: every committed matching event was handled by every subscription at least once.
     */
    static List<String> drainAndCheckNoLoss(State start) {
        State s = new State(start);
        s.transitionViolations = new ArrayList<>();
        var out = new ArrayList<String>();
        int rotate = 0;
        for (int step = 0; step < 10_000; step++) {
            var acts = actions(s, false);
            if (acts.isEmpty()) break;
            String before = s.key();
            State next = null;
            for (int k = 0; k < acts.size(); k++) {
                var a = acts.get((rotate + k) % acts.size());
                State t = new State(s);
                t.transitionViolations = new ArrayList<>();
                a.effect().accept(t);
                if (!t.key().equals(before)) {
                    next = t;
                    rotate = (rotate + k + 1);
                    break;
                }
            }
            if (next == null) break;
            s = next;
            for (String v : violations(s)) if (!out.contains(v)) out.add(v + " [during drain]");
            if (!out.isEmpty()) return out;
            if (step == 9_999) out.add("LIVENESS: drain did not reach quiescence");
        }
        for (int i = 0; i < s.subs.length; i++) {
            Sub sub = s.subs[i];
            for (int g : s.committed.keySet()) {
                if (!s.matches(i, g)) continue;
                if (!sub.handled.contains(g) && !sub.proj.containsKey(g) && !sub.dl.contains(g)) {
                    out.add("I1: s" + i + " never handled committed g" + g + " (quiescent: " + s.summary() + ")");
                    break;
                }
            }
        }
        return out;
    }

    static String invariantId(String violation) {
        int colon = violation.indexOf(':');
        return colon > 0 ? violation.substring(0, colon) : violation;
    }

    private SubscriptionModel() {
    }
}
