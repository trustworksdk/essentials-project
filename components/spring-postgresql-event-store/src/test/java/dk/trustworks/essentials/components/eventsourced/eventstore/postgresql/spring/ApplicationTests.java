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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.spring;

import org.jdbi.v3.core.Jdbi;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;

import javax.sql.DataSource;

@SpringBootApplication
class ApplicationTests {
    /**
     * Contributes the Essentials types module to Spring's Jackson 2 {@code ObjectMapper}, when the active Jackson
     * flavor has one. Under the Jackson 3 flavor {@code EssentialTypesJacksonModule} extends
     * {@code tools.jackson.databind.module.SimpleModule} instead, so it is not a Jackson 2 module and there is
     * nothing to contribute here — an empty module keeps the bean contract without failing the context.
     * <p>
     * Note the {@link LinkageError}: the class file is on the classpath under either flavor, so
     * {@code Class.forName} does not fail with {@code ClassNotFoundException} — it fails while resolving the
     * <em>superclass</em>, which surfaces as {@code NoClassDefFoundError}.
     */
    @Bean
    public com.fasterxml.jackson.databind.Module essentialJacksonModule() {
        try {
            var moduleClass = Class.forName("dk.trustworks.essentials.jackson.types.EssentialTypesJacksonModule");
            var module      = moduleClass.getDeclaredConstructor().newInstance();
            if (module instanceof com.fasterxml.jackson.databind.Module jacksonModule) {
                return jacksonModule;
            }
        } catch (ReflectiveOperationException | LinkageError e) {
            // Falls through to the no-op module below.
        }
        return new com.fasterxml.jackson.databind.module.SimpleModule("essentials-types-absent-for-active-jackson-flavor");
    }

    @Bean
    public Jdbi jdbi(DataSource dataSource) {
        Jdbi jdbi = Jdbi.create(new TransactionAwareDataSourceProxy(dataSource));
        return jdbi;
    }
}
