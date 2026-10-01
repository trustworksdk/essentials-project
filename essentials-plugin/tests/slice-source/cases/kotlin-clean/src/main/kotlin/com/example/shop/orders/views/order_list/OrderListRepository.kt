package com.example.shop.orders.views.order_list

import com.example.shop.orders.types.OrderId
import dk.trustworks.essentials.components.document_db.DocumentDbRepository

typealias OrderListRepository = DocumentDbRepository<OrderListView, OrderId>
