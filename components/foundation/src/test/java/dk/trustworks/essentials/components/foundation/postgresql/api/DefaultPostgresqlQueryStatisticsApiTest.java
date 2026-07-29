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

package dk.trustworks.essentials.components.foundation.postgresql.api;

import dk.trustworks.essentials.components.foundation.transaction.jdbi.*;
import dk.trustworks.essentials.shared.functional.CheckedConsumer;
import dk.trustworks.essentials.shared.security.EssentialsSecurityProvider;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

class DefaultPostgresqlQueryStatisticsApiTest {

    @Test
    void constructor_does_not_fail_when_pg_stat_statements_initialization_fails() {
        var securityProvider = new EssentialsSecurityProvider.AllAccessSecurityProvider();
        var failingFactory = new ThrowingHandleAwareUnitOfWorkFactory();

        assertThatNoException().isThrownBy(() -> {
            var api = new DefaultPostgresqlQueryStatisticsApi(securityProvider, failingFactory);
            assertThat(api.getTopTenSlowestQueries("principal")).isEmpty();
        });
    }

    private static final class ThrowingHandleAwareUnitOfWorkFactory implements HandleAwareUnitOfWorkFactory<HandleAwareUnitOfWork> {

        @Override
        public HandleAwareUnitOfWork getRequiredUnitOfWork() {
            throw new UnsupportedOperationException("Not required in this test");
        }

        @Override
        public HandleAwareUnitOfWork getOrCreateNewUnitOfWork() {
            throw new UnsupportedOperationException("Not required in this test");
        }

        @Override
        public Optional<HandleAwareUnitOfWork> getCurrentUnitOfWork() {
            return Optional.empty();
        }

        @Override
        public void usingUnitOfWork(CheckedConsumer<HandleAwareUnitOfWork> unitOfWorkConsumer) {
            throw new RuntimeException("Simulated initialization failure");
        }
    }
}
