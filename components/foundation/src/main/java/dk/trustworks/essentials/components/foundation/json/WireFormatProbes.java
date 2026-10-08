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

import dk.trustworks.essentials.components.foundation.messaging.queue.QueueName;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

import static dk.trustworks.essentials.shared.Exceptions.rethrowIfCriticalError;

/**
 * The behavioural probes behind {@link EssentialsObjectMappers#findWireFormatDeviations(ObjectMapper)}.
 * <p>
 * Each probe exercises one aspect of the persisted format with a one-property type, so property ordering cannot make
 * a probe fail, and compares what the mapper does with what the Essentials 0.50 Jackson 2 mapper did. The expected
 * documents are written out rather than produced by {@link EssentialsObjectMappers}: they are the 0.50 bytes the golden
 * documents of {@code EssentialsObjectMappersWireFormatTest} were written in (an {@code OffsetDateTime} as
 * {@code "2026-01-15T10:30:00Z"}, a {@code Duration} of 30 seconds as {@code 30.000000000}, an {@code isX()} accessor
 * next to its field contributing nothing).
 */
final class WireFormatProbes {

    private WireFormatProbes() {
    }

    static List<WireFormatDeviation> findDeviations(ObjectMapper objectMapper) {
        var deviations = new ArrayList<WireFormatDeviation>();
        expectWritten(objectMapper, "Field access", new FieldProbe(), "{\"id\":\"probe-1\"}", deviations);
        expectWritten(objectMapper, "Getter visibility", new GetterProbe(), "{\"id\":\"probe-1\"}", deviations);
        expectWritten(objectMapper, "is-getter visibility", new IsGetterProbe(), "{\"isExpress\":true}", deviations);
        expectWritten(objectMapper, "Date/time format (WRITE_DATES_AS_TIMESTAMPS disabled)", new DateTimeProbe(), "{\"at\":\"2026-01-15T10:30:00Z\"}", deviations);
        expectWritten(objectMapper, "Duration format (WRITE_DURATIONS_AS_TIMESTAMPS enabled)", new DurationProbe(), "{\"delay\":30.000000000}", deviations);
        expectRead(objectMapper,
                   "A missing primitive reads as its default (FAIL_ON_NULL_FOR_PRIMITIVES disabled)",
                   "{\"id\":\"probe-1\"}",
                   PrimitiveProbe.class,
                   new PrimitiveProbe("probe-1", 0),
                   deviations);
        expectRead(objectMapper,
                   "An unknown property is ignored (FAIL_ON_UNKNOWN_PROPERTIES disabled)",
                   "{\"id\":\"probe-1\",\"quantity\":2,\"removedLater\":\"x\"}",
                   PrimitiveProbe.class,
                   new PrimitiveProbe("probe-1", 2),
                   deviations);
        expectUntypedFloatAsBigDecimal(objectMapper, deviations);
        if (isOnClasspath(EssentialsJacksonModules.TYPES_MODULE_CLASS_NAME)) {
            // Only probed where types-jackson3 is present: without it no mapper, Essentials-built or not, can write a
            // value type as a primitive, and the persisted format has nothing to say about an application without them.
            expectWritten(objectMapper, "Essentials value type as a JSON primitive (types-jackson3 module)", new ValueTypeProbe(), "{\"queueName\":\"orders\"}", deviations);
            expectRead(objectMapper,
                       "Essentials value type read from a JSON primitive (types-jackson3 module)",
                       "{\"queueName\":\"orders\"}",
                       ValueTypeProbe.class,
                       new ValueTypeProbe(),
                       deviations);
        }
        return deviations;
    }

    private static void expectWritten(ObjectMapper objectMapper, String aspect, Object probe, String expectedJson, List<WireFormatDeviation> deviations) {
        String actual;
        try {
            actual = objectMapper.writeValueAsString(probe);
        } catch (Throwable e) {
            rethrowIfCriticalError(e);
            actual = describe(e);
        }
        if (!expectedJson.equals(actual)) {
            deviations.add(new WireFormatDeviation(aspect, expectedJson, actual));
        }
    }

    private static <T> void expectRead(ObjectMapper objectMapper, String aspect, String json, Class<T> type, T expectedValue, List<WireFormatDeviation> deviations) {
        String actual;
        try {
            var read = objectMapper.readValue(json, type);
            if (Objects.equals(expectedValue, read)) {
                return;
            }
            actual = String.valueOf(read);
        } catch (Throwable e) {
            rethrowIfCriticalError(e);
            actual = describe(e);
        }
        deviations.add(new WireFormatDeviation(aspect, json + " to read as " + expectedValue, actual));
    }

    private static void expectUntypedFloatAsBigDecimal(ObjectMapper objectMapper, List<WireFormatDeviation> deviations) {
        var aspect = "An untyped floating-point number reads as BigDecimal (USE_BIG_DECIMAL_FOR_FLOATS enabled)";
        var json   = "{\"amount\":12.75}";
        String actual;
        try {
            var amount = objectMapper.readValue(json, Map.class).get("amount");
            if (amount instanceof BigDecimal bigDecimal && bigDecimal.compareTo(new BigDecimal("12.75")) == 0) {
                return;
            }
            actual = amount == null ? "null" : amount.getClass().getName() + " " + amount;
        } catch (Throwable e) {
            rethrowIfCriticalError(e);
            actual = describe(e);
        }
        deviations.add(new WireFormatDeviation(aspect, json + " to read the amount as " + BigDecimal.class.getName() + " 12.75", actual));
    }

    private static String describe(Throwable e) {
        return e.getClass().getName() + ": " + e.getMessage();
    }

    private static boolean isOnClasspath(String className) {
        try {
            Class.forName(className, false, WireFormatProbes.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // The probe types. Each carries one property, so the property order a mapper chooses cannot make a probe fail.
    // ------------------------------------------------------------------------------------------------------------

    /** A private field with no accessor: field access is the persisted format. */
    static final class FieldProbe {
        @SuppressWarnings({"unused", "FieldMayBeFinal"})
        private String id = "probe-1";
    }

    /** A getter-shaped method that is not a field must contribute nothing. */
    static final class GetterProbe {
        @SuppressWarnings("unused")
        public final String id = "probe-1";

        @SuppressWarnings("unused")
        public String getDerived() {
            return "derived";
        }
    }

    /** An is-getter next to its field: the 0.50 mapper wrote the field alone. */
    static final class IsGetterProbe {
        @SuppressWarnings("unused")
        public final boolean isExpress = true;

        @SuppressWarnings("unused")
        public boolean isExpress() {
            return isExpress;
        }
    }

    static final class DateTimeProbe {
        @SuppressWarnings("unused")
        public final OffsetDateTime at = OffsetDateTime.of(2026, 1, 15, 10, 30, 0, 0, ZoneOffset.UTC);
    }

    static final class DurationProbe {
        @SuppressWarnings("unused")
        public final Duration delay = Duration.ofSeconds(30);
    }

    /** A record whose primitive component is absent from a document persisted before it existed. */
    record PrimitiveProbe(String id, int quantity) {
    }

    static final class ValueTypeProbe {
        @SuppressWarnings("FieldMayBeFinal")
        private QueueName queueName = QueueName.of("orders");

        @Override
        public boolean equals(Object o) {
            return o instanceof ValueTypeProbe that && Objects.equals(queueName, that.queueName);
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(queueName);
        }

        @Override
        public String toString() {
            return "ValueTypeProbe{queueName=" + queueName + "}";
        }
    }
}
