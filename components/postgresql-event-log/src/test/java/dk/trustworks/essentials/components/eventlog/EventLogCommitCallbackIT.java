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
package dk.trustworks.essentials.components.eventlog;

import dk.trustworks.essentials.components.foundation.transaction.jdbi.JdbiUnitOfWorkFactory;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Tier 2 through foundation's unit of work (design phase 6 §2.2): an append in a JDBI unit of work that registers its
 * aggregate type with {@link EventLogCommitCallback} wakes the type's reader after the commit, so with a 30 s backstop
 * the event arrives at once; a rollback wakes nothing and delivers nothing.
 */
class EventLogCommitCallbackIT extends AbstractSubscriptionIT {
    @Test
    void a_committed_unit_of_work_wakes_the_reader_and_a_rolled_back_one_delivers_nothing() throws Exception {
        restartEventLog(dataSource, FAST.withBackstopInterval(Duration.ofSeconds(30)));
        var recorder = new Recorder();
        subscribe("sub", recorder, TestSeams.NONE);
        awaitTrue(() -> reads.attaches.get() >= 1);
        var unitOfWorkFactory = new JdbiUnitOfWorkFactory(Jdbi.create(dataSource));
        var callback          = eventLog.commitCallback();

        assertThatThrownBy(() -> unitOfWorkFactory.usingUnitOfWork(unitOfWork -> {
            unitOfWork.registerLifecycleCallbackForResource(TYPE, callback);
            eventLog.append(unitOfWork.handle().getConnection(), AppendRequest.withoutExpectedOrder(TYPE, "rolled-back", events("E", 1)));
            throw new IllegalStateException("roll back");
        })).isInstanceOf(RuntimeException.class);

        var committed = unitOfWorkFactory.withUnitOfWork(unitOfWork -> {
            unitOfWork.registerLifecycleCallbackForResource(TYPE, callback);
            return eventLog.append(unitOfWork.handle().getConnection(), List.of(AppendRequest.withoutExpectedOrder(TYPE, "committed", events("E", 1))));
        }).getFirst().events().getFirst().globalOrder();
        var start = System.nanoTime();
        awaitDelivered(recorder, committed);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).as("woken, not the 30 s backstop").isLessThan(Duration.ofSeconds(5));
        assertThat(recorder.delivered).extracting(StoredEvent::aggregateId).containsExactly("committed");
    }
}
