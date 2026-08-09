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

package dk.trustworks.essentials.spring.examples.postgresql.cqrs.shipping.views.order_status;

import dk.trustworks.essentials.components.document_db.JavaVersionedEntity;
import dk.trustworks.essentials.components.document_db.Version;
import dk.trustworks.essentials.components.document_db.annotations.DocumentEntity;
import dk.trustworks.essentials.components.document_db.annotations.Id;
import dk.trustworks.essentials.components.document_db.annotations.Indexed;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.shipping.types.ShippingDestinationAddress;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * The read model of the {@code shipping.order_status} view slice, owned by this slice alone.
 * <p>
 * {@code status} is a projection concept, not a domain one: the {@code ShippingOrder} aggregate holds a
 * {@code boolean shipped} because that is all its invariant needs, while a caller asking "where is my order"
 * wants a name for the state. That difference is the whole reason this is a projection rather than a getter
 * on the aggregate.
 * <p>
 * As with every DocumentDB entity: {@code version} starts at {@link Version#NOT_SAVED_YET_VALUE} (-1), and
 * the {@code version}/{@code lastUpdated} field names are hardcoded in the reflection layer — do not rename
 * them, and keep them mutable.
 */
@DocumentEntity(tableName = "shipping_order_status")
public class OrderStatusView extends JavaVersionedEntity<String, OrderStatusView> {

    public static final String REGISTERED = "REGISTERED";
    public static final String SHIPPED    = "SHIPPED";

    /**
     * Public, and it has to be — see {@code AccountBalanceView#accountId}. {@code EntityConfiguration}
     * resolves {@code @Id} through Kotlin reflection over {@code memberProperties}, which for a Java class
     * reads the field directly and throws {@code IllegalAccessException} if it is private.
     */
    @Id
    public String orderId;

    private ShippingDestinationAddress destinationAddress;

    @Indexed
    private String status;

    private long           version     = Version.NOT_SAVED_YET_VALUE;
    private OffsetDateTime lastUpdated = OffsetDateTime.now(ZoneOffset.UTC);

    public OrderStatusView() {
    }

    public OrderStatusView(String orderId,
                           ShippingDestinationAddress destinationAddress,
                           String status) {
        this.orderId            = orderId;
        this.destinationAddress = destinationAddress;
        this.status             = status;
    }

    @Override
    public long getVersionValue() {
        return version;
    }

    @Override
    public void setVersionValue(long version) {
        this.version = version;
    }

    @Override
    public OffsetDateTime getLastUpdated() {
        return lastUpdated;
    }

    @Override
    public void setLastUpdated(OffsetDateTime lastUpdated) {
        this.lastUpdated = lastUpdated;
    }

    public String getOrderId() {
        return orderId;
    }

    public ShippingDestinationAddress getDestinationAddress() {
        return destinationAddress;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    @Override
    public String toString() {
        return "OrderStatusView(orderId=" + orderId + ", status=" + status + ", destinationAddress=" + destinationAddress + ")";
    }
}
