package com.acme.shop.orders.views.order_list

import com.acme.shop.orders.types.OrderId
import dk.trustworks.essentials.components.document_db.DocumentDbRepository

typealias OrderListRepository = DocumentDbRepository<OrderListView, OrderId>
