package com.example.post.parcels.events;

public sealed interface ParcelPacked extends ParcelEvent permits PackedPartly, PackedFully {
}
