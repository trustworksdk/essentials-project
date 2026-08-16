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

import dk.trustworks.essentials.components.foundation.messaging.MessageHandler;
import dk.trustworks.essentials.components.foundation.messaging.eip.store_and_forward.MessageHandlerInterceptor;
import dk.trustworks.essentials.components.foundation.messaging.eip.store_and_forward.operation.InvokeMessageHandlerMethod;
import dk.trustworks.essentials.shared.interceptor.InterceptorChain;
import dk.trustworks.essentials.shared.measurement.*;
import io.micrometer.core.instrument.MeterRegistry;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Measure {@link MessageHandler} annotated methods processing time using the {@link MeasurementTaker} API.
 * <p>
 * The metric name is {@value #METRIC} and any dynamic parameters (e.g. message_type, message_handler_class, message_handler_method)
 * are added as tags.
 */
public class RecordExecutionTimeMessageHandlerInterceptor implements MessageHandlerInterceptor {
    public static final  String                            MODULE_TAG_NAME  = "Module";
    public static final  String                            METRIC           = "essentials.messaging.message_handler";
    private final        MeasurementTaker                  measurementTaker;
    /**
     * Key: Metod<br>
     * Value: logging-friendly name
     */
    private static final ConcurrentHashMap<Method, String> loggingNameCache = new ConcurrentHashMap<>();


    private final boolean recordExecutionTimeEnabled;
    private final String  moduleTag;

    /**
     * Constructs a new interceptor recording to the supplied {@link MeasurementTaker}.
     * <p>
     * There is no separate "enabled" flag: pass {@link MeasurementTaker#none()} to switch recording off. The
     * interceptor branches on {@link MeasurementTaker#isRecording()}, so a disabled interceptor still skips resolving
     * the method name and assembling the {@link MeasurementContext} — exactly as the old
     * {@code recordExecutionTimeEnabled} flag did.
     *
     * @param measurementTaker where message-handler execution times are recorded. {@link MeasurementTaker#none()} disables recording
     * @param moduleTag        Optional {@value #MODULE_TAG_NAME} Tag value. May be {@code null}, in which case the tag is omitted
     */
    public RecordExecutionTimeMessageHandlerInterceptor(MeasurementTaker measurementTaker,
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
     * @deprecated Use {@link #RecordExecutionTimeMessageHandlerInterceptor(MeasurementTaker, String)}. Assemble the
     *         {@link MeasurementTaker} once — typically one per metrics subsystem in the Spring Boot starter — rather
     *         than having every interceptor re-derive one from an {@code Optional<MeterRegistry>}. Pass
     *         {@link MeasurementTaker#none()} where {@code recordExecutionTimeEnabled} was {@code false}. This
     *         constructor delegates and behaves identically, except that the logging recorder is now named after this
     *         class rather than after the runtime subclass.
     */
    @Deprecated(forRemoval = true, since = "0.40.x")
    @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
    public RecordExecutionTimeMessageHandlerInterceptor(Optional<MeterRegistry> meterRegistryOptional,
                                                        boolean recordExecutionTimeEnabled,
                                                        LogThresholds thresholds,
                                                        String moduleTag) {
        this(recordExecutionTimeEnabled
             ? MeasurementTaker.builder()
                               .setLoggingRecorder(RecordExecutionTimeMessageHandlerInterceptor.class, thresholds)
                               .setMeterRegistry(meterRegistryOptional)
                               .build()
             : MeasurementTaker.none(),
             moduleTag);
    }

    @Override
    public void intercept(InvokeMessageHandlerMethod operation, InterceptorChain<InvokeMessageHandlerMethod, Void, MessageHandlerInterceptor> interceptorChain) {
        if (recordExecutionTimeEnabled) {
            var methodLoggingName = loggingNameCache.computeIfAbsent(operation.methodToInvoke, method -> getMethodDescription(operation));
            measurementTaker.context(METRIC)
                            .description("Time taken to handle a message")
                            .tag("message_handler_class", operation.methodToInvoke.getDeclaringClass().getSimpleName())
                            .tag("message_handler_method", methodLoggingName)
                            .tag("message_type", operation.resolvedInvokeMethodWithArgumentOfType.getName())
                            .optionalTag(MODULE_TAG_NAME, moduleTag)
                            .record(interceptorChain::proceed);
        } else {
            interceptorChain.proceed();
        }
    }

    private static String getMethodDescription(InvokeMessageHandlerMethod operation) {
        return operation.methodToInvoke.getName() + "(" + Arrays.stream(operation.methodToInvoke.getParameterTypes()).map(Class::getSimpleName).collect(Collectors.joining(", ")) + ")";
    }
}
