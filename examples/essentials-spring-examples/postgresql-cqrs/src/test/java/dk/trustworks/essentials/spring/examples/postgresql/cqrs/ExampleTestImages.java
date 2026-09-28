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

package dk.trustworks.essentials.spring.examples.postgresql.cqrs;

/**
 * The container images this example's integration tests run against, pinned in one place.
 * <p>
 * A floating tag ({@code postgres:latest}, {@code apache/kafka-native:latest}) silently changes the database or broker
 * major version underneath the suite, which turns an upstream release into an unexplained local failure - see
 * {@code .claude/rules/testing.md}. These examples cannot use {@code EssentialsTestContainers} (it is an internal test
 * utility, and these modules are meant to read like consumer code), so they pin the same tags inline instead.
 * <p>
 * <b>Bumping:</b> change {@code EssentialsTestContainers} first, then these pins, then the pre-pull step in
 * {@code .github/workflows/maven.yml}.
 */
public final class ExampleTestImages {

    /** Kept in step with {@code EssentialsTestContainers.POSTGRES_IMAGE}. */
    public static final String POSTGRES_IMAGE = "postgres:18.4";

    /**
     * Kept in step with the {@code kafka-clients.version} pinned in the root {@code pom.xml}: the comment there
     * reasons that the broker is never the older half of the pair, which only holds while this tag matches.
     */
    public static final String KAFKA_IMAGE = "apache/kafka-native:4.3.1";

    /**
     * A Kafka container for one test class. Every IT class starts its own, which keeps each class's broker state -
     * topics, committed consumer-group offsets - to itself; sharing one broker across classes would let a class's
     * listeners consume what an earlier class produced and never handled.
     * <p>
     * The cost is a dozen broker startups per build, and each is a chance to fail: with two attempts, a class failed
     * with {@code RetryCountExceededException} about one build in three or four, locally and on CI. Three attempts,
     * each allowed two minutes rather than the wait strategy's default 60 s, make that rarer without changing what any
     * test sees. If it still happens, the {@code Caused by} at the bottom of the trace says why the attempts failed.
     */
    public static org.testcontainers.kafka.KafkaContainer newKafkaContainer() {
        return new org.testcontainers.kafka.KafkaContainer(KAFKA_IMAGE)
                .withStartupAttempts(3)
                .withStartupTimeout(java.time.Duration.ofMinutes(2));
    }

    private ExampleTestImages() {
    }
}
