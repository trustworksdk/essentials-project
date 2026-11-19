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
package dk.trustworks.essentials.components.eventlog.processor;

import dk.trustworks.essentials.components.eventlog.*;
import dk.trustworks.essentials.components.eventlog.processor.test_data.Added;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.EssentialsJSONEventSerializers;
import dk.trustworks.essentials.components.foundation.messaging.MessageHandler;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

import java.sql.SQLException;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * {@link EventLogViewProcessor#rebuild()} without a database: the page source it needs, and {@link
 * EventLogViewProcessor#onRebuild(ProjectionContext)} running in the rebuild's transaction, whose failure rolls it back.
 * The clear and reset committing together, and the replay, are {@link EventLogViewProcessorIT}'s.
 */
class EventLogViewProcessorTest {
    private final EventLog eventLog = EventLog.builder().setDataSource(new PGSimpleDataSource()).build();

    class View extends EventLogViewProcessor {
        final List<PageTransaction> rebuiltIn = new ArrayList<>();

        View(PageTransactionSource source) {
            super("view", List.of("Orders"), new EventLogProcessorDependencies(eventLog, EssentialsJSONEventSerializers.create(), source));
        }

        @Override
        protected void onRebuild(ProjectionContext context) throws Exception {
            rebuiltIn.add(context.transaction());
            throw new SQLException("clear failed");
        }

        @MessageHandler
        void on(Added added) {
        }
    }

    @Test
    void a_rebuild_needs_a_page_source_that_binds_what_handlers_write_through() {
        var view = new View(PageTransactionSource.engine());

        assertThatThrownBy(view::rebuild).isInstanceOf(IllegalStateException.class).hasMessageContaining("PageTransactionSource.engine()");
        assertThat(view.rebuiltIn).isEmpty();
    }

    @Test
    void onRebuild_runs_in_the_rebuild_transaction_and_its_failure_rolls_it_back() throws Exception {
        var source = mock(PageTransactionSource.class);
        var open   = mock(PageTransactionSource.OpenPage.class);
        when(source.begin()).thenReturn(open);
        var view = new View(source);

        assertThatThrownBy(view::rebuild).isInstanceOf(IllegalStateException.class).hasRootCauseMessage("clear failed");

        assertThat(view.rebuiltIn).containsExactly(open);
        verify(source).begin();
        verify(open).rollback();
        verify(open, never()).commit();
        assertThat(view.isStarted()).isFalse();
        assertThat(view.projections()).hasSize(1).noneMatch(ViewProjection::isRunning);
    }
}
