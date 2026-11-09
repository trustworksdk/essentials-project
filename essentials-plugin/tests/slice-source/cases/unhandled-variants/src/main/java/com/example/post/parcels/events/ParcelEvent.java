package com.example.post.parcels.events;

public sealed interface ParcelEvent permits ParcelRegistered, ParcelPacked, ParcelShipped, ParcelDelivered {
}
