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

package dk.trustworks.essentials.components.eventsourced.aggregates.closingbooks;

import dk.trustworks.essentials.components.eventsourced.aggregates.stateful.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The four framework objects that stand up closing books for one aggregate type, assembled once.
 * <p>
 * Standing this up by hand means creating a {@link ClosingBooksGenerationRepository}, a
 * {@link ClosingBooksCoordinator} and a {@link TypedAggregateClosingBooksGenerationAccess}, in that order, and knowing
 * which serializer each one needs - even though every argument beyond the id types and the aggregate class is either
 * derivable or has a sensible default. This type does that assembly:
 * <pre>{@code
 * var setup = ClosingBooksSetup.<TradingAccountId, TradingAccountGenerationId>builder(TRADING_ACCOUNTS, TradingAccount.class)
 *                              .setLogicalAggregateIdType(TradingAccountId.class)
 *                              .setStreamIdType(TradingAccountGenerationId.class)
 *                              .setUnitOfWorkFactory(unitOfWorkFactory)
 *                              .setMeterRegistry(meterRegistry)
 *                              .build();
 * }</pre>
 * {@link #generationAccess()} in particular is <b>derived</b>, never written by hand: everything it needs - the
 * aggregate type, the implementation class, the generation repository and the logical-id serializer - is already held
 * here.
 * <p>
 * {@link #logicalAggregateRepository(StatefulAggregateRepository)} stays a call the application makes, because only the
 * application has the {@link StatefulAggregateRepository} to delegate to.
 *
 * @param <LOGICAL_ID> the logical/business aggregate id type
 * @param <STREAM_ID>  the generation stream id type
 */
public class ClosingBooksSetup<LOGICAL_ID, STREAM_ID> {
    private final AggregateType                                   aggregateType;
    private final Class<?>                                        aggregateImplementationType;
    private final ClosingBooksGenerationRepository<LOGICAL_ID>    generationRepository;
    private final ClosingBooksCoordinator<LOGICAL_ID>             coordinator;
    private final ClosingBooksIdSerializer<LOGICAL_ID>            logicalAggregateIdSerializer;
    private final ClosingBooksIdSerializer<STREAM_ID>             streamIdSerializer;

    ClosingBooksSetup(AggregateType aggregateType,
                      Class<?> aggregateImplementationType,
                      ClosingBooksGenerationRepository<LOGICAL_ID> generationRepository,
                      ClosingBooksCoordinator<LOGICAL_ID> coordinator,
                      ClosingBooksIdSerializer<LOGICAL_ID> logicalAggregateIdSerializer,
                      ClosingBooksIdSerializer<STREAM_ID> streamIdSerializer) {
        this.aggregateType = requireNonNull(aggregateType, "No aggregateType provided");
        this.aggregateImplementationType = requireNonNull(aggregateImplementationType, "No aggregateImplementationType provided");
        this.generationRepository = requireNonNull(generationRepository, "No generationRepository provided");
        this.coordinator = requireNonNull(coordinator, "No coordinator provided");
        this.logicalAggregateIdSerializer = requireNonNull(logicalAggregateIdSerializer, "No logicalAggregateIdSerializer provided");
        this.streamIdSerializer = requireNonNull(streamIdSerializer, "No streamIdSerializer provided");
    }

    /**
     * @param <LOGICAL_ID>                the logical/business aggregate id type
     * @param <STREAM_ID>                 the generation stream id type
     * @param aggregateType               the aggregate type closing books is being set up for; must not be null
     * @param aggregateImplementationType the aggregate implementation class; must not be null
     * @return a new builder
     */
    public static <LOGICAL_ID, STREAM_ID> ClosingBooksSetupBuilder<LOGICAL_ID, STREAM_ID> builder(AggregateType aggregateType,
                                                                                                 Class<?> aggregateImplementationType) {
        return new ClosingBooksSetupBuilder<>(aggregateType, aggregateImplementationType);
    }

    public AggregateType aggregateType() {
        return aggregateType;
    }

    public Class<?> aggregateImplementationType() {
        return aggregateImplementationType;
    }

    public ClosingBooksGenerationRepository<LOGICAL_ID> generationRepository() {
        return generationRepository;
    }

    public ClosingBooksCoordinator<LOGICAL_ID> coordinator() {
        return coordinator;
    }

    public ClosingBooksIdSerializer<LOGICAL_ID> logicalAggregateIdSerializer() {
        return logicalAggregateIdSerializer;
    }

    public ClosingBooksIdSerializer<STREAM_ID> streamIdSerializer() {
        return streamIdSerializer;
    }

    /**
     * The admin-API view of this setup's generations, derived from what the setup already holds. Contribute it to
     * {@link AggregateClosingBooksGenerationAccessProvider} - the Spring Boot starter does that automatically for every
     * {@link ClosingBooksSetup} bean.
     *
     * @return generation access for this aggregate type
     */
    public TypedAggregateClosingBooksGenerationAccess<LOGICAL_ID> generationAccess() {
        return new TypedAggregateClosingBooksGenerationAccess<>() {
            @Override
            public AggregateType aggregateType() {
                return aggregateType;
            }

            @Override
            public Class<?> aggregateImplementationType() {
                return aggregateImplementationType;
            }

            @Override
            public ClosingBooksGenerationRepository<LOGICAL_ID> generationRepository() {
                return generationRepository;
            }

            @Override
            public ClosingBooksIdSerializer<LOGICAL_ID> logicalAggregateIdSerializer() {
                return logicalAggregateIdSerializer;
            }
        };
    }

    /**
     * The consumer-facing repository that keeps application code on logical business ids.
     *
     * @param <EVENT_TYPE> the event type
     * @param <AGGREGATE>  the aggregate implementation type
     * @param delegate     the repository persisting the generation event streams; must not be null
     * @return a {@link ClosingBooksLogicalAggregateRepository} over the given delegate
     */
    public <EVENT_TYPE, AGGREGATE extends StatefulAggregate<STREAM_ID, EVENT_TYPE, AGGREGATE>>
    ClosingBooksLogicalAggregateRepository<LOGICAL_ID, STREAM_ID, EVENT_TYPE, AGGREGATE> logicalAggregateRepository(
            StatefulAggregateRepository<STREAM_ID, EVENT_TYPE, AGGREGATE> delegate) {
        return new ClosingBooksLogicalAggregateRepository<>(aggregateType,
                                                           requireNonNull(delegate, "No delegate provided"),
                                                           coordinator,
                                                           streamIdSerializer);
    }
}
