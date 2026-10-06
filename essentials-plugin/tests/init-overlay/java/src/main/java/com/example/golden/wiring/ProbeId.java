package com.example.golden.wiring;

import dk.trustworks.essentials.types.CharSequenceType;

/** A Java semantic id: a real class in every JVM signature, so it binds only through types-spring-web (S4). */
public final class ProbeId extends CharSequenceType<ProbeId> {
    public ProbeId(CharSequence value) {
        super(value);
    }

    public static ProbeId of(CharSequence value) {
        return new ProbeId(value);
    }
}
