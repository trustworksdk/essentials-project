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

package dk.trustworks.essentials.spring.examples.postgresql.cqrs.config;

import dk.trustworks.essentials.components.document_db.DocumentDbRepositoryFactory;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.JSONEventSerializer;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.EventStoreUnitOfWork;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.EventStoreUnitOfWorkFactory;
import org.jdbi.v3.core.Jdbi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The one piece of shared wiring the view slices need. {@code postgresql-document-db} carries no Spring
 * dependency of its own, so nothing auto-configures this factory — each view slice then declares its own
 * {@code DocumentDbRepository} bean from it, and owns that read model alone.
 * <p>
 * All three collaborators already exist in the context:
 * <ul>
 *     <li>{@link Jdbi} from {@code spring-boot-starter-postgresql}</li>
 *     <li>{@link EventStoreUnitOfWorkFactory}, which is a {@code HandleAwareUnitOfWorkFactory} — so a
 *         projection writes its read model in the <em>same</em> transaction that read the events</li>
 *     <li>{@link JSONEventSerializer}, which extends {@code JSONSerializer}. Reusing it rather than building
 *         a mapper here is what keeps the read models on the same Jackson-flavour-neutral configuration as
 *         the event store, so both Jackson profiles write byte-identical JSON</li>
 * </ul>
 */
@Configuration
public class DocumentDbConfiguration {

    @Bean
    public DocumentDbRepositoryFactory documentDbRepositoryFactory(Jdbi jdbi,
                                                                   EventStoreUnitOfWorkFactory<? extends EventStoreUnitOfWork> unitOfWorkFactory,
                                                                   JSONEventSerializer jsonSerializer) {
        return new DocumentDbRepositoryFactory(jdbi,
                                               unitOfWorkFactory,
                                               jsonSerializer);
    }
}
