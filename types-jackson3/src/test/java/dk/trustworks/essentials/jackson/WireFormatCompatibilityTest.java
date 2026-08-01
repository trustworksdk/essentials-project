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

package dk.trustworks.essentials.jackson;

import tools.jackson.databind.ObjectMapper;
import dk.trustworks.essentials.jackson.model.*;
import dk.trustworks.essentials.jackson.types.EssentialTypesJacksonModule;
import dk.trustworks.essentials.types.*;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Jackson 3 half of the wire-format gate: proves this module reads and reproduces, byte for byte, the JSON that
 * Jackson 2 wrote — the exact situation of an application upgrading to Spring Boot 4.
 * <p>
 * Pins the persisted JSON wire format of the Essentials types.
 * <p>
 * Essentials persists JSON — event payloads, event metadata, durable-queue message payloads, documents — and that data
 * outlives the library version that wrote it. An application upgrading to Spring Boot 4 and Jackson 3 must still be
 * able to read everything Jackson 2 wrote, so the format is not an implementation detail: it is a compatibility
 * contract, and this test is its gate.
 * <p>
 * The golden document is checked in under {@code types-jackson} because that module defines the legacy format. The
 * {@code types-jackson3} module runs the mirrored test against the very same file (wired in as a shared test resource
 * by its POM), which is what makes "Jackson 3 reads Jackson 2 payloads" an assertion rather than a hope.
 * <p>
 * If a change to the value types intentionally alters the format, regenerate with:
 * <pre>{@code
 * mvn -pl types-jackson test -Dtest=WireFormatCompatibilityTest -Dwireformat.regenerate=true
 * }</pre>
 * and treat the resulting diff as the breaking change it is — existing persisted data will no longer deserialize.
 */
class WireFormatCompatibilityTest {

    private static final String GOLDEN_RESOURCE = "/wire-format/serialization-test-subject.json";

    private final ObjectMapper objectMapper = EssentialTypesJacksonModule.createObjectMapper();

    @Test
    void the_persisted_wire_format_is_unchanged() throws IOException {
        var serialized = objectMapper.writeValueAsString(fixture());

        // Compared as trees: property order is not part of the contract, property names and value encodings are.
        assertThat(objectMapper.readTree(serialized))
                .as("""
                    The persisted JSON wire format changed. Data written by earlier versions will no longer \
                    deserialize. Regenerate with -Dwireformat.regenerate=true only if that is intended.""")
                .isEqualTo(objectMapper.readTree(golden()));
    }

    @Test
    void a_payload_written_by_jackson_2_deserializes_into_the_expected_object_graph() throws IOException {
        var deserialized = objectMapper.readValue(golden(), SerializationTestSubject.class);

        assertThat(deserialized).isEqualTo(fixture());
    }

    /**
     * The encodings that matter, asserted individually so a failure names the offending type rather than dumping a
     * whole document diff: every semantic type collapses to a JSON primitive, temporal types to ISO-8601 strings, and
     * {@link Money} to its two-field object.
     */
    @Test
    void every_type_family_is_encoded_as_a_primitive_rather_than_a_wrapper_object() throws IOException {
        var json = objectMapper.readTree(golden());

        assertThat(json.get("customerId").isTextual()).as("CharSequenceType → string").isTrue();
        assertThat(json.get("customerId").asText()).isEqualTo("customer-1");
        assertThat(json.get("accountId").isNumber()).as("LongType → number").isTrue();
        assertThat(json.get("amount").isNumber()).as("BigDecimalType → number").isTrue();
        assertThat(json.get("percentage").isNumber()).as("Percentage → number").isTrue();
        assertThat(json.get("currency").asText()).as("CurrencyCode → string").isEqualTo("DKK");
        assertThat(json.get("created").isTextual()).as("JSR-310 type → ISO-8601 string").isTrue();
        assertThat(json.get("created").asText()).isEqualTo("2026-01-15T10:30:00");
        assertThat(json.get("totalPrice").get("amount").isNumber()).as("Money.amount → number").isTrue();
        assertThat(json.get("totalPrice").get("currency").asText()).as("Money.currency → string").isEqualTo("DKK");
        assertThat(json.get("orderLines").get("product-1").asInt()).as("value-type map key → string key").isEqualTo(10);
    }

    /**
     * Fixed values throughout — a golden document cannot be pinned against {@code random()} or {@code now()}.
     */
    private static SerializationTestSubject fixture() {
        var amount     = Amount.of("123.45");
        var percentage = Percentage.from("30%");
        return new SerializationTestSubject(CustomerId.of("customer-1"),
                                            OrderId.of(1000L),
                                            ProductId.of("product-1"),
                                            AccountId.of(2000L),
                                            amount,
                                            Amount.of("100"),
                                            percentage,
                                            CurrencyCode.DKK,
                                            CountryCode.of("DK"),
                                            EmailAddress.of("john@nonexistingdomain.com"),
                                            Map.of(ProductId.of("product-1"), Quantity.of(10),
                                                   ProductId.of("product-2"), Quantity.of(5)),
                                            Money.of(amount.add(percentage.of(amount)), CurrencyCode.DKK),
                                            Created.of(LocalDateTime.of(2026, 1, 15, 10, 30, 0)),
                                            DueDate.of(LocalDate.of(2026, 2, 1)),
                                            LastUpdated.of(Instant.parse("2026-01-15T10:30:00Z")),
                                            TimeOfDay.of(LocalTime.of(10, 30, 0)),
                                            TransactionTime.of(ZonedDateTime.of(2026, 1, 15, 10, 30, 0, 0, ZoneOffset.UTC)),
                                            TransferTime.of(OffsetDateTime.of(2026, 1, 15, 10, 30, 0, 0, ZoneOffset.UTC)));
    }

    private static byte[] golden() throws IOException {
        try (InputStream goldenDocument = WireFormatCompatibilityTest.class.getResourceAsStream(GOLDEN_RESOURCE)) {
            assertThat(goldenDocument)
                    .as("The golden wire-format document is missing from the test classpath at %s", GOLDEN_RESOURCE)
                    .isNotNull();
            return goldenDocument.readAllBytes();
        }
    }

}
