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

package dk.trustworks.essentials.shared.reflection.invocation;

import dk.trustworks.essentials.shared.measurement.*;
import dk.trustworks.essentials.shared.reflection.FunctionalInterfaceLoggingNameResolver;
import org.slf4j.Logger;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * {@link LoggerAwareInvocationTracker} that reports every dispatched method invocation through a
 * {@link MeasurementTaker}.
 * <p>
 * There are two ways to set it up:
 * <ul>
 *     <li>{@link #MeasurementInvocationTracker(MeasurementTaker)} — you supply the {@link MeasurementTaker}, so the
 *         invocation timings land wherever the rest of that subsystem's measurements go (Micrometer, logging, or
 *         both). This is the preferred form.</li>
 *     <li>{@link #MeasurementInvocationTracker()} — no taker supplied; one is derived from the {@link Logger} that
 *         {@link PatternMatchingMethodInvoker} injects via {@link #setLogger(Logger)}, using
 *         {@link #getLogThresholds()}. This is log-only.</li>
 * </ul>
 * Either way the {@link MeasurementTaker} is resolved once, not per invocation — this sits on the dispatch path of
 * every event and message handler in the system.
 */
public class MeasurementInvocationTracker implements LoggerAwareInvocationTracker {
    private              MeasurementTaker                  measurementTaker;
    private final        boolean                           measurementTakerSuppliedByCaller;
    /**
     * Key: Metod<br>
     * Value: logging-friendly name
     */
    private static final ConcurrentHashMap<Method, String> loggingNameCache = new ConcurrentHashMap<>();

    /**
     * Creates a tracker with no {@link MeasurementTaker} yet: one is built lazily from the {@link Logger} handed to
     * {@link #setLogger(Logger)}, combined with {@link #getLogThresholds()}. Until that happens the tracker records
     * nothing.
     */
    public MeasurementInvocationTracker() {
        this.measurementTaker = MeasurementTaker.none();
        this.measurementTakerSuppliedByCaller = false;
    }

    /**
     * Creates a tracker that reports to the supplied {@link MeasurementTaker}. A later {@link #setLogger(Logger)} call
     * does <b>not</b> replace it — an explicitly supplied taker wins, since the caller has already decided where these
     * measurements should go.
     *
     * @param measurementTaker where invocation timings are recorded. Pass {@link MeasurementTaker#none()} to record nothing
     */
    public MeasurementInvocationTracker(MeasurementTaker measurementTaker) {
        this.measurementTaker = requireNonNull(measurementTaker, "No measurementTaker provided - use MeasurementTaker.none() to record nothing");
        this.measurementTakerSuppliedByCaller = true;
    }

    @Override
    public void trackMethodInvoked(Method method, Object invokeMethodsOn, Duration duration, Object argument) {
        var methodLoggingName = loggingNameCache.computeIfAbsent(method, MeasurementInvocationTracker::getMethodDescription);

        measurementTaker.recordTime(MeasurementContext.builder("essentials.invocation")
                                                      .description("Time it takes to invoke a method")
                                                      .tag("class", FunctionalInterfaceLoggingNameResolver.resolveLoggingName(invokeMethodsOn))
                                                      .tag("method", methodLoggingName)
                                                      .build(),
                                    duration);
    }

    /**
     * Override this method to provide a custom {@link LogThresholds}
     *
     * @return The {@link LogThresholds} to use for logging invocation metrics
     */
    protected LogThresholds getLogThresholds() {
        return LogThresholds.defaultThresholds();
    }

    @Override
    public void setLogger(Logger logger) {
        if (measurementTakerSuppliedByCaller) {
            return;
        }
        this.measurementTaker = logger == null
                                ? MeasurementTaker.none()
                                : MeasurementTaker.builder()
                                                  .setLoggingRecorder(logger, getLogThresholds())
                                                  .build();
    }

    private static String getMethodDescription(Method method) {
        return method.getName() + "(" + Arrays.stream(method.getParameterTypes()).map(Class::getSimpleName).collect(Collectors.joining(", ")) + ")";
    }
}
