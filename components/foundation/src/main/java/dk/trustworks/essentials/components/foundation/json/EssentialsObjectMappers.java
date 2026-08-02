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
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The canonical Essentials JSON mapper configuration, for both Jackson majors.
 * <p>
 * Essentials persists JSON that outlives the library version which wrote it: event payloads, event metadata,
 * durable-queue message payloads. The <em>exact</em> mapper configuration is therefore part of the compatibility
 * contract, not a local detail — field-based access rather than getters, ISO-8601 dates rather than timestamps, and the
 * Essentials value-type modules registered. Configure a mapper slightly differently and it writes JSON the rest of the
 * estate cannot read.
 * <p>
 * This class exists because that configuration was previously copied into every place that needed a mapper, letting the
 * copies drift — most consequentially between the Jackson 2 and Jackson 3 paths, where a divergence silently changes
 * the persisted format. Both factories here apply the same settings and register the Essentials modules through
 * {@link EssentialsJacksonModules}, which fails loudly on a flavor mismatch instead of quietly omitting them.
 * <p>
 * That the two produce byte-identical JSON is asserted by {@code EssentialsObjectMappersWireFormatTest} — that
 * equivalence is what lets an application move to Spring Boot 4 and Jackson 3 and still read what Jackson 2 persisted.
 *
 * @see EssentialsJacksonModules
 */
public final class EssentialsObjectMappers {

    private EssentialsObjectMappers() {
    }

    /**
     * @param additionalModules extra modules to register, e.g. application-specific serializers
     * @return a Jackson 2 {@link ObjectMapper} with the canonical Essentials configuration
     * @throws IllegalStateException if the Essentials Jackson modules on the classpath are the Jackson 3 flavor
     */
    public static ObjectMapper createJackson2ObjectMapper(com.fasterxml.jackson.databind.Module... additionalModules) {
        requireNonNull(additionalModules, "No additionalModules provided");
        var builder = com.fasterxml.jackson.databind.json.JsonMapper.builder()
                                                                   .disable(MapperFeature.AUTO_DETECT_GETTERS)
                                                                   .disable(MapperFeature.AUTO_DETECT_IS_GETTERS)
                                                                   .disable(MapperFeature.AUTO_DETECT_SETTERS)
                                                                   .disable(MapperFeature.DEFAULT_VIEW_INCLUSION)
                                                                   .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                                                                   .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                                                                   .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
                                                                   .enable(MapperFeature.AUTO_DETECT_CREATORS)
                                                                   .enable(MapperFeature.AUTO_DETECT_FIELDS)
                                                                   .enable(MapperFeature.PROPAGATE_TRANSIENT_MARKER)
                                                                   // Untyped binding (deserialize to Map/Object, as the
                                                                   // CDC WAL path does) would otherwise map JSON floats
                                                                   // to Double, so 1.10 re-serializes as 1.1 and large
                                                                   // decimals lose precision. On the CDC path the
                                                                   // re-serialized string IS the persisted event
                                                                   // payload, so fidelity has to be exact.
                                                                   .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                                                                   .addModule(new Jdk8Module())
                                                                   .addModule(new JavaTimeModule());
        EssentialsJacksonModules.jackson2Modules().forEach(builder::addModule);
        for (com.fasterxml.jackson.databind.Module additionalModule : additionalModules) {
            builder.addModule(additionalModule);
        }

        var objectMapper = builder.build();
        objectMapper.setVisibility(objectMapper.getSerializationConfig().getDefaultVisibilityChecker()
                                               .withGetterVisibility(Visibility.NONE)
                                               .withSetterVisibility(Visibility.NONE)
                                               .withFieldVisibility(Visibility.ANY)
                                               .withCreatorVisibility(Visibility.ANY));
        return objectMapper;
    }

    /**
     * @param additionalModules extra modules to register, e.g. application-specific serializers
     * @return a Jackson 3 {@link tools.jackson.databind.ObjectMapper} configured to write the same JSON as
     *         {@link #createJackson2ObjectMapper}
     * @throws IllegalStateException if the Essentials Jackson modules on the classpath are the Jackson 2 flavor
     */
    public static tools.jackson.databind.ObjectMapper createJackson3ObjectMapper(tools.jackson.databind.JacksonModule... additionalModules) {
        requireNonNull(additionalModules, "No additionalModules provided");
        // Jackson 3 has JavaTime and Jdk8 support built in, and its mappers are immutable, so visibility is configured
        // on the builder rather than after the fact.
        var builder = tools.jackson.databind.json.JsonMapper.builder()
                                                            .changeDefaultVisibility(visibility -> tools.jackson.databind.introspect.VisibilityChecker
                                                                    .defaultInstance()
                                                                    .withGetterVisibility(Visibility.NONE)
                                                                    .withSetterVisibility(Visibility.NONE)
                                                                    .withFieldVisibility(Visibility.ANY)
                                                                    .withCreatorVisibility(Visibility.ANY))
                                                            .disable(tools.jackson.databind.MapperFeature.DEFAULT_VIEW_INCLUSION)
                                                            .disable(tools.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
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
        EssentialsJacksonModules.jackson3Modules().forEach(builder::addModule);
        builder.addModules(additionalModules);
        return builder.build();
    }

    /**
     * Builds the {@link JSONSerializer} matching the Jackson flavor on the classpath — the Jackson 3 one when the
     * Essentials Jackson 3 modules are present, otherwise the Jackson 2 one.
     *
     * @return a {@link JSONSerializer} using the canonical configuration for the active flavor
     */
    public static JSONSerializer createJSONSerializer() {
        return EssentialsJacksonModules.isJackson3Flavor()
               ? new Jackson3JSONSerializer(createJackson3ObjectMapper())
               : new JacksonJSONSerializer(createJackson2ObjectMapper());
    }
}
