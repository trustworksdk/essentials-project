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

package dk.trustworks.essentials.components.foundation.interceptor.micrometer;

import dk.trustworks.essentials.components.foundation.messaging.queue.QueuedMessage;
import dk.trustworks.essentials.reactive.command.*;
import dk.trustworks.essentials.reactive.command.interceptor.*;
import dk.trustworks.essentials.shared.measurement.*;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.Optional;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Measure the time {@link CommandBus#send(Object)}/{@link CommandBus#sendAsync(Object)} and {@link CommandBus#sendAndDontWait(Object)}
 * take to process the command (this includes the time it takes
 * to handle the command by the selected {@link CommandHandler} measured using the {@link MeasurementTaker} API.
 * <p>
 * The metric name always begins with {@value #METRIC_PREFIX} and any dynamic parameters (e.g. command_type) are added as tags.
 */
public class RecordExecutionTimeCommandBusInterceptor implements CommandBusInterceptor {
    private final       MeasurementTaker measurementTaker;
    public static final String           MODULE_TAG_NAME = "Module";
    public static final String           METRIC_PREFIX   = "essentials.reactive.commandbus";
    private final       boolean          recordExecutionTimeEnabled;
    private final       String           moduleTag;

    /**
     * Constructs a new interceptor recording to the supplied {@link MeasurementTaker}.
     * <p>
     * There is no separate "enabled" flag: pass {@link MeasurementTaker#none()} to switch recording off. The
     * interceptor branches on {@link MeasurementTaker#isRecording()}, so a disabled interceptor still skips assembling
     * the {@link dk.trustworks.essentials.shared.measurement.MeasurementContext} — exactly as the old
     * {@code recordExecutionTimeEnabled} flag did.
     *
     * @param measurementTaker where command execution times are recorded. {@link MeasurementTaker#none()} disables recording
     * @param moduleTag        Optional {@value #MODULE_TAG_NAME} Tag value. May be {@code null}, in which case the tag is omitted
     */
    public RecordExecutionTimeCommandBusInterceptor(MeasurementTaker measurementTaker,
                                                    String moduleTag) {
        this.measurementTaker = requireNonNull(measurementTaker, "No measurementTaker provided - use MeasurementTaker.none() to disable recording");
        this.recordExecutionTimeEnabled = measurementTaker.isRecording();
        this.moduleTag = moduleTag;
    }

    /**
     * Constructs a new interceptor.
     *
     * @param meterRegistryOptional      an Optional MeterRegistry to enable Micrometer metrics
     * @param recordExecutionTimeEnabled whether to record execution times or not
     * @param thresholds                 the logging thresholds configuration
     * @param moduleTag                  Optional {@value #MODULE_TAG_NAME} Tag value
     * @deprecated Use {@link #RecordExecutionTimeCommandBusInterceptor(MeasurementTaker, String)}. Assemble the
     *         {@link MeasurementTaker} once — typically one per metrics subsystem in the Spring Boot starter — rather
     *         than having every interceptor re-derive one from an {@code Optional<MeterRegistry>}. Pass
     *         {@link MeasurementTaker#none()} where {@code recordExecutionTimeEnabled} was {@code false}. This
     *         constructor delegates and behaves identically, except that the logging recorder is now named after this
     *         class rather than after the runtime subclass.
     */
    @Deprecated(forRemoval = true, since = "0.40.x")
    @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
    public RecordExecutionTimeCommandBusInterceptor(Optional<MeterRegistry> meterRegistryOptional,
                                                    boolean recordExecutionTimeEnabled,
                                                    LogThresholds thresholds,
                                                    String moduleTag) {
        this(recordExecutionTimeEnabled
             ? MeasurementTaker.builder()
                               .setLoggingRecorder(RecordExecutionTimeCommandBusInterceptor.class, thresholds)
                               .setMeterRegistry(meterRegistryOptional)
                               .build()
             : MeasurementTaker.none(),
             moduleTag);
    }

    @Override
    public Object interceptSend(Object command, CommandBusInterceptorChain commandBusInterceptorChain) {
        if (recordExecutionTimeEnabled) {
            return measurementTaker.context(METRIC_PREFIX + ".send")
                                   .description("Time taken to handle a command sent using send")
                                   .tag("command_type", command.getClass().getName())
                                   .optionalTag(MODULE_TAG_NAME, moduleTag)
                                   .record(commandBusInterceptorChain::proceed);
        } else {
            return commandBusInterceptorChain.proceed();
        }
    }

    @Override
    public Object interceptSendAsync(Object command, CommandBusInterceptorChain commandBusInterceptorChain) {
        if (recordExecutionTimeEnabled) {
            return measurementTaker.context(METRIC_PREFIX + ".sendAsync")
                                   .description("Time taken to handle a command sent using sendAsync")
                                   .tag("command_type", command.getClass().getName())
                                   .optionalTag(MODULE_TAG_NAME, moduleTag)
                                   .record(commandBusInterceptorChain::proceed);
        } else {
            return commandBusInterceptorChain.proceed();
        }
    }

    @Override
    public void interceptSendAndDontWait(Object commandMessage, CommandBusInterceptorChain commandBusInterceptorChain) {
        if (recordExecutionTimeEnabled) {
            var commandType = commandMessage.getClass().getName();
            if (commandMessage instanceof QueuedMessage queuedMessage && queuedMessage.getMessage().getPayload() != null) {
                commandType = queuedMessage.getMessage().getPayload().getClass().getName();
            }
            measurementTaker.context(METRIC_PREFIX + ".sendAndDontWait")
                            .description("Time taken to handle a command sent using sendAndDontWait")
                            .tag("command_type", commandType)
                            .optionalTag(MODULE_TAG_NAME, moduleTag)
                            .record(commandBusInterceptorChain::proceed);
        } else {
            commandBusInterceptorChain.proceed();
        }
    }
}
