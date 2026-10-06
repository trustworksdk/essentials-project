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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer

import dk.trustworks.essentials.kotlin.types.StringValueType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class StringValueTypeAggregateIdSerializerTest {
    @Test
    fun `serializerFor returns the Kotlin serializer for a value class id`() {
        val serializer = AggregateIdSerializer.serializerFor(KotlinOrderId::class.java)

        assertThat(serializer).isEqualTo(StringValueTypeAggregateIdSerializer(KotlinOrderId::class))
        assertThat(serializer.aggregateIdType()).isEqualTo(KotlinOrderId::class.java)
    }

    @Test
    fun `serializerFor round trips a value class id`() {
        val serializer = AggregateIdSerializer.serializerFor(KotlinOrderId::class.java)
        val id = KotlinOrderId("order-1")

        val serialized = serializer.serialize(id)

        assertThat(serialized).isEqualTo("order-1")
        assertThat(serializer.deserialize(serialized)).isEqualTo(id)
    }

    @Test
    fun `serializerFor round trips a data class id`() {
        val serializer = AggregateIdSerializer.serializerFor(KotlinCustomerId::class.java)
        val id = KotlinCustomerId("customer-1")

        assertThat(serializer).isEqualTo(StringValueTypeAggregateIdSerializer(KotlinCustomerId::class))
        assertThat(serializer.deserialize(serializer.serialize(id))).isEqualTo(id)
    }

    @Test
    fun `forType rejects a type that is not a StringValueType`() {
        assertThatThrownBy { StringValueTypeAggregateIdSerializer.forType(String::class.java) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining(StringValueType::class.java.name)
    }

    @Test
    fun `serializerFor rejects the StringValueType interface itself`() {
        assertThatThrownBy { AggregateIdSerializer.serializerFor(StringValueType::class.java) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("MUST be concrete")
    }
}
