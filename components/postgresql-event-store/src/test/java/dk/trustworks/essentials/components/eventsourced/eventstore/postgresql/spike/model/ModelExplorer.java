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

import java.util.*;

/**
 * Breadth-first exhaustive exploration of {@link SubscriptionModel} (shortest counterexamples), and seeded random
 * walks. Visited states are kept as 128-bit fingerprints of their canonical key, as TLC does with 64-bit ones.
 */
final class ModelExplorer {

    record Fingerprint(long a, long b) {
        static Fingerprint of(String s) {
            long h1 = 0xcbf29ce484222325L, h2 = 0x84222325cbf29ce4L;
            for (int i = 0; i < s.length(); i++) {
                char ch = s.charAt(i);
                h1 = (h1 ^ ch) * 0x100000001b3L;
                h2 = (h2 ^ ch) * 0x9E3779B97F4A7C15L + 0x632BE59BD9B4E019L;
                h2 ^= h2 >>> 29;
            }
            return new Fingerprint(h1, h2);
        }
    }

    record Result(Config config, long states, long transitions, int maxDepth, boolean exhausted, long millis,
                  Map<String, String> firstViolationByInvariant) {
        boolean clean() {
            return firstViolationByInvariant.isEmpty();
        }

        String headline() {
            return String.format("%s: %,d states, %,d transitions, depth %d, %s, %d ms, violations=%s", config.name, states, transitions, maxDepth,
                                 exhausted ? "EXHAUSTED" : "BOUNDED (state cap hit)", millis, firstViolationByInvariant.keySet());
        }
    }

    /**
     * @param stopAtFirst stop at the first violating state; otherwise keep exploring (not expanding violating
     *                    states) and record the shortest trace for each invariant id
     */
    static Result bfs(Config c, int maxStates, boolean stopAtFirst) {
        long t0 = System.currentTimeMillis();
        var ids = new HashMap<Fingerprint, Integer>(1 << 20);
        int[] parents = new int[1 << 16];
        int[] labelOf = new int[1 << 16];
        int[] depth = new int[1 << 16];
        var labels = new ArrayList<String>();
        var labelIds = new HashMap<String, Integer>();
        var found = new LinkedHashMap<String, String>();
        var queue = new ArrayDeque<Object[]>(); // {State, id}
        State init = SubscriptionModel.initial(c);
        ids.put(Fingerprint.of(init.key()), 0);
        parents[0] = -1;
        int count = 1;
        long transitions = 0;
        int maxDepth = 0;
        queue.add(new Object[]{init, 0});
        boolean capped = false;
        outer:
        while (!queue.isEmpty()) {
            Object[] e = queue.poll();
            State s = (State) e[0];
            int sid = (int) e[1];
            for (Action a : SubscriptionModel.actions(s, true)) {
                State t = new State(s);
                a.effect().accept(t);
                transitions++;
                Fingerprint fp = Fingerprint.of(t.key());
                if (ids.containsKey(fp)) continue;
                if (count >= maxStates) {
                    capped = true;
                    break outer;
                }
                int tid = count++;
                if (tid >= parents.length) {
                    parents = Arrays.copyOf(parents, parents.length * 2);
                    labelOf = Arrays.copyOf(labelOf, labelOf.length * 2);
                    depth = Arrays.copyOf(depth, depth.length * 2);
                }
                ids.put(fp, tid);
                parents[tid] = sid;
                labelOf[tid] = labelIds.computeIfAbsent(a.label(), l -> {
                    labels.add(l);
                    return labels.size() - 1;
                });
                depth[tid] = depth[sid] + 1;
                maxDepth = Math.max(maxDepth, depth[tid]);
                var v = SubscriptionModel.violations(t);
                if (v.isEmpty() && t.writersDone()) v = SubscriptionModel.drainAndCheckNoLoss(t);
                if (!v.isEmpty()) {
                    var path = new ArrayList<String>();
                    for (int k = tid; parents[k] >= 0; k = parents[k]) path.add(labels.get(labelOf[k]));
                    Collections.reverse(path);
                    for (String viol : v) {
                        String id = SubscriptionModel.invariantId(viol);
                        if (!found.containsKey(id)) found.put(id, render(c, path, viol));
                    }
                    if (stopAtFirst) break outer;
                    continue;
                }
                queue.add(new Object[]{t, tid});
            }
        }
        return new Result(c, count, transitions, maxDepth, !capped && (found.isEmpty() || !stopAtFirst), System.currentTimeMillis() - t0, found);
    }

    record WalkResult(Config config, int seeds, long steps, long millis, Map<String, String> firstViolationByInvariant) {
        String headline() {
            return String.format("%s: %d random walks, %,d steps, %d ms, violations=%s", config.name, seeds, steps, millis, firstViolationByInvariant.keySet());
        }
    }

    /** Seeded random walks: random enabled steps, then writers finish, then a drain to quiescence and I1. */
    static WalkResult randomWalks(Config c, long firstSeed, int seeds, int maxSteps) {
        long t0 = System.currentTimeMillis();
        long steps = 0;
        var found = new LinkedHashMap<String, String>();
        for (long seed = firstSeed; seed < firstSeed + seeds; seed++) {
            var rnd = new Random(seed);
            State s = SubscriptionModel.initial(c);
            var path = new ArrayList<String>();
            List<String> v = List.of();
            for (int step = 0; step < maxSteps * 4 && v.isEmpty(); step++) {
                var acts = new ArrayList<>(SubscriptionModel.actions(s, true));
                if (step >= maxSteps) { // wind down: let writers finish, no more crashes or jumps
                    acts.removeIf(a -> a.label().startsWith("CRASH") || a.label().startsWith("sequenceJump"));
                    if (s.writersDone()) break;
                }
                if (acts.isEmpty()) break;
                var a = acts.get(rnd.nextInt(acts.size()));
                State t = new State(s);
                a.effect().accept(t);
                path.add(a.label());
                steps++;
                s = t;
                v = SubscriptionModel.violations(s);
            }
            if (v.isEmpty() && s.writersDone()) v = SubscriptionModel.drainAndCheckNoLoss(s);
            if (!v.isEmpty()) {
                for (String viol : v) {
                    String id = SubscriptionModel.invariantId(viol);
                    if (!found.containsKey(id)) found.put(id, "seed " + seed + "\n" + render(c, path, viol));
                }
            }
        }
        return new WalkResult(c, seeds, steps, System.currentTimeMillis() - t0, found);
    }

    /** Replays a path of step labels from the initial state, printing the state after every step. */
    static String render(Config c, List<String> path, String violation) {
        var b = new StringBuilder();
        b.append("VIOLATION ").append(violation).append('\n').append("config ").append(c).append('\n');
        State s = SubscriptionModel.initial(c);
        b.append(String.format("  %2d %-34s %s%n", 0, "(initial)", s.summary()));
        int n = 1;
        for (String label : path) {
            State cur = s;
            var a = SubscriptionModel.actions(cur, true).stream().filter(x -> x.label().equals(label)).findFirst()
                                     .orElseThrow(() -> new IllegalStateException("step " + label + " not enabled in " + cur.summary()));
            State t = new State(s);
            a.effect().accept(t);
            s = t;
            b.append(String.format("  %2d %-34s %s%n", n++, label, s.summary()));
        }
        return b.toString();
    }

    /** Applies the given labelled steps in order, failing if one is not enabled. */
    static State run(Config c, String... labels) {
        State s = SubscriptionModel.initial(c);
        for (String label : labels) {
            State cur = s;
            var a = SubscriptionModel.actions(cur, true).stream().filter(x -> x.label().equals(label)).findFirst()
                                     .orElseThrow(() -> new IllegalStateException("step " + label + " not enabled in " + cur.summary() +
                                                                                  "; enabled: " + SubscriptionModel.actions(cur, true).stream().map(Action::label).toList()));
            State t = new State(s);
            a.effect().accept(t);
            s = t;
        }
        return s;
    }

    private ModelExplorer() {
    }
}
