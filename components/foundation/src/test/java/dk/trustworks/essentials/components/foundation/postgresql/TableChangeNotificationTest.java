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

package dk.trustworks.essentials.components.foundation.postgresql;

import dk.trustworks.essentials.components.foundation.postgresql.ListenNotify.SqlOperation;
import org.junit.jupiter.api.Test;

import java.util.HashSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LN-4: {@link TableChangeNotification#equals(Object)} dereferenced {@code tableName}, so a notification deserialized
 * from a payload without {@code table_name} threw a {@link NullPointerException} the moment it was compared or put in
 * a {@link java.util.Set}. It also used {@code instanceof}, so two different notification types with the same table
 * name and operation were equal.
 */
class TableChangeNotificationTest {

    @Test
    void equals_and_hashCode_are_null_safe() {
        var withoutTableName      = new OrderNotification(null, null);
        var otherWithoutTableName = new OrderNotification(null, null);
        var withTableName         = new OrderNotification("orders", SqlOperation.INSERT);

        assertThat(withoutTableName).isEqualTo(otherWithoutTableName);
        assertThat(withoutTableName.hashCode()).isEqualTo(otherWithoutTableName.hashCode());
        assertThat(withoutTableName).isNotEqualTo(withTableName);
        assertThat(withTableName).isNotEqualTo(withoutTableName);
        assertThat(withoutTableName.equals(null)).isFalse();

        var set = new HashSet<TableChangeNotification>();
        set.add(withoutTableName);
        set.add(otherWithoutTableName);
        set.add(withTableName);
        assertThat(set).hasSize(2);
    }

    @Test
    void notifications_of_different_types_are_never_equal() {
        var order    = new OrderNotification("orders", SqlOperation.INSERT);
        var customer = new CustomerNotification("orders", SqlOperation.INSERT);

        assertThat(order).isNotEqualTo(customer);
        assertThat(customer).isNotEqualTo(order);
        assertThat(order).isEqualTo(new OrderNotification("orders", SqlOperation.INSERT));
    }

    private static class OrderNotification extends TableChangeNotification {
        OrderNotification(String tableName, SqlOperation operation) {
            super(tableName, operation);
        }
    }

    private static class CustomerNotification extends TableChangeNotification {
        CustomerNotification(String tableName, SqlOperation operation) {
            super(tableName, operation);
        }
    }
}
