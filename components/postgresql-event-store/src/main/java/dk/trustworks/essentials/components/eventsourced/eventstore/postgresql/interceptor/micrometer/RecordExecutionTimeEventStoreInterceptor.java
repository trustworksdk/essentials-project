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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.interceptor.micrometer;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.interceptor.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.operations.*;
import dk.trustworks.essentials.shared.measurement.*;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.*;
import java.util.stream.Stream;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * An interceptor that records execution time for event store operations using the {@link MeasurementTaker} API.
 * <p>
 * The metric name always begins with {@value #METRIC_PREFIX} and any dynamic parameters (e.g. aggregate_type) are added as tags.
 */
public class RecordExecutionTimeEventStoreInterceptor implements EventStoreInterceptor {
    public static final String           METRIC_PREFIX   = "essentials.eventstore";
    public static final String           MODULE_TAG_NAME = "Module";
    private final       MeasurementTaker measurementTaker;
    private final       boolean          recordExecutionTimeEnabled;
    private final       String           moduleTag;

    /**
     * Constructs a new interceptor recording to the supplied {@link MeasurementTaker}.
     * <p>
     * There is no separate "enabled" flag: pass {@link MeasurementTaker#none()} to switch recording off. The
     * interceptor branches on {@link MeasurementTaker#isRecording()}, so a disabled interceptor still skips assembling
     * the measurement context — which matters here, since this sits on every event-store operation.
     *
     * @param measurementTaker where event store operation times are recorded. {@link MeasurementTaker#none()} disables recording
     * @param moduleTag        Optional {@value #MODULE_TAG_NAME} Tag value. May be {@code null}, in which case the tag is omitted
     */
    public RecordExecutionTimeEventStoreInterceptor(MeasurementTaker measurementTaker,
                                                    String moduleTag) {
        this.measurementTaker = requireNonNull(measurementTaker, "No measurementTaker provided - use MeasurementTaker.none() to disable recording");
        this.recordExecutionTimeEnabled = measurementTaker.isRecording();
        this.moduleTag = moduleTag;
    }

    /**
     * @param meterRegistryOptional      an Optional MeterRegistry to enable Micrometer metrics
     * @param recordExecutionTimeEnabled whether to record execution times or not
     * @param thresholds                 the logging thresholds configuration
     * @param moduleTag                  Optional {@value #MODULE_TAG_NAME} Tag value
     * @deprecated Use {@link #RecordExecutionTimeEventStoreInterceptor(MeasurementTaker, String)}. Assemble the
     *         {@link MeasurementTaker} once — typically one per metrics subsystem in the Spring Boot starter — rather
     *         than having every interceptor re-derive one from an {@code Optional<MeterRegistry>}. Pass
     *         {@link MeasurementTaker#none()} where {@code recordExecutionTimeEnabled} was {@code false}. This
     *         constructor delegates and behaves identically, except that the logging recorder is now named after this
     *         class rather than after the runtime subclass.
     */
    @Deprecated(forRemoval = true, since = "0.40.x")
    @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
    public RecordExecutionTimeEventStoreInterceptor(Optional<MeterRegistry> meterRegistryOptional,
                                                    boolean recordExecutionTimeEnabled,
                                                    LogThresholds thresholds,
                                                    String moduleTag) {
        this(recordExecutionTimeEnabled
             ? MeasurementTaker.builder()
                               .setLoggingRecorder(RecordExecutionTimeEventStoreInterceptor.class, thresholds)
                               .setMeterRegistry(meterRegistryOptional)
                               .build()
             : MeasurementTaker.none(),
             moduleTag);
    }

    @Override
    public <ID> AggregateEventStream<ID> intercept(AppendToStream<ID> operation,
                                                   EventStoreInterceptorChain<AppendToStream<ID>, AggregateEventStream<ID>> chain) {
        if (recordExecutionTimeEnabled) {
            return measurementTaker.context(METRIC_PREFIX + ".append_to_stream")
                                   .description("Time taken to append events for an aggregate to the event store")
                                   .tag("aggregate_type", operation.getAggregateType())
                                   .optionalTag(MODULE_TAG_NAME, moduleTag)
                                   .record(chain::proceed);
        }
        return chain.proceed();
    }

    @Override
    public <ID> Optional<PersistedEvent> intercept(LoadLastPersistedEventRelatedTo<ID> operation,
                                                   EventStoreInterceptorChain<LoadLastPersistedEventRelatedTo<ID>, Optional<PersistedEvent>> chain) {
        if (recordExecutionTimeEnabled) {
            return measurementTaker.context(METRIC_PREFIX + ".load_last_persisted_event")
                                   .description("Time taken to load last persisted event for an aggregate")
                                   .tag("aggregate_type", operation.getAggregateType())
                                   .optionalTag(MODULE_TAG_NAME, moduleTag)
                                   .record(chain::proceed);
        }
        return chain.proceed();
    }

    @Override
    public Optional<PersistedEvent> intercept(LoadEvent operation,
                                              EventStoreInterceptorChain<LoadEvent, Optional<PersistedEvent>> chain) {
        if (recordExecutionTimeEnabled) {
            return measurementTaker.context(METRIC_PREFIX + ".load_event")
                                   .description("Time taken to load event for an aggregate type")
                                   .tag("aggregate_type", operation.getAggregateType())
                                   .optionalTag(MODULE_TAG_NAME, moduleTag)
                                   .record(chain::proceed);
        }
        return chain.proceed();
    }

    @Override
    public List<PersistedEvent> intercept(LoadEvents operation,
                                          EventStoreInterceptorChain<LoadEvents, List<PersistedEvent>> chain) {
        if (recordExecutionTimeEnabled) {
            return measurementTaker.context(METRIC_PREFIX + ".load_events")
                                   .description("Time taken to load events for an aggregate type")
                                   .tag("aggregate_type", operation.getAggregateType())
                                   .tag("event_ids_size", operation.getEventIds().size())
                                   .optionalTag(MODULE_TAG_NAME, moduleTag)
                                   .record(chain::proceed);
        }
        return chain.proceed();
    }

    @Override
    public <ID> Optional<AggregateEventStream<ID>> intercept(FetchStream<ID> operation,
                                                             EventStoreInterceptorChain<FetchStream<ID>, Optional<AggregateEventStream<ID>>> chain) {
        if (recordExecutionTimeEnabled) {
            return measurementTaker.context(METRIC_PREFIX + ".fetch_stream")
                                   .description("Time taken to fetch event stream for an aggregate")
                                   .tag("aggregate_type", operation.getAggregateType())
                                   .tag("event_order_range", operation.getEventOrderRange().toString())
                                   .optionalTag(MODULE_TAG_NAME, moduleTag)
                                   .record(chain::proceed);
        }
        return chain.proceed();
    }

    @Override
    public Stream<PersistedEvent> intercept(LoadEventsByGlobalOrder operation,
                                            EventStoreInterceptorChain<LoadEventsByGlobalOrder, Stream<PersistedEvent>> chain) {
        if (recordExecutionTimeEnabled) {
            return measurementTaker.context(METRIC_PREFIX + ".load_events_by_global_order")
                                   .description("Time taken to load events by global order for an aggregate type")
                                   .tag("aggregate_type", operation.getAggregateType())
                                   .tag("global_event_order_range", operation.getGlobalEventOrderRange().toString())
                                   .optionalTag(MODULE_TAG_NAME, moduleTag)
                                   .record(chain::proceed);
        }
        return chain.proceed();
    }
}