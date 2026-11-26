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

package dk.trustworks.essentials.components.eventlog.processor.architecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.FailoverRedeliveryPolicy;
import dk.trustworks.essentials.components.foundation.messaging.MessageHandler;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.*;
import org.junit.jupiter.api.*;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * The processor module's dependency rules: it re-uses the old engine's value types and
 * serializers, never its subscription model; it serializes only through the application's {@code JSONEventSerializer};
 * Spring stays confined to the classes that are about Spring, so the module runs without it.
 */
class DependencyRulesTest {
    private static JavaClasses moduleClasses;

    private static final String INBOX_PROCESSOR = "dk\\.trustworks\\.essentials\\.components\\.eventlog\\.processor\\.EventLog(Inbox|PartitionedView)Processor(\\$.*)?";

    @BeforeAll
    static void importModuleClasses() {
        moduleClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("dk.trustworks.essentials.components.eventlog.processor");
    }

    @Test
    void reactor_appears_only_in_the_event_store_adapters_poll_signatures() {
        // EventStore's pollEvents/unboundedPollForEvents return Flux; the adapter's overrides throw
        noClasses().that().doNotHaveFullyQualifiedName("dk.trustworks.essentials.components.eventlog.processor.EventLogEventStore")
                   .should().dependOnClassesThat().resideInAnyPackage("reactor..")
                   .check(moduleClasses);
    }

    @Test
    void the_old_subscription_model_is_not_used() {
        // foundation messaging is the durable queues' world; only the @MessageHandler annotation is used here,
        // except by EventLogInboxProcessor and EventLogPartitionedViewProcessor, whose job is forwarding into a queue - checked by the next rule
        noClasses().that().haveNameNotMatching(INBOX_PROCESSOR).should().dependOnClassesThat(JavaClass.Predicates.resideInAnyPackage("dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription..",
                                                                                         "dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor..",
                                                                                         "dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.gap..",
                                                                                         "dk.trustworks.essentials.components.foundation.fencedlock..",
                                                                                         "dk.trustworks.essentials.components.foundation.messaging..")
                                                                     .and(DescribedPredicate.not(JavaClass.Predicates.equivalentTo(MessageHandler.class))))
                   .check(moduleClasses);
    }

    @Test
    void the_inbox_processor_uses_inboxes_but_not_the_old_subscription_model() {
        // FailoverRedeliveryPolicy is the old processor package's only class allowed: a value class, the inbox's default policy
        noClasses().that().haveNameMatching(INBOX_PROCESSOR)
                   .should().dependOnClassesThat(JavaClass.Predicates.resideInAnyPackage("dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription..",
                                                                                         "dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor..",
                                                                                         "dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.gap..",
                                                                                         "dk.trustworks.essentials.components.foundation.fencedlock..")
                                                                     .and(DescribedPredicate.not(JavaClass.Predicates.equivalentTo(FailoverRedeliveryPolicy.class))))
                   .check(moduleClasses);
    }

    @Test
    void no_mapper_is_built_here() {
        noClasses().should().dependOnClassesThat().resideInAnyPackage("tools.jackson..", "com.fasterxml.jackson.databind..")
                   .check(moduleClasses);
    }

    @Test
    void spring_is_confined_to_the_spring_classes() {
        noClasses().that().haveNameNotMatching(".*\\.(SpringPageTransactions|EventLogCommits|UnitOfWorkPageTransactions)(\\$.*)?")
                   .should().dependOnClassesThat().resideInAnyPackage("org.springframework..")
                   .check(moduleClasses);
    }
}
