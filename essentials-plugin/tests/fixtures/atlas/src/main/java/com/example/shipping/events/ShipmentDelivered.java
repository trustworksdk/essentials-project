package com.example.shipping.events;

public record ShipmentDelivered(String orderId, String deliveredAt) {
}
