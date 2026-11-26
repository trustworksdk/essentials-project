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

import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

/**
 * A page handler cannot end or change the engine's transaction - {@code commit},
 * {@code rollback()}, {@code setAutoCommit}, {@code close} and {@code abort} throw - while savepoints and everything
 * else reach the real connection.
 */
class GuardedConnectionTest {
    @Test
    void ending_or_changing_the_transaction_is_refused() {
        var calls   = new ArrayList<String>();
        var guarded = GuardedConnection.of(recording(calls));
        assertThatThrownBy(guarded::commit).isInstanceOf(IllegalStateException.class).hasMessageContaining("commit");
        assertThatThrownBy(guarded::rollback).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> guarded.setAutoCommit(true)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(guarded::close).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> guarded.abort(Runnable::run)).isInstanceOf(IllegalStateException.class);
        assertThat(calls).as("none reached the connection").isEmpty();
    }

    @Test
    void savepoints_and_statements_pass_through() throws Exception {
        var calls   = new ArrayList<String>();
        var guarded = GuardedConnection.of(recording(calls));
        guarded.setSavepoint("s");
        guarded.rollback(null);
        guarded.releaseSavepoint(null);
        guarded.prepareStatement("SELECT 1");
        guarded.getAutoCommit();
        assertThat(calls).containsExactly("setSavepoint", "rollback", "releaseSavepoint", "prepareStatement", "getAutoCommit");
        assertThat(guarded.unwrap(Connection.class)).as("unwrapping to Connection keeps the guard").isSameAs(guarded);
    }

    @Test
    void the_real_connection_s_exception_is_thrown_unwrapped() {
        var failing = (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
            throw new SQLException("boom", "42P01");
        });
        assertThatThrownBy(() -> GuardedConnection.of(failing).prepareStatement("SELECT 1")).isInstanceOf(SQLException.class).hasMessage("boom");
    }

    private Connection recording(List<String> calls) {
        return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
            calls.add(method.getName());
            return method.getReturnType() == boolean.class ? Boolean.FALSE : null;
        });
    }
}
