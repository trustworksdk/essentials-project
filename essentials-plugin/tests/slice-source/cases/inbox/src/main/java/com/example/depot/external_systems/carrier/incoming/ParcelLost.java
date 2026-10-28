package com.example.depot.external_systems.carrier.incoming;

public record ParcelLost(String parcelId) implements CarrierEvent {
}
