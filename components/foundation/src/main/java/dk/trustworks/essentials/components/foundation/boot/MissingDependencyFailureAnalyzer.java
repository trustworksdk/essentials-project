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

package dk.trustworks.essentials.components.foundation.boot;

import org.springframework.boot.diagnostics.*;
import org.springframework.core.Ordered;

import java.util.*;

/**
 * Turns a startup failure caused by a missing third-party library that an Essentials starter needs into a report that
 * names the Maven coordinates to add.
 * <p>
 * Every third-party integration of Essentials is declared {@code provided} - the application chooses the versions, so
 * it has to declare the libraries itself. Leaving one out surfaces as a raw {@code NoClassDefFoundError} or
 * {@code ClassNotFoundException} deep in a bean-creation stack trace, often under a message that names neither the
 * library nor the dependency, such as "Failed to deduce bean type for ...reactiveHandlersBeanPostProcessor".
 * <p>
 * Only the libraries the starters are known to need are mapped (the plugin's stack contract, S2.1); any other missing
 * class is left to Spring Boot's own reporting. Registered in the starters' {@code META-INF/spring.factories}.
 */
public final class MissingDependencyFailureAnalyzer implements FailureAnalyzer, Ordered {

    private static final int MAX_CAUSE_DEPTH = 100;

    /**
     * Package (or class) prefix of a missing class, in the order they are tried: a more specific prefix comes before a
     * broader one.
     */
    private static final List<MissingLibrary> KNOWN_LIBRARIES = List.of(
            new MissingLibrary("org.jdbi.v3.postgres.", "org.jdbi:jdbi3-postgres", "The PostgreSQL starters install Jdbi's PostgresPlugin."),
            new MissingLibrary("org.jdbi.", "org.jdbi:jdbi3-core", "The PostgreSQL starters and components run on Jdbi."),
            new MissingLibrary("org.postgresql.", "org.postgresql:postgresql", "No Essentials artifact brings the PostgreSQL JDBC driver."),
            new MissingLibrary("kotlin.reflect.full.", "org.jetbrains.kotlin:kotlin-reflect", "postgresql-document-db is written in Kotlin; this applies to pure-Java projects too."),
            new MissingLibrary("kotlin.reflect.jvm.", "org.jetbrains.kotlin:kotlin-reflect", "postgresql-document-db is written in Kotlin; this applies to pure-Java projects too."),
            new MissingLibrary("kotlin.", "org.jetbrains.kotlin:kotlin-stdlib (and, for postgresql-document-db, org.jetbrains.kotlin:kotlin-reflect)",
                               "postgresql-document-db is written in Kotlin, so the Kotlin runtime is needed even by a project with no Kotlin sources."),
            new MissingLibrary("org.springframework.data.mongodb.", "org.springframework.boot:spring-boot-starter-data-mongodb", "The MongoDB starter is built on Spring Data MongoDB."),
            new MissingLibrary("com.mongodb.", "org.springframework.boot:spring-boot-starter-data-mongodb", "It brings the MongoDB driver the MongoDB starter uses."),
            new MissingLibrary("org.springframework.jdbc.", "org.springframework.boot:spring-boot-starter-jdbc", "The PostgreSQL starters need its DataSource and PlatformTransactionManager."),
            new MissingLibrary("org.springframework.transaction.", "org.springframework.boot:spring-boot-starter-jdbc (PostgreSQL) or org.springframework.boot:spring-boot-starter-data-mongodb (MongoDB)",
                               "The Essentials UnitOfWork joins Spring-managed transactions."),
            new MissingLibrary("tools.jackson.", "tools.jackson.core:jackson-databind", "Essentials persists JSON with Jackson 3; a Spring Boot web starter brings it, a headless application must declare it."),
            new MissingLibrary("org.objenesis.", "org.objenesis:objenesis", "immutable-jackson3, which the starters bring, instantiates persisted objects through it. Spring Boot does not manage its version."),
            new MissingLibrary("org.reactivestreams.", "io.projectreactor:reactor-core", "The Essentials EventBus and CommandBus run on Reactor; WebFlux brings it, a WebMvc application must declare it."),
            new MissingLibrary("reactor.", "io.projectreactor:reactor-core", "The Essentials EventBus and CommandBus run on Reactor; WebFlux brings it, a WebMvc application must declare it."));

    @Override
    public FailureAnalysis analyze(Throwable failure) {
        var visited = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        var depth   = 0;
        for (var cause = failure; cause != null && depth < MAX_CAUSE_DEPTH && visited.add(cause); cause = cause.getCause(), depth++) {
            var missingClass = missingClassName(cause);
            if (missingClass.isEmpty()) {
                continue;
            }
            var library = libraryFor(missingClass.get());
            if (library.isPresent()) {
                return analysis(missingClass.get(), library.get(), failure);
            }
        }
        return null;
    }

    @Override
    public int getOrder() {
        // Ahead of Spring Boot's generic analyzers: this one only fires for the libraries listed above, and for those
        // the coordinates to add are the most useful thing to say
        return Ordered.HIGHEST_PRECEDENCE;
    }

    private static FailureAnalysis analysis(String missingClass, MissingLibrary library, Throwable failure) {
        var description = "The application failed to start because the class " + missingClass + " is not on the classpath. "
                + "An Essentials starter needs it, from " + library.coordinates() + ".";
        var action = "Add " + library.coordinates() + " to your application's dependencies (compile scope). "
                + library.reason() + " "
                + "Essentials declares every third-party integration with provided scope, by design, so the application chooses the "
                + "version and must declare the library itself; it is never brought in transitively.";
        return new FailureAnalysis(description, action, failure);
    }

    private static Optional<MissingLibrary> libraryFor(String missingClass) {
        return KNOWN_LIBRARIES.stream().filter(library -> missingClass.startsWith(library.classPrefix())).findFirst();
    }

    /**
     * @return the binary name of the class a link or load failure reports as missing
     */
    static Optional<String> missingClassName(Throwable cause) {
        String name = null;
        if (cause instanceof NoClassDefFoundError || cause instanceof ClassNotFoundException) {
            name = cause.getMessage();
            if (name != null && name.startsWith("Could not initialize class ")) {
                // Not missing: its static initializer failed earlier. The cause chain may still hold the real culprit.
                name = null;
            }
        } else if (cause instanceof TypeNotPresentException typeNotPresent) {
            name = typeNotPresent.typeName();
        } else if (cause.getMessage() != null && cause.getMessage().contains("driver class") && cause.getMessage().contains("org.postgresql.Driver")) {
            // HikariCP / Spring Boot's DataSourceProperties report a missing JDBC driver without a ClassNotFoundException in the chain
            name = "org.postgresql.Driver";
        }
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(name.trim().replace('/', '.'));
    }

    private record MissingLibrary(String classPrefix, String coordinates, String reason) {
    }
}
