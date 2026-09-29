package {{packagePath}}.orders.views.order_list

/** Read model row for the order-list view. Owned by this view slice. */
data class OrderListView(
    val orderId: String,
    val sku: String,
    val quantity: Int,
    val status: String
)
