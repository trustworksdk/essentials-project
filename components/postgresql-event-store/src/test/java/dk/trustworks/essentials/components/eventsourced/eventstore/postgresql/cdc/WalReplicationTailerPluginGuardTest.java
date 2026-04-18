/*
 *  Copyright 2021-2026 the original author or authors.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.CdcProperties.WalReplicationTailerProperties;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.HandleAwareUnitOfWork;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.HandleAwareUnitOfWorkFactory;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WalReplicationTailerPluginGuardTest {

    @Test
    void auto_mode_falls_back_when_plugin_pipeline_is_not_supported() {
        var availability = new CdcAvailability();
        var tailer = tailer(CdcMode.AUTO, availability);

        tailer.start();

        assertThat(tailer.isStarted()).isFalse();
        assertThat(availability.getState()).isEqualTo(CdcAvailability.State.FAILED);
        assertThat(availability.snapshot().reason()).contains("test-plugin").contains("unsupported");
    }

    @Test
    void require_mode_throws_when_plugin_pipeline_is_not_supported() {
        var availability = new CdcAvailability();
        var tailer = tailer(CdcMode.REQUIRE, availability);

        assertThatThrownBy(tailer::start)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("test-plugin")
                .hasMessageContaining("required");
    }

    private static WalReplicationTailer tailer(CdcMode cdcMode, CdcAvailability availability) {
        var jdbi = Jdbi.create("jdbc:postgresql://localhost:5432/test-db", "user", "password");
        var unitOfWorkFactory = new NoOpUnitOfWorkFactory();
        var replicationDataSource = new PGSimpleDataSource();
        var inboxRepository = new NoOpCdcInboxRepository(unitOfWorkFactory);

        return new WalReplicationTailer(
                replicationDataSource,
                jdbi,
                unitOfWorkFactory,
                "slot_pgoutput_guard",
                inboxRepository,
                WalReplicationTailerProperties.defaults(
                        Duration.ofMillis(25),
                        Duration.ofMillis(50),
                        Duration.ofSeconds(1),
                        Duration.ofMillis(100)
                ),
                PgSlotMode.CREATE_IF_MISSING,
                cdcMode,
                CdcProperties.CdcDeliveryMode.INBOX,
                CdcProperties.WalParserMode.BYTES,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(new UnsupportedLogicalDecodingPlugin()),
                availability,
                Optional.empty(),
                Optional.empty()
        );
    }

    private static final class UnsupportedLogicalDecodingPlugin implements LogicalDecodingPlugin {
        @Override
        public String pluginName() {
            return "test-plugin";
        }

        @Override
        public Optional<String> unusableReason(org.jdbi.v3.core.Handle handle) {
            return Optional.empty();
        }

        @Override
        public Map<String, Object> slotOptions() {
            return Map.of();
        }

        @Override
        public boolean supportsCurrentPayloadPipeline() {
            return false;
        }

        @Override
        public String unsupportedReason() {
            return "CDC plugin 'test-plugin' is configured, but the payload pipeline is unsupported";
        }
    }

    private static final class NoOpCdcInboxRepository extends CdcInboxRepository {
        public NoOpCdcInboxRepository(HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory) {
            super(unitOfWorkFactory);
        }

        @Override
        public void createTableAndIndexes() {
        }
    }

    private static final class NoOpUnitOfWorkFactory implements HandleAwareUnitOfWorkFactory<HandleAwareUnitOfWork> {
        @Override
        public HandleAwareUnitOfWork getRequiredUnitOfWork() {
            throw new UnsupportedOperationException("No UnitOfWork available in this test");
        }

        @Override
        public HandleAwareUnitOfWork getOrCreateNewUnitOfWork() {
            throw new UnsupportedOperationException("No UnitOfWork should be created in this test");
        }

        @Override
        public Optional<HandleAwareUnitOfWork> getCurrentUnitOfWork() {
            return Optional.empty();
        }
    }
}
