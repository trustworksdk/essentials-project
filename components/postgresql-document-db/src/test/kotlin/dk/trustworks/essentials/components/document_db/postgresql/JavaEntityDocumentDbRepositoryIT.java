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

package dk.trustworks.essentials.components.document_db.postgresql;

import dk.trustworks.essentials.components.document_db.*;
import dk.trustworks.essentials.components.document_db.annotations.*;
import dk.trustworks.essentials.components.foundation.json.EssentialsObjectMappers;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.JdbiUnitOfWorkFactory;
import dk.trustworks.essentials.types.CharSequenceType;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A Java entity the way Java users write one: a {@link CharSequenceType} id held in a <b>private</b> {@code @Id} field,
 * created from Java through the typed {@link DocumentDbRepositoryFactory#createForCharSequenceTypeId(Class)} (DD-3).
 * Before 0.60.1 the private id passed configuration and then failed every save/update/delete with an
 * {@code IllegalCallableAccessException}, and a {@code CharSequenceType} id had no factory that compiled without a raw type.
 */
@Testcontainers
class JavaEntityDocumentDbRepositoryIT {
    @Container
    static final PostgreSQLContainer postgresContainer = new PostgreSQLContainer("postgres:18.4")
            .withDatabaseName("testdb")
            .withUsername("test")
            .withPassword("test");

    private DocumentDbRepository<JavaOrderView, JavaOrderId> repository;

    @BeforeEach
    void setup() {
        var jdbi = Jdbi.create(postgresContainer.getJdbcUrl(), postgresContainer.getUsername(), postgresContainer.getPassword());
        var repositoryFactory = new DocumentDbRepositoryFactory(jdbi,
                                                                new JdbiUnitOfWorkFactory(jdbi),
                                                                EssentialsObjectMappers.createJSONSerializer());
        repository = repositoryFactory.createForCharSequenceTypeId(JavaOrderView.class);
        repository.deleteAll();
    }

    @Test
    void saves_reads_updates_and_deletes_an_entity_with_a_private_CharSequenceType_id() {
        var orderId = JavaOrderId.of("order-1");

        repository.save(new JavaOrderView(orderId, "REGISTERED"), Version.ZERO_VALUE);

        var loaded = repository.getById(orderId);
        assertThat((Object) loaded.getOrderId()).isEqualTo(orderId);
        assertThat(loaded.getStatus()).isEqualTo("REGISTERED");
        assertThat(loaded.getVersionValue()).isEqualTo(0L);

        loaded.setStatus("SHIPPED");
        repository.update(loaded);

        var updated = repository.findById(orderId);
        assertThat(updated).isNotNull();
        assertThat(updated.getStatus()).isEqualTo("SHIPPED");
        assertThat(updated.getVersionValue()).isEqualTo(1L);

        repository.delete(updated);
        assertThat(repository.existsById(orderId)).isFalse();
    }

    public static final class JavaOrderId extends CharSequenceType<JavaOrderId> {
        public JavaOrderId(CharSequence value) {
            super(value);
        }

        public JavaOrderId(String value) {
            super(value);
        }

        public static JavaOrderId of(CharSequence value) {
            return new JavaOrderId(value);
        }
    }

    @DocumentEntity(tableName = "java_order_views")
    public static class JavaOrderView extends JavaVersionedEntity<JavaOrderId, JavaOrderView> {
        @Id
        private JavaOrderId    orderId;
        @Indexed
        private String         status;
        private long           version     = Version.NOT_SAVED_YET_VALUE;
        private OffsetDateTime lastUpdated = OffsetDateTime.now(ZoneOffset.UTC);

        public JavaOrderView() {
        }

        public JavaOrderView(JavaOrderId orderId, String status) {
            this.orderId = orderId;
            this.status  = status;
        }

        @Override
        public long getVersionValue() {
            return version;
        }

        @Override
        public void setVersionValue(long version) {
            this.version = version;
        }

        @Override
        public OffsetDateTime getLastUpdated() {
            return lastUpdated;
        }

        @Override
        public void setLastUpdated(OffsetDateTime lastUpdated) {
            this.lastUpdated = lastUpdated;
        }

        public JavaOrderId getOrderId() {
            return orderId;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }
    }
}
