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

import org.jdbi.v3.core.*;
import org.jdbi.v3.core.transaction.*;

import java.sql.SQLException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Wraps a {@link Jdbi}'s {@link TransactionHandler} so that a handle whose page transaction is open refuses
 * {@code commit()} and {@code rollback()} (design phase 8 §2.4): a handler that ended the page transaction itself would
 * land output without the progress update (I12). Every other handle, and the page's handle once the page source ends it,
 * behaves exactly as before.
 */
final class PageGuardTransactionHandler implements TransactionHandler {
    private final TransactionHandler delegate;
    private final Set<Handle>        guarded;

    private PageGuardTransactionHandler(TransactionHandler delegate, Set<Handle> guarded) {
        this.delegate = requireNonNull(delegate, "No delegate provided");
        this.guarded = guarded;
    }

    /** @return the guard installed on {@code jdbi}, installing it if it is not there yet */
    static synchronized PageGuardTransactionHandler installOn(Jdbi jdbi) {
        requireNonNull(jdbi, "No jdbi provided");
        if (jdbi.getTransactionHandler() instanceof PageGuardTransactionHandler installed) {
            return installed;
        }
        var guard = new PageGuardTransactionHandler(jdbi.getTransactionHandler(), ConcurrentHashMap.newKeySet());
        jdbi.setTransactionHandler(guard);
        return guard;
    }

    void guard(Handle handle) {
        guarded.add(handle);
    }

    void release(Handle handle) {
        guarded.remove(handle);
    }

    private void refuseIfGuarded(Handle handle, String what) {
        if (guarded.contains(handle)) {
            throw new IllegalStateException("A page handler must not " + what + " the page transaction: the engine commits it after the progress update");
        }
    }

    @Override
    public TransactionHandler specialize(Handle handle) throws SQLException {
        return new PageGuardTransactionHandler(delegate.specialize(handle), guarded);
    }

    @Override
    public void begin(Handle handle) {
        delegate.begin(handle);
    }

    @Override
    public void commit(Handle handle) {
        refuseIfGuarded(handle, "commit");
        delegate.commit(handle);
    }

    @Override
    public void rollback(Handle handle) {
        refuseIfGuarded(handle, "roll back");
        delegate.rollback(handle);
    }

    @Override
    public boolean isInTransaction(Handle handle) {
        return delegate.isInTransaction(handle);
    }

    @Override
    public void savepoint(Handle handle, String name) {
        delegate.savepoint(handle, name);
    }

    @Override
    public void rollbackToSavepoint(Handle handle, String name) {
        delegate.rollbackToSavepoint(handle, name);
    }

    @Override
    public void releaseSavepoint(Handle handle, String name) {
        delegate.releaseSavepoint(handle, name);
    }

    @Override
    public <R, X extends Exception> R inTransaction(Handle handle, HandleCallback<R, X> callback) throws X {
        return delegate.inTransaction(handle, callback);
    }

    @Override
    public <R, X extends Exception> R inTransaction(Handle handle, TransactionIsolationLevel level, HandleCallback<R, X> callback) throws X {
        return delegate.inTransaction(handle, level, callback);
    }
}
