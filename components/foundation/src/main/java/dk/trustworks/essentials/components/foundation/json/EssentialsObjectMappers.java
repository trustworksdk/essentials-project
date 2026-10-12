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

package dk.trustworks.essentials.components.foundation.json;

import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;

import java.util.List;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The canonical Essentials JSON mapper configuration.
 * <p>
 * Essentials persists JSON that outlives the library version which wrote it: event payloads, event metadata,
 * durable-queue message payloads. The <em>exact</em> mapper configuration is therefore part of the compatibility
 * contract, not a local detail — field-based access rather than getters, ISO-8601 dates rather than timestamps, and the
 * Essentials value-type modules registered. Configure a mapper slightly differently and it writes JSON the rest of the
 * estate cannot read.
 * <p>
 * This class exists because that configuration was previously copied into every place that needed a mapper, letting the
 * copies drift, and a divergence silently changes the persisted format. The factory here registers the Essentials
 * modules through {@link EssentialsJacksonModules}, which fails loudly when they are missing or of the wrong Jackson
 * major instead of quietly omitting them.
 * <p>
 * Up to 0.50 a second, Jackson 2 factory existed alongside this one. The Jackson 3 mapper is configured to write
 * byte-identical JSON to it, and {@code EssentialsObjectMappersWireFormatTest} holds golden documents written by that
 * Jackson 2 mapper: they are what guarantees that data persisted before 0.60 stays readable.
 *
 * @see EssentialsJacksonModules
 */
public final class EssentialsObjectMappers {

    private EssentialsObjectMappers() {
    }

    /**
     * Create a Jackson 3 {@link tools.jackson.databind.ObjectMapper} with the canonical Essentials configuration - the
     * mapper to use for anything Essentials persists or that must read what it persisted: event payloads and metadata,
     * durable-queue messages, documents.
     * <p>
     * Registers every Essentials Jackson module on the classpath ({@link EssentialsJacksonModules#modules()}), including
     * {@code immutable-jackson3}'s module when present, followed by the <code>additionalModules</code>. Use
     * {@link #createJackson3ObjectMapper(boolean, tools.jackson.databind.JacksonModule...)} to leave the immutable module out.
     *
     * @param additionalModules extra modules to register, e.g. application-specific serializers
     * @return a Jackson 3 {@link tools.jackson.databind.ObjectMapper} with the canonical Essentials configuration,
     *         writing the same JSON the Jackson 2 mapper of Essentials 0.50 and earlier wrote
     * @throws IllegalStateException if an Essentials Jackson module on the classpath was built for Jackson 2
     */
    public static tools.jackson.databind.ObjectMapper createJackson3ObjectMapper(tools.jackson.databind.JacksonModule... additionalModules) {
        return createJackson3ObjectMapper(true, additionalModules);
    }

    /**
     * Create a Jackson 3 {@link tools.jackson.databind.ObjectMapper} with the canonical Essentials configuration, as
     * {@link #createJackson3ObjectMapper(tools.jackson.databind.JacksonModule...)}, choosing whether
     * {@code immutable-jackson3}'s module is registered.
     *
     * @param includeImmutableModule {@code false} leaves {@code immutable-jackson3}'s {@code EssentialsImmutableJacksonModule}
     *                               out even when it is on the classpath; see {@link EssentialsJacksonModules#modules(boolean)}.
     *                               Only reading is affected - the JSON written is identical either way. Spring Boot:
     *                               {@code essentials.immutable-jackson-module-enabled}
     * @param additionalModules      extra modules to register, e.g. application-specific serializers
     * @return a Jackson 3 {@link tools.jackson.databind.ObjectMapper} with the canonical Essentials configuration
     * @throws IllegalStateException as {@link #createJackson3ObjectMapper(tools.jackson.databind.JacksonModule...)}
     */
    public static tools.jackson.databind.ObjectMapper createJackson3ObjectMapper(boolean includeImmutableModule, tools.jackson.databind.JacksonModule... additionalModules) {
        requireNonNull(additionalModules, "No additionalModules provided");
        // Jackson 3 has JavaTime and Jdk8 support built in, and its mappers are immutable, so visibility is configured
        // on the builder rather than after the fact.
        var builder = tools.jackson.databind.json.JsonMapper.builder()
                                                            .changeDefaultVisibility(visibility -> tools.jackson.databind.introspect.VisibilityChecker
                                                                    .defaultInstance()
                                                                    .withGetterVisibility(Visibility.NONE)
                                                                    // Jackson 3 detects is-getters separately from
                                                                    // getters. The 0.50 Jackson 2 mapper did not
                                                                    // detect them at all, and a visible isX() adds a
                                                                    // property to the persisted JSON - or overwrites
                                                                    // a field of the same name.
                                                                    .withIsGetterVisibility(Visibility.NONE)
                                                                    .withSetterVisibility(Visibility.NONE)
                                                                    .withFieldVisibility(Visibility.ANY)
                                                                    .withCreatorVisibility(Visibility.ANY))
                                                            .disable(tools.jackson.databind.MapperFeature.DEFAULT_VIEW_INCLUSION)
                                                            .disable(tools.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                                                            // Jackson 3 turned this on by default. Off, a document
                                                            // persisted before a primitive component was added reads
                                                            // it as 0/false, as Jackson 2 did; on, every such read
                                                            // fails.
                                                            .disable(tools.jackson.databind.DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                                                            .disable(tools.jackson.databind.SerializationFeature.FAIL_ON_EMPTY_BEANS)
                                                            .enable(tools.jackson.databind.MapperFeature.PROPAGATE_TRANSIENT_MARKER)
                                                            .enable(tools.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                                                            // Jackson 3 changed two temporal defaults. Both are pinned
                                                            // back to the Jackson 2 behaviour, because the difference is
                                                            // not cosmetic: it is the format already sitting in
                                                            // production databases. Jackson 3 would otherwise write a
                                                            // Duration as "PT30S" where Jackson 2 wrote 30.000000000.
                                                            .disable(tools.jackson.databind.cfg.DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
                                                            .enable(tools.jackson.databind.cfg.DateTimeFeature.WRITE_DURATIONS_AS_TIMESTAMPS)
                                                            // Jackson 2 populated final fields reflectively; Jackson 3
                                                            // turned that off by default. It is load-bearing for the
                                                            // immutable style Essentials encourages — final fields, one
                                                            // all-args constructor, no default constructor — because
                                                            // Jackson 3 also reads a lone single-argument constructor as
                                                            // a delegating creator, so a single-property type binds
                                                            // nothing and deserializes to null instead of failing.
                                                            // Multi-argument constructors escape that only because this
                                                            // build passes -parameters, which a consumer's build need
                                                            // not.
                                                            //
                                                            // The cost: a type whose JSON form is its contents rather
                                                            // than a bean stops looking like one once its final field
                                                            // counts as a mutator. Those types therefore state their
                                                            // creator explicitly instead of relying on this
                                                            // configuration — MessageMetaData wraps a Map, and
                                                            // types-jackson3 pins the Essentials value types, which wrap
                                                            // a scalar. Adding another such wrapper means annotating it
                                                            // the same way.
                                                            .enable(tools.jackson.databind.MapperFeature.ALLOW_FINAL_FIELDS_AS_MUTATORS);
        // Not set here: ConstructorDetector.EXPLICIT_ONLY. Jackson 3 reads constructor parameter names from the
        // bytecode and binds a class's sole constructor by them, which Jackson 2 did not do (no parameter-names module
        // is registered above) — so a constructor parameter whose name differs from the field it assigns now binds
        // nothing and passes null. EXPLICIT_ONLY does not prevent that: with no other way to construct the type,
        // Jackson uses the sole constructor regardless. The parameter name is therefore part of the JSON contract under
        // Jackson 3, and a mismatch has to be fixed on the type rather than configured away.
        builder.addModule(new Jackson3CollectionWrapperModule());
        EssentialsJacksonModules.modules(includeImmutableModule).forEach(builder::addModule);
        builder.addModules(additionalModules);
        return builder.build();
    }

    /**
     * Create a {@link JSONSerializer} backed by {@link #createJackson3ObjectMapper(tools.jackson.databind.JacksonModule...)}
     * - the serializer to hand to Essentials components that take one (durable queues, the multi-table change listener),
     * and to build test serializers with, so they write the persisted format.
     *
     * @return a {@link JSONSerializer} using the canonical Essentials configuration
     * @throws IllegalStateException as {@link #createJackson3ObjectMapper(tools.jackson.databind.JacksonModule...)}
     */
    public static JSONSerializer createJSONSerializer() {
        return new Jackson3JSONSerializer(createJackson3ObjectMapper());
    }

    /**
     * Create a {@link JSONSerializer} as {@link #createJSONSerializer()}, choosing whether {@code immutable-jackson3}'s
     * module is registered.
     *
     * @param includeImmutableModule see {@link #createJackson3ObjectMapper(boolean, tools.jackson.databind.JacksonModule...)}
     * @return a {@link JSONSerializer} using the canonical Essentials configuration
     * @throws IllegalStateException as {@link #createJackson3ObjectMapper(tools.jackson.databind.JacksonModule...)}
     */
    public static JSONSerializer createJSONSerializer(boolean includeImmutableModule) {
        return new Jackson3JSONSerializer(createJackson3ObjectMapper(includeImmutableModule));
    }

    /**
     * Checks whether a mapper reproduces the persisted JSON format, for a mapper Essentials did not build.
     * <p>
     * Every mapper {@link #createJackson3ObjectMapper(tools.jackson.databind.JacksonModule...)} builds writes the
     * format, but {@link Jackson3JSONSerializer} accepts any mapper, and a hand-built one drifts without a signal: the
     * application starts, writes, and only data persisted by another version tells the difference.
     * <p>
     * The check is behavioural, not a comparison of configuration: it writes and reads small probe types and compares
     * the result with what the Essentials 0.50 mapper wrote, so a mapper configured differently but to the same effect
     * passes. It covers field access, getter and {@code is}-getter visibility, the ISO-8601 date and numeric
     * {@code Duration} formats, a missing primitive reading as its default ({@code FAIL_ON_NULL_FOR_PRIMITIVES}),
     * unknown properties being ignored, untyped floats reading as {@code BigDecimal}, and - when {@code types-jackson3}
     * is on the classpath - Essentials value types written and read as JSON primitives.
     * <p>
     * The Essentials Spring Boot starters run it at startup against every {@link JSONSerializer} bean backed by a
     * {@link Jackson3JSONSerializer} ({@code essentials.json.wire-format-check}).
     *
     * @param objectMapper the mapper to check
     * @return every deviation found; empty when the mapper reproduces the persisted format
     */
    public static List<WireFormatDeviation> findWireFormatDeviations(tools.jackson.databind.ObjectMapper objectMapper) {
        requireNonNull(objectMapper, "No objectMapper provided");
        return WireFormatProbes.findDeviations(objectMapper);
    }
}
