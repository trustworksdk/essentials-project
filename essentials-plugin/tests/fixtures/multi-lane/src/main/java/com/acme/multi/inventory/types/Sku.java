package com.acme.multi.inventory.types;

import dk.trustworks.essentials.types.CharSequenceType;
import dk.trustworks.essentials.types.Identifier;

public class Sku extends CharSequenceType<Sku> implements Identifier {
    public Sku(CharSequence value) { super(value); }
    public static Sku of(CharSequence value) { return new Sku(value); }
}
