package com.example.depot.external_systems.carrier.incoming;

public record ParcelCollected(String parcelId) implements CarrierEvent {
}
