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

package dk.trustworks.essentials.components.foundation.boot;

import dk.trustworks.essentials.components.foundation.json.*;
import dk.trustworks.essentials.components.foundation.startup.EssentialsStartupReport;
import org.slf4j.*;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;

import java.util.List;
import java.util.stream.Collectors;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Runs {@link EssentialsObjectMappers#findWireFormatDeviations(tools.jackson.databind.ObjectMapper)} against every
 * {@link JSONSerializer} bean backed by a {@link Jackson3JSONSerializer} - which includes the event store's
 * {@code JSONEventSerializer} - as it is created.
 * <p>
 * The starters' serializer beans are {@code @ConditionalOnMissingBean} and {@link Jackson3JSONSerializer} accepts any
 * mapper, so an application can replace them with one built on a hand-configured mapper that writes a different JSON
 * format than the one already persisted. Nothing else would notice until data written by another version failed to
 * read. Spring Boot's own web {@code JsonMapper} is not a {@link JSONSerializer} and is not checked.
 * <p>
 * {@code essentials.json.wire-format-check}: {@code warn} (default) logs one ERROR banner per deviating bean, {@code fail}
 * fails the bean's creation, {@code off} skips the check.
 */
public final class WireFormatCheckBeanPostProcessor implements BeanPostProcessor, EnvironmentAware {
    /** The property selecting the {@link WireFormatCheckMode}. */
    public static final String PROPERTY = "essentials.json.wire-format-check";

    private static final Logger log = LoggerFactory.getLogger(WireFormatCheckBeanPostProcessor.class);

    private WireFormatCheckMode mode = WireFormatCheckMode.WARN;

    @Override
    public void setEnvironment(Environment environment) {
        requireNonNull(environment, "No environment provided");
        this.mode = Binder.get(environment).bind(PROPERTY, WireFormatCheckMode.class).orElse(WireFormatCheckMode.WARN);
    }

    /**
     * @return the mode bound from {@value #PROPERTY}
     */
    public WireFormatCheckMode getMode() {
        return mode;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (mode == WireFormatCheckMode.OFF || !(bean instanceof Jackson3JSONSerializer serializer)) {
            return bean;
        }
        var deviations = EssentialsObjectMappers.findWireFormatDeviations(serializer.getObjectMapper());
        if (deviations.isEmpty()) {
            return bean;
        }
        var report = report(beanName, serializer, deviations);
        if (mode == WireFormatCheckMode.FAIL) {
            throw new IllegalStateException(report);
        }
        log.error(report);
        EssentialsStartupReport.addToCurrent(EssentialsStartupReport.Finding.error(
                "JSONSerializer bean '" + beanName + "'",
                "does not write and read the JSON format Essentials persists: " + deviations.stream().map(String::valueOf).collect(Collectors.joining("; ")),
                "Build its mapper with EssentialsObjectMappers.createJackson3ObjectMapper(additionalModules...) instead of configuring a JsonMapper by hand. "
                        + PROPERTY + "=fail stops the application instead, =off skips the check"));
        return bean;
    }

    static String report(String beanName, Jackson3JSONSerializer serializer, List<WireFormatDeviation> deviations) {
        var banner = "*".repeat(120);
        return "\n" + banner + "\n"
                + "Essentials wire-format check: the JSON serializer bean '" + beanName + "' (" + serializer.getClass().getName() + ") does not\n"
                + "write and read the JSON format Essentials persists (event payloads, event metadata, durable-queue messages).\n"
                + "Data it writes may not be readable by other versions of this application, and data they wrote may not be readable by it.\n"
                + deviations.stream().map(deviation -> "  - " + deviation).collect(Collectors.joining("\n")) + "\n"
                + "Fix: build its mapper with EssentialsObjectMappers.createJackson3ObjectMapper(additionalModules...), which carries the\n"
                + "persisted-format configuration, rather than configuring a JsonMapper by hand.\n"
                + "Set " + PROPERTY + "=fail to stop the application instead, or =off to skip this check.\n"
                + banner;
    }
}
