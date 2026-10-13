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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.EventStore
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.EventStoreSubscriptionManager
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.EventStoreUnitOfWork
import dk.trustworks.essentials.components.foundation.fencedlock.FencedLockManager
import dk.trustworks.essentials.components.foundation.messaging.queue.DurableQueues
import dk.trustworks.essentials.components.foundation.reactive.command.DurableLocalCommandBus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock

/**
 * Pins the shape of `AbstractEventProcessor`'s `UnitOfWork` helpers as a Kotlin subclass sees them. Compiling this file
 * is most of the test.
 *
 * `usingUnitOfWork { ... }` and `withUnitOfWork { ... }` take a parameterless lambda. Adding a
 * `usingUnitOfWork(CheckedConsumer<EventStoreUnitOfWork>)` / `withUnitOfWork(CheckedFunction<...>)` overload beside them
 * makes both calls an overload-resolution ambiguity in Kotlin (a parameterless lambda may also mean `it`), which would
 * break every Kotlin processor using them - so the processor offers `requiredUnitOfWork` and `unitOfWorkFactory`
 * instead, and this file fails to compile if such an overload is added.
 */
class KotlinProcessorUnitOfWorkHelpersTest {

    @Test
    fun `the UnitOfWork helpers resolve from a Kotlin processor subclass`() {
        val processor = KotlinTestProcessor(subscriptionManager())

        assertThat(processor.overloadsResolve()).isTrue()
    }

    private fun subscriptionManager(): EventStoreSubscriptionManager {
        val subscriptionManager = mock(EventStoreSubscriptionManager::class.java)
        org.mockito.Mockito.`when`(subscriptionManager.eventStore).thenReturn(mock(EventStore::class.java))
        return subscriptionManager
    }

    class KotlinTestProcessor(subscriptionManager: EventStoreSubscriptionManager) : ViewEventProcessor(
        subscriptionManager,
        mock(FencedLockManager::class.java),
        mock(DurableQueues::class.java),
        mock(DurableLocalCommandBus::class.java),
        listOf()
    ) {
        override fun getProcessorName(): String = "KotlinTestProcessor"

        override fun reactsToEventsRelatedToAggregateTypes(): List<AggregateType> = listOf(AggregateType.of("Things"))

        /**
         * Never run against a real `UnitOfWork` - the lambdas are only built, so the overload each one binds to is
         * decided by the compiler.
         */
        @Suppress("UNUSED_VARIABLE", "unused")
        fun overloadsResolve(): Boolean {
            val using = { usingUnitOfWork { requiredUnitOfWork.handle() } }
            val with: () -> Int = { withUnitOfWork { 42 } }
            val current = { currentUnitOfWork.isPresent }
            val required = { requiredUnitOfWork.handle() }
            val handedTheUnitOfWork = { unitOfWorkFactory.usingUnitOfWork { uow: EventStoreUnitOfWork -> uow.handle() } }
            return true
        }
    }
}
