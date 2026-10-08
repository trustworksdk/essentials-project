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
package dk.trustworks.essentials.components.boot.autoconfigure.postgresql;

import dk.trustworks.essentials.components.foundation.postgresql.PostgresqlEmitSchemaApplier;
import dk.trustworks.essentials.components.foundation.schema.*;
import dk.trustworks.essentials.components.foundation.startup.EssentialsStartupReport;
import org.slf4j.*;
import org.springframework.beans.factory.*;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.ApplicationListener;

import java.util.*;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Runs the {@link EssentialsSchemaHarness} over every {@link EssentialsSchemaContributor} bean, once all singletons
 * exist and before any lifecycle is started - so {@code validate} refuses to start before a single consumer runs, and
 * every {@link DynamicSchemaContributor} is attached before the application registers anything at runtime.
 * <p>
 * In {@link SchemaMode#EMIT} the application is stopped with exit code 0 as soon as it has started, unless
 * {@code essentials.schema.emit.exit=false}. The Essentials lifecycles are not started in that mode, so nothing
 * consumes, polls or subscribes in between.
 */
public final class EssentialsSchemaHarnessRunner implements SmartInitializingSingleton, ApplicationListener<ApplicationStartedEvent> {
    private static final Logger log = LoggerFactory.getLogger(EssentialsSchemaHarnessRunner.class);

    private final SchemaApplier                               applier;
    private final ObjectProvider<EssentialsSchemaContributor> contributors;
    private final ObjectProvider<SchemaContext>               context;
    private final SchemaMode                                  mode;
    private final boolean                                     exitAfterEmit;

    public EssentialsSchemaHarnessRunner(SchemaApplier applier,
                                         ObjectProvider<EssentialsSchemaContributor> contributors,
                                         ObjectProvider<SchemaContext> context,
                                         SchemaMode mode,
                                         boolean exitAfterEmit) {
        this.applier = requireNonNull(applier, "No applier provided");
        this.contributors = requireNonNull(contributors, "No contributors provided");
        this.context = requireNonNull(context, "No context provided");
        this.mode = requireNonNull(mode, "No mode provided");
        this.exitAfterEmit = exitAfterEmit;
    }

    @Override
    public void afterSingletonsInstantiated() {
        // One component may be reachable as several beans, so identity - not equality - decides what is one contributor
        var distinct = Collections.newSetFromMap(new IdentityHashMap<EssentialsSchemaContributor, Boolean>());
        var found    = new ArrayList<EssentialsSchemaContributor>();
        contributors.orderedStream().filter(distinct::add).forEach(found::add);
        log.info("Schema mode '{}': {} schema contributor(s)", mode.name().toLowerCase(Locale.ROOT), found.size());
        new EssentialsSchemaHarness(applier, context.getIfAvailable(SchemaContext::empty), found).apply();
        addStartupReportNote(found.size());
    }

    /**
     * The RN-5 notes for the startup report: what {@code emit} and {@code validate} cover, and what they cannot
     */
    private void addStartupReportNote(int contributors) {
        var registerAtStartup = "Register every AggregateType and schema-owning component at start-up (as singleton beans or in their component's configuration), " +
                "not from an ApplicationRunner, a @Lazy bean or on demand, and re-run the emit mode after adding one";
        if (mode == SchemaMode.EMIT) {
            var scriptFile = applier instanceof PostgresqlEmitSchemaApplier emitApplier ? "'" + emitApplier.getScriptFile().toAbsolutePath() + "'" : "the script";
            EssentialsStartupReport.addToCurrent(EssentialsStartupReport.Finding.info(
                    "essentials.schema.mode=emit",
                    "the schema of " + contributors + " contributor(s) is written to " + scriptFile + (exitAfterEmit ? " and the application stops once started" : "") +
                            "; it covers what was registered during start-up only",
                    registerAtStartup));
        } else if (mode == SchemaMode.VALIDATE) {
            EssentialsStartupReport.addToCurrent(EssentialsStartupReport.Finding.info(
                    "essentials.schema.mode=validate",
                    "the schema of " + contributors + " contributor(s) was validated against the ledger; an AggregateType registered later is validated when it is " +
                            "registered and fails with a SchemaValidationException naming it if its table is not in the ledger",
                    registerAtStartup));
        }
    }

    @Override
    public void onApplicationEvent(ApplicationStartedEvent event) {
        if (mode != SchemaMode.EMIT || !exitAfterEmit) {
            return;
        }
        if (applier instanceof PostgresqlEmitSchemaApplier emitApplier) {
            log.info("Schema mode 'emit': the script is written to '{}' - stopping the application. It covers what was registered by now; " +
                             "anything registered later - from an ApplicationRunner, a @Lazy bean or on demand - is not in it",
                     emitApplier.getScriptFile().toAbsolutePath());
            emitApplier.exitTriggered();
        } else {
            log.info("Schema mode 'emit': the script is written - stopping the application");
        }
        System.exit(SpringApplication.exit(event.getApplicationContext(), () -> 0));
    }
}
