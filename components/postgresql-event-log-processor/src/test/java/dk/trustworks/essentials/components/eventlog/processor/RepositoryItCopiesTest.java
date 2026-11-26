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


package dk.trustworks.essentials.components.eventlog.processor;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The repository IT copies and their fixtures may differ from their originals only inside marked
 * blocks: {@code // >>> adapter-copy: replaces N original lines: <reason>} ... {@code // <<< adapter-copy}. Fails when
 * an original changes (its SHA-256 no longer matches) and when a copy drifts outside its blocks.
 */
class RepositoryItCopiesTest {
    static final String  CHANGED = "original changed: re-sync the copy and update the hash";
    static final Pattern OPEN    = Pattern.compile("// >>> adapter-copy: replaces (\\d+) original lines: \\S.*");
    static final String  CLOSE   = "// <<< adapter-copy";

    /** @param module the original's module, a sibling of this one; @param path the path under src/test/java, the same in the copy */
    record Copy(String module, String path, String sha256) {
        Path original() {
            return Path.of("..", module, "src", "test", "java").resolve(path);
        }

        Path copy() {
            return Path.of("src", "test", "java").resolve(path);
        }
    }

    static final List<Copy> COPIES = List.of(
            new Copy("eventsourced-aggregates", "dk/trustworks/essentials/components/eventsourced/aggregates/CustomerId.java", "2070c97eaf2dfc6995627917812c8241acc73e2a1ddcac5d04a0ba123016c4d2"),
            new Copy("eventsourced-aggregates", "dk/trustworks/essentials/components/eventsourced/aggregates/OrderId.java", "e4adc1f56adad0e58098e68d0b340da5b18f16de374e881de39e13e147434d46"),
            new Copy("eventsourced-aggregates", "dk/trustworks/essentials/components/eventsourced/aggregates/ProductId.java", "b9181feece9c8fbb90f067c8ec9b4c2e017ab633b4afaef6a25e7609616cd18b"),
            new Copy("eventsourced-aggregates", "dk/trustworks/essentials/components/eventsourced/aggregates/RecordingLocalEventBusConsumer.java", "bb6b116b61f22b72615577d44eafcdc4531263ff202ab7acda778b50788b1f6f"),
            new Copy("eventsourced-aggregates", "dk/trustworks/essentials/components/eventsourced/aggregates/TestPersistableEventMapper.java", "b01c9f8f2ac8d9579596f834af179149e066b8a374cf61bc634243165122028d"),
            new Copy("eventsourced-aggregates", "dk/trustworks/essentials/components/eventsourced/aggregates/classic/Order.java", "78f95f80df04b00c5a7f99359288d572d3bad719a8f293731704496b9409af6c"),
            new Copy("eventsourced-aggregates", "dk/trustworks/essentials/components/eventsourced/aggregates/classic/OrderEvents.java", "02d20ed341331eac75575379df57e6783c674d546599d5ccaac52b3fc1cf9af0"),
            new Copy("eventsourced-aggregates", "dk/trustworks/essentials/components/eventsourced/aggregates/classic/objenesis/Order.java", "3af30c2bff8e1cb4782546393fb8fb923190ffef34677c7680020a03872cf938"),
            new Copy("eventsourced-aggregates", "dk/trustworks/essentials/components/eventsourced/aggregates/classic/objenesis/NoDefaultConstructorOrderEvents.java", "05d1ae0b71d8e376e3826c03ecd127f869a386a0e6d6febe482be1ecaf7d24f1"),
            new Copy("eventsourced-aggregates", "dk/trustworks/essentials/components/eventsourced/aggregates/classic/state/OrderState.java", "d1064def635f5594eb19ff0506a211e3cf1d4a2e23aedcb893a8621db7307726"),
            new Copy("eventsourced-aggregates", "dk/trustworks/essentials/components/eventsourced/aggregates/classic/state/OrderWithState.java", "cdf90583dc2999fbd50fbcc4c46e8c7939f0e5fe8626a2da3d4e0cf673fcd1d3"),
            new Copy("eventsourced-aggregates", "dk/trustworks/essentials/components/eventsourced/aggregates/classic/objenesis/state/OrderState.java", "101920be08b92693bc4531c6dedb7bc1d7d6645cf7d176710e048235cfe542ae"),
            new Copy("eventsourced-aggregates", "dk/trustworks/essentials/components/eventsourced/aggregates/classic/objenesis/state/OrderWithState.java", "7162efc102ea196c6666bbf8fba12e6f2922c57f7c654a9a59e77a45667a27ee"),
            new Copy("eventsourced-aggregates", "dk/trustworks/essentials/components/eventsourced/aggregates/modern/Order.java", "5b528984fddef7275ae51776b5d8a2ef0e4038b767a59aa55c1978188dd046d1"),
            new Copy("eventsourced-aggregates", "dk/trustworks/essentials/components/eventsourced/aggregates/modern/OrderEvent.java", "40118a8280f5feb60d508e3e940766b65f0b6619439df12ecafbc92961199814"),
            new Copy("spring-postgresql-event-store", "dk/trustworks/essentials/components/eventsourced/eventstore/postgresql/spring/ApplicationTests.java", "ec1a6afc72aeb1f0dd12e75189bc3d418f0daf1aa5ed1446633631305da7d8fc"),
            new Copy("spring-postgresql-event-store", "dk/trustworks/essentials/components/eventsourced/eventstore/postgresql/spring/SpringManagedUnitOfWorkFactory_OrderAggregateRootRepositoryIT.java", "bbe9a0d6e2fdd742d4b1e448eaff74135fec81fdb78673b9ccc9cd6df78f7f32"),
            new Copy("spring-postgresql-event-store", "dk/trustworks/essentials/components/eventsourced/eventstore/postgresql/spring/SpringTransactionAwareEventStoreUnitOfWorkFactory_OrderAggregateRootRepositoryIT.java", "7557229361eaa408a61d09dc35b3445e42696f31cd61f772b3e86ef2c5d1cee9"),
            new Copy("spring-postgresql-event-store", "dk/trustworks/essentials/components/eventsourced/eventstore/postgresql/spring/test_data/CustomerId.java", "ab6efd18eef4bc7b82dab3f14cdeb41c78c94a2ed4c84c849e84009606ab1cba"),
            new Copy("spring-postgresql-event-store", "dk/trustworks/essentials/components/eventsourced/eventstore/postgresql/spring/test_data/Order.java", "73a7fcb9e6f9f1d845b85c45b1883a3c280be51429d25a4a45a99fda77658e12"),
            new Copy("spring-postgresql-event-store", "dk/trustworks/essentials/components/eventsourced/eventstore/postgresql/spring/test_data/OrderEvent.java", "d240159be916c9dae7084612ccd08434ab29e5c4ace46232b91db8e917910312"),
            new Copy("spring-postgresql-event-store", "dk/trustworks/essentials/components/eventsourced/eventstore/postgresql/spring/test_data/OrderId.java", "b2e3ed31dde8230804b2ac6be41ab25bc7dfb7d3c1480564ad944d060ee7c62d"),
            new Copy("spring-postgresql-event-store", "dk/trustworks/essentials/components/eventsourced/eventstore/postgresql/spring/test_data/OrderState.java", "10cb309c00a1819ba831d0c308f04eea752520d7f2c7221e10c234dab0f4a697"),
            new Copy("spring-postgresql-event-store", "dk/trustworks/essentials/components/eventsourced/eventstore/postgresql/spring/test_data/ProductId.java", "5a182ebb25a5f8a6c0ecebae9684895a59b7a08c1c903fa0a2d7c96d5f409bef"),
            new Copy("eventsourced-aggregates", "dk/trustworks/essentials/components/eventsourced/aggregates/classic/OrderAggregateRootRepositoryIT.java", "c1ce015060be63c9054e42c27db8d4ce09e5c198a3d6cff4dc872a20f8d67c40"),
            new Copy("eventsourced-aggregates", "dk/trustworks/essentials/components/eventsourced/aggregates/classic/objenesis/OrderAggregateRootRepositoryIT.java", "0d63dbd45b7d2389afb396450a4163dc5f829282f5c7383be37d4366e1ea6688"),
            new Copy("eventsourced-aggregates", "dk/trustworks/essentials/components/eventsourced/aggregates/classic/state/OrderWithStateAggregateRootRepositoryIT.java", "1d04cfa8f73b61cb81ee9cf8bb0ec7920b1e35845f3acf37977e7b91ca438558"),
            new Copy("eventsourced-aggregates", "dk/trustworks/essentials/components/eventsourced/aggregates/classic/objenesis/state/OrderWithStateAggregateRootRepositoryIT.java", "0cea9a01dac17e534fdadc1d183b4c5708d92673b83b6202fb9b096d56de968e"),
            new Copy("eventsourced-aggregates", "dk/trustworks/essentials/components/eventsourced/aggregates/stateful/StatefulAggregateRepositoryIT.java", "bca48f8e1afb7cea6d24849896a9a1d889c048237257a28127f92a87878fb823"),
            new Copy("eventsourced-aggregates", "dk/trustworks/essentials/components/eventsourced/aggregates/eventstream/EventStreamDeciderIT.java", "6f99469ea13b0d59322475964abb53e04215b0da6aa435553c7993426ab6684c"),
            new Copy("eventsourced-aggregates", "dk/trustworks/essentials/components/eventsourced/aggregates/eventstream/adapters/AppendedEventsIT.java", "1e78554a980df269729d265df62ab7a57bdcf5b0ae99b388d194968d71676859"),
            new Copy("eventsourced-aggregates", "dk/trustworks/essentials/components/eventsourced/aggregates/eventstream/adapters/EventStreamDeciderAndAggregateTypeConfiguratorTest.java", "20f604a6f497aa0ece20c8b64e30611ad0a4c42e5a2df054b12122cb49cf0181"),
            new Copy("spring-postgresql-event-store", "dk/trustworks/essentials/components/eventsourced/eventstore/postgresql/spring/OrderAggregateRootRepositoryTest.java", "0fd9149ff26fdb18a6b0255564564cf0b52e19be7d8432361083ca6d3d826c24")
                                            );

    @Test
    void every_original_is_unchanged() throws Exception {
        var changed = new ArrayList<String>();
        for (var copy : COPIES) {
            if (!sha256(Files.readAllBytes(copy.original())).equals(copy.sha256())) {
                changed.add(copy.original() + ": " + CHANGED);
            }
        }
        assertThat(changed).isEmpty();
    }

    @Test
    void every_copy_equals_its_original_outside_the_marked_blocks() throws Exception {
        var drift = new ArrayList<String>();
        for (var copy : COPIES) {
            drift.addAll(check(copy.copy().toString(), lines(copy.original()), lines(copy.copy())));
        }
        assertThat(drift).isEmpty();
    }

    @Test
    void the_checker_rejects_an_altered_copy() throws Exception {
        var it       = COPIES.stream().filter(c -> c.path().endsWith("classic/OrderAggregateRootRepositoryIT.java")).findFirst().orElseThrow();
        var original = lines(it.original());
        var copy     = lines(it.copy());
        assertThat(check("copy", original, copy)).isEmpty();

        // a changed line outside the blocks
        var assertion = indexOf(copy, "globalEventOrder()).isEqualTo(GlobalEventOrder.of(1))");
        var altered   = new ArrayList<>(copy);
        altered.set(assertion, altered.get(assertion).replace("GlobalEventOrder.of(1)", "GlobalEventOrder.of(7)"));
        assertThat(check("copy", original, altered)).anyMatch(problem -> problem.contains("drift"));

        // a removed line outside the blocks
        altered = new ArrayList<>(copy);
        altered.remove(assertion);
        assertThat(check("copy", original, altered)).isNotEmpty();

        // a block that declares the wrong number of replaced lines
        var block = indexOf(copy, "// >>> adapter-copy: replaces 1 original lines:");
        altered = new ArrayList<>(copy);
        altered.set(block, altered.get(block).replace("replaces 1 original", "replaces 2 original"));
        assertThat(check("copy", original, altered)).isNotEmpty();

        // an unterminated block
        altered = new ArrayList<>(copy);
        altered.remove(indexOf(copy, CLOSE));
        assertThat(check("copy", original, altered)).isNotEmpty();

        // a truncated copy
        assertThat(check("copy", original, copy.subList(0, copy.size() - 3))).anyMatch(problem -> problem.contains("not consumed"));

        // a changed original
        assertThat(sha256("changed".getBytes())).isNotEqualTo(it.sha256());
    }

    /**
     * @return the drift found: walking {@code copy}, each line outside a marked block equals the next original line, each
     * block skips exactly its declared number of original lines, and the whole original is consumed
     */
    static List<String> check(String name, List<String> original, List<String> copy) {
        var problems = new ArrayList<String>();
        int o = 0;
        int c = 0;
        while (c < copy.size()) {
            var line    = copy.get(c);
            var trimmed = line.trim();
            if (trimmed.startsWith("// >>> adapter-copy")) {
                var open = OPEN.matcher(trimmed);
                if (!open.matches()) {
                    problems.add(name + ":" + (c + 1) + ": malformed block marker: " + trimmed);
                    return problems;
                }
                int close = c + 1;
                while (close < copy.size() && !copy.get(close).trim().equals(CLOSE)) {
                    if (copy.get(close).trim().startsWith("// >>> adapter-copy")) {
                        problems.add(name + ":" + (close + 1) + ": nested block");
                        return problems;
                    }
                    close++;
                }
                if (close == copy.size()) {
                    problems.add(name + ":" + (c + 1) + ": block not closed");
                    return problems;
                }
                o += Integer.parseInt(open.group(1));
                if (o > original.size()) {
                    problems.add(name + ":" + (c + 1) + ": block replaces past the end of the original");
                    return problems;
                }
                c = close + 1;
            } else if (trimmed.equals(CLOSE)) {
                problems.add(name + ":" + (c + 1) + ": block close without open");
                return problems;
            } else {
                if (o >= original.size() || !line.equals(original.get(o))) {
                    problems.add(name + ":" + (c + 1) + ": drift from original line " + (o + 1) + ": expected <" + (o < original.size() ? original.get(o) : "end of file") + "> but was <" + line + ">");
                    return problems;
                }
                o++;
                c++;
            }
        }
        if (o != original.size()) {
            problems.add(name + ": original not consumed: " + (original.size() - o) + " original lines left from line " + (o + 1));
        }
        return problems;
    }

    private static int indexOf(List<String> lines, String text) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(text)) {
                return i;
            }
        }
        throw new IllegalArgumentException("Not found: " + text);
    }

    private static List<String> lines(Path path) throws IOException {
        return Arrays.asList(Files.readString(path).split("\n", -1));
    }

    private static String sha256(byte[] bytes) throws NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
