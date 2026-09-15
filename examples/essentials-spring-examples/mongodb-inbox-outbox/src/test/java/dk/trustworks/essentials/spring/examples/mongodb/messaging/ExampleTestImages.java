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

package dk.trustworks.essentials.spring.examples.mongodb.messaging;

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

    /** Kept in step with {@code EssentialsTestContainers.MONGO_IMAGE}. */
    public static final String MONGO_IMAGE = "mongo:8.2";

    /**
     * Kept in step with the {@code kafka-clients.version} pinned in the root {@code pom.xml}: the comment there
     * reasons that the broker is never the older half of the pair, which only holds while this tag matches.
     */
    public static final String KAFKA_IMAGE = "apache/kafka-native:4.3.1";

    private ExampleTestImages() {
    }
}
