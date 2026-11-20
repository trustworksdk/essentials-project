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

import dk.trustworks.essentials.components.eventlog.PageTransactionSource;
import dk.trustworks.essentials.components.foundation.transaction.*;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.*;
import dk.trustworks.essentials.components.foundation.transaction.spring.jdbi.SpringTransactionAwareJdbiUnitOfWorkFactory;
import org.jdbi.v3.core.*;

import java.lang.reflect.*;
import java.sql.*;
import java.util.Set;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Page transactions from the application's JDBI unit-of-work factory: each page runs in a unit of
 * work of {@code unitOfWorkFactory}, current on the delivery thread, so {@code resource(Handle.class)},
 * {@code resource(UnitOfWork.class)}, {@code resource(HandleAwareUnitOfWork.class)} and the factory's
 * {@code getRequiredUnitOfWork()} all see it. The engine runs the handler, then the progress update on the unit of work's
 * connection, then {@link OpenPage#commit()}, which commits the unit of work - its {@code beforeCommit} callbacks run
 * then, after the progress update.
 * <p>
 * The handler cannot end the page transaction: the unit of work handed out refuses {@code commit} and
 * {@code rollback}, and the {@link Jdbi}'s transaction handler is wrapped once ({@link PageGuardTransactionHandler}) so
 * that the page's {@link Handle} refuses them too while the page is open.
 */
public final class UnitOfWorkPageTransactions implements PageTransactionSource {
    private final HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory;
    private final PageGuardTransactionHandler                                  guard;
    private final Set<ProcessorMutant>                                       mutants;

    /**
     * @param unitOfWorkFactory a {@link GenericHandleAwareUnitOfWorkFactory} or {@link SpringTransactionAwareJdbiUnitOfWorkFactory},
     *                          whose {@link Jdbi} is guarded; for any other factory use {@link #UnitOfWorkPageTransactions(HandleAwareUnitOfWorkFactory, Jdbi)}
     */
    public UnitOfWorkPageTransactions(HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory) {
        this(unitOfWorkFactory, jdbiOf(unitOfWorkFactory));
    }

    /**
     * @param jdbi the {@link Jdbi} the factory opens its handles with
     */
    public UnitOfWorkPageTransactions(HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory, Jdbi jdbi) {
        this(unitOfWorkFactory, jdbi, Set.of());
    }

    UnitOfWorkPageTransactions(HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory, Jdbi jdbi, Set<ProcessorMutant> mutants) {
        this.unitOfWorkFactory = requireNonNull(unitOfWorkFactory, "No unitOfWorkFactory provided");
        this.mutants = Set.copyOf(mutants);
        this.guard = this.mutants.contains(ProcessorMutant.HANDLER_COMMITS_EARLY) ? null : PageGuardTransactionHandler.installOn(jdbi);
    }

    private static Jdbi jdbiOf(HandleAwareUnitOfWorkFactory<?> factory) {
        requireNonNull(factory, "No unitOfWorkFactory provided");
        if (factory instanceof GenericHandleAwareUnitOfWorkFactory<?> generic) {
            return generic.getJdbi();
        }
        try {
            if (factory instanceof SpringTransactionAwareJdbiUnitOfWorkFactory spring) {
                return spring.getJdbi();
            }
        } catch (NoClassDefFoundError noSpring) {
            // no Spring on the class path: not a Spring factory
        }
        throw new IllegalArgumentException("Cannot find the Jdbi of " + factory.getClass().getName() + ": use new UnitOfWorkPageTransactions(factory, jdbi)");
    }

    @Override
    public OpenPage begin() throws SQLException {
        if (unitOfWorkFactory.getCurrentUnitOfWork().isPresent()) {
            throw new IllegalStateException("A unit of work is already active on the delivery thread");
        }
        HandleAwareUnitOfWork unitOfWork;
        try {
            unitOfWork = unitOfWorkFactory.getOrCreateNewUnitOfWork();
        } catch (RuntimeException e) {
            throw new SQLTransientConnectionException("Starting a page's unit of work failed", "08001", e);
        }
        var handle = unitOfWork.handle();
        if (guard != null) {
            guard.guard(handle);
        }
        return new Open(unitOfWork, handle);
    }

    private final class Open implements OpenPage {
        private final HandleAwareUnitOfWork unitOfWork;
        private final Handle                handle;
        private final HandleAwareUnitOfWork guardedUnitOfWork;

        Open(HandleAwareUnitOfWork unitOfWork, Handle handle) {
            this.unitOfWork = unitOfWork;
            this.handle = handle;
            this.guardedUnitOfWork = guard == null ? unitOfWork : refusingEnd(unitOfWork);
        }

        @Override
        public Connection connection() {
            return handle.getConnection();
        }

        @Override
        public <T> T resource(Class<T> type) {
            if (type == Handle.class) {
                return type.cast(handle);
            }
            if (type == UnitOfWork.class || type == HandleAwareUnitOfWork.class) {
                return type.cast(guardedUnitOfWork);
            }
            if (type.isInstance(unitOfWork) && guard == null) {
                return type.cast(unitOfWork);
            }
            throw new IllegalArgumentException("A unit-of-work page binds a Handle, UnitOfWork and HandleAwareUnitOfWork, not " + type.getName());
        }

        @Override
        public void commit() throws SQLException {
            release();
            if (unitOfWork.status() == UnitOfWorkStatus.MarkedForRollbackOnly) {
                // commit() of a rollback-only unit of work rolls back without throwing: the engine would count the page, and
                // its resume point, as committed. Fail the page instead, so it is retried or parked like any failure
                var cause = unitOfWork.getCauseOfRollback();
                unitOfWork.rollback();
                throw new SQLException("The page's unit of work was marked rollback-only" + (cause == null ? "" : ": " + cause.getMessage()), cause);
            }
            try {
                unitOfWork.commit();
            } catch (RuntimeException e) {
                throw new SQLException("Committing a page's unit of work failed", e);
            }
        }

        @Override
        public void rollback() {
            release();
            unitOfWork.rollback();
        }

        private void release() {
            if (guard != null) {
                guard.release(handle);
            }
        }
    }

    private static HandleAwareUnitOfWork refusingEnd(HandleAwareUnitOfWork unitOfWork) {
        InvocationHandler handler = (proxy, method, args) -> {
            switch (method.getName()) {
                case "commit", "rollback", "markAsRollbackOnly" ->
                        throw new IllegalStateException("A page handler must not end the page's unit of work: the engine commits it after the progress update");
                case "equals" -> {
                    return proxy == args[0];
                }
                case "hashCode" -> {
                    return System.identityHashCode(proxy);
                }
                default -> {
                    try {
                        return method.invoke(unitOfWork, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                }
            }
        };
        return (HandleAwareUnitOfWork) Proxy.newProxyInstance(UnitOfWorkPageTransactions.class.getClassLoader(), new Class<?>[]{HandleAwareUnitOfWork.class}, handler);
    }
}
