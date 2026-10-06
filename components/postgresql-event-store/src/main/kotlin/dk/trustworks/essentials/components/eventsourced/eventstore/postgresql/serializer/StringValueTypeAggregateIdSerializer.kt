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
import java.lang.reflect.Modifier
import kotlin.reflect.KClass
import kotlin.reflect.full.primaryConstructor

/**
 * [AggregateIdSerializer] for semantic [StringValueType]'s.
 *
 * Construct it explicitly with the id's [KClass], or let [AggregateIdSerializer.serializerFor] pick it for a
 * [StringValueType] id - both give the same serializer.
 * [deserialize] calls the id's primary constructor through `kotlin-reflect`, which must be on the classpath.
 */
@Suppress("UNCHECKED_CAST")
data class StringValueTypeAggregateIdSerializer<T : StringValueType<T>>(private val concreteType: KClass<T>) : AggregateIdSerializer {
    override fun aggregateIdType(): Class<*> {
        return concreteType.java
    }

    override fun serialize(aggregateId: Any): String {
        return (aggregateId as T).value
    }

    override fun deserialize(aggregateId: String): Any {
        return concreteType.primaryConstructor!!.call(aggregateId)
    }

    companion object {
        /**
         * Creates the serializer for a concrete [StringValueType] id given as a Java [Class], which is how
         * [AggregateIdSerializer.serializerFor] obtains it
         *
         * @param aggregateIdType the concrete [StringValueType] subtype used as aggregate id
         * @throws IllegalArgumentException if [aggregateIdType] is not a concrete [StringValueType] subtype
         */
        @JvmStatic
        fun forType(aggregateIdType: Class<*>): StringValueTypeAggregateIdSerializer<*> {
            require(StringValueType::class.java.isAssignableFrom(aggregateIdType)) {
                "The provided type '${aggregateIdType.name}' MUST implement ${StringValueType::class.java.name}"
            }
            require(!Modifier.isAbstract(aggregateIdType.modifiers)) {
                "The provided StringValueType '${aggregateIdType.name}' MUST be concrete"
            }
            // Any concrete T satisfies T : StringValueType<T> at runtime; Nothing only stands in for the unknown T
            return StringValueTypeAggregateIdSerializer(aggregateIdType.kotlin as KClass<Nothing>)
        }
    }
}