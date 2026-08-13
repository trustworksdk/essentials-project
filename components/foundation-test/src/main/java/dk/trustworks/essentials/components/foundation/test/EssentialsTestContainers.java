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

package dk.trustworks.essentials.components.foundation.test;

import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Single source of truth for the container images and container configuration used by the Essentials integration tests.
 * <p>
 * Two rules exist because breaking either one of them is expensive:
 * <ul>
 *     <li><b>Images are pinned.</b> A floating {@code :latest} tag silently changes the database major version underneath the
 *     entire test suite, which turns an upstream release into an unexplained local failure. Bump {@link #POSTGRES_IMAGE} /
 *     {@link #MONGO_IMAGE} deliberately, as its own commit.</li>
 *     <li><b>{@code @Container} fields are {@code static}.</b> An instance field makes JUnit start and stop a fresh container
 *     for every single test <i>method</i> rather than once per test class.</li>
 * </ul>
 *
 * @see #postgres()
 */
public final class EssentialsTestContainers {
    /**
     * Pinned PostgreSQL image. Matches the version that {@code postgres:latest} resolved to when the images were pinned,
     * so pinning did not change any test's behaviour.
     */
    public static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse("postgres:18.4");

    /**
     * Pinned MongoDB image. Matches the version that {@code mongo:latest} resolved to when the images were pinned.
     * <p>
     * Exposed as a plain image name rather than a container factory because {@code foundation-test} deliberately does not
     * depend on {@code testcontainers-mongodb} — the Mongo modules build their own {@code MongoDBContainer} (they need a
     * replica set) and only need the version to agree.
     */
    public static final DockerImageName MONGO_IMAGE = DockerImageName.parse("mongo:8.2");

    /**
     * System property that opts a local developer into Testcontainers container reuse: {@code -Dessentials.test.containers.reuse=true}.
     * <p>
     * Deliberately off by default, and deliberately <b>not</b> keyed off {@code testcontainers.reuse.enable} alone. A reused
     * container is shared by every test whose container configuration hashes the same, so with more than one Failsafe fork two
     * forks would attach to the <i>same</i> database and drop each other's tables — most Essentials suites reset their storage
     * in {@code @BeforeEach}. Only enable this together with {@code -Dfailsafe.forkCount=1}.
     */
    public static final String REUSE_PROPERTY = "essentials.test.containers.reuse";

    public static final String DEFAULT_DATABASE_NAME = "essentials-test-db";
    public static final String DEFAULT_USERNAME      = "test-user";
    public static final String DEFAULT_PASSWORD      = "secret-password";

    private EssentialsTestContainers() {
    }

    /**
     * A PostgreSQL container using {@link #POSTGRES_IMAGE} and the default database name/credentials.
     * <p>
     * Assign it to a {@code static} {@code @Container} field so the container is started once per test class:
     * <pre>{@code
     * @Container
     * static final PostgreSQLContainer<?> POSTGRES = EssentialsTestContainers.postgres();
     * }</pre>
     *
     * @return a non-started container
     */
    public static PostgreSQLContainer<?> postgres() {
        return postgres(DEFAULT_DATABASE_NAME);
    }

    /**
     * A PostgreSQL container using {@link #POSTGRES_IMAGE} and the default credentials, against the given database name.
     * <p>
     * Prefer this over {@link #postgres()} when a suite needs to stay isolated from other suites sharing the same fork.
     *
     * @param databaseName the initial database to create
     * @return a non-started container
     */
    public static PostgreSQLContainer<?> postgres(String databaseName) {
        return postgres(databaseName, DEFAULT_USERNAME, DEFAULT_PASSWORD);
    }

    /**
     * A PostgreSQL container using {@link #POSTGRES_IMAGE} against the given database name and credentials.
     *
     * @param databaseName the initial database to create
     * @param username     the database user
     * @param password     the database password
     * @return a non-started container
     */
    public static PostgreSQLContainer<?> postgres(String databaseName, String username, String password) {
        requireNonNull(databaseName, "No databaseName provided");
        requireNonNull(username, "No username provided");
        requireNonNull(password, "No password provided");
        return new PostgreSQLContainer<>(POSTGRES_IMAGE)
                .withDatabaseName(databaseName)
                .withUsername(username)
                .withPassword(password)
                .withReuse(isReuseEnabled());
    }

    /**
     * Whether container reuse has been opted into via {@link #REUSE_PROPERTY}.
     * <p>
     * Testcontainers additionally requires {@code testcontainers.reuse.enable=true} in {@code ~/.testcontainers.properties};
     * if that is absent the flag is silently ignored and containers behave as non-reusable.
     */
    public static boolean isReuseEnabled() {
        return Boolean.parseBoolean(System.getProperty(REUSE_PROPERTY, "false"));
    }
}
