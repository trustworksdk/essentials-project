package com.example.orders.events;

public sealed interface OrderEvent permits OrderPlaced, OrderCancelled {
}
