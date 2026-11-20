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

import dk.trustworks.essentials.components.foundation.transaction.UnitOfWorkStatus;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.*;
import org.jdbi.v3.core.*;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * A page's unit of work that a handler marked rollback-only must fail the page: {@code commit()} of such a unit of
 * work rolls back without throwing, so the engine used to count the page and its resume point as committed.
 */
class UnitOfWorkPageTransactionsTest {
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void a_page_whose_unit_of_work_was_marked_rollback_only_fails_instead_of_committing() throws Exception {
        HandleAwareUnitOfWorkFactory factory    = mock(HandleAwareUnitOfWorkFactory.class);
        var                          unitOfWork = mock(HandleAwareUnitOfWork.class);
        when(factory.getCurrentUnitOfWork()).thenReturn(Optional.empty());
        when(factory.getOrCreateNewUnitOfWork()).thenReturn(unitOfWork);
        when(unitOfWork.handle()).thenReturn(mock(Handle.class));
        when(unitOfWork.status()).thenReturn(UnitOfWorkStatus.MarkedForRollbackOnly);
        var source = new UnitOfWorkPageTransactions(factory, Jdbi.create("jdbc:postgresql://localhost/never-connected"), Set.of(ProcessorMutant.HANDLER_COMMITS_EARLY));

        var page = source.begin();

        assertThatThrownBy(page::commit).isInstanceOf(SQLException.class).hasMessageContaining("rollback-only");
        verify(unitOfWork).rollback();
        verify(unitOfWork, never()).commit();
    }
}
