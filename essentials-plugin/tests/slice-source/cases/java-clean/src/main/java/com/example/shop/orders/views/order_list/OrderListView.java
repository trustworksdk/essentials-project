package com.example.shop.orders.views.order_list;

import dk.trustworks.essentials.components.document_db.JavaVersionedEntity;
import dk.trustworks.essentials.components.document_db.annotations.DocumentEntity;
import dk.trustworks.essentials.components.document_db.annotations.Id;
import dk.trustworks.essentials.components.document_db.annotations.Indexed;

import java.time.OffsetDateTime;

/** RULE (readModels): columns are the non-static fields; @Id / @Indexed become notes. */
@DocumentEntity(tableName = "orders_order_list")
public class OrderListView extends JavaVersionedEntity<String, OrderListView> {
    public static final int MAX_PAGE = 100;

    @Id
    public String orderId;
    @Indexed
    private String status;
    private long version;
    private OffsetDateTime lastUpdated;

    public OrderListView() {
    }

    public OrderListView(String orderId, String status) {
        this.orderId = orderId;
        this.status = status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
