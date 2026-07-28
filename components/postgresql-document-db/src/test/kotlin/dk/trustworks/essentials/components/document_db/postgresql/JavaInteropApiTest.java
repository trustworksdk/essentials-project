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

import dk.trustworks.essentials.components.document_db.DocumentDbRepository;
import dk.trustworks.essentials.components.document_db.DocumentDbRepositoryFactory;
import dk.trustworks.essentials.components.document_db.Index;
import dk.trustworks.essentials.components.document_db.JavaVersionedEntity;
import dk.trustworks.essentials.components.document_db.Version;
import dk.trustworks.essentials.components.document_db.VersionedEntity;
import dk.trustworks.essentials.components.document_db.annotations.DocumentEntity;
import dk.trustworks.essentials.components.document_db.annotations.Id;
import dk.trustworks.essentials.components.document_db.annotations.Indexed;
import kotlin.jvm.JvmClassMappingKt;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaInteropApiTest {

    @Test
    void javaEntityAnnotationsAndVersionBridgeAreSupported() {
        var kotlinClass = JvmClassMappingKt.getKotlinClass(JavaProduct.class);
        EntityConfiguration<String, JavaProduct> configuration = EntityConfiguration.Companion.configureEntity(kotlinClass);

        assertEquals("java_products", configuration.tableName());
        assertEquals("id", configuration.idProperty().getName());
        assertEquals("name", configuration.indexedFields().get(0).getName());

        JavaProduct entity = new JavaProduct();
        entity.setVersionValue(17L);
        assertEquals(17L, entity.getVersionValue());
        assertEquals(Version.NOT_SAVED_YET_VALUE, new JavaProduct().getVersionValue());
    }

    @Test
    void javaFriendlyApiSurfaceIsAvailable() throws Exception {
        Method createForStringIdClass = DocumentDbRepositoryFactory.class.getMethod("createForStringId", Class.class);
        Method createForCompositeIdClassFunction = null;
        for (Method method : DocumentDbRepositoryFactory.class.getMethods()) {
            if (method.getName().equals("createForCompositeId") && method.getParameterCount() == 2 &&
                method.getParameterTypes()[0] == Class.class &&
                method.getParameterTypes()[1].getName().equals("java.util.function.Function")) {
                createForCompositeIdClassFunction = method;
            }
        }

        Method saveWithLong = DocumentDbRepository.class.getMethod("save", VersionedEntity.class, long.class);
        Method updateWithLong = DocumentDbRepository.class.getMethod("update", VersionedEntity.class, long.class);

        assertNotNull(createForStringIdClass);
        assertNotNull(createForCompositeIdClassFunction);
        assertNotNull(saveWithLong);
        assertNotNull(updateWithLong);
    }

    @Test
    void javaFriendlyPathHelpersAreAvailable() {
        Index<JavaProduct> index = Index.fromPaths("idx_name_city", "name", "address.city");
        JsonPathProperty<JavaProduct> pathProperty = new JsonPathProperty<>("address.city");

        assertEquals("idx_name_city", index.getName());
        assertEquals(2, index.getProperties().size());
        assertEquals("address_city", pathProperty.name());
        assertEquals("data->'address'->>'city'", pathProperty.toJSONValueArrowPath());
        assertEquals("data->'address'->'city'", pathProperty.toJSONArrowPath());
        assertTrue(pathProperty.returnType().isMarkedNullable());
    }

    @DocumentEntity(tableName = "java_products")
    static class JavaProduct extends JavaVersionedEntity<String, JavaProduct> {
        @Id
        private String id;
        @Indexed
        private String name;
        private long version = Version.NOT_SAVED_YET_VALUE;
        private OffsetDateTime lastUpdated = OffsetDateTime.now(ZoneOffset.UTC);

        JavaProduct() {
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

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }
}
