package com.example.multi.catalog.types;

import dk.trustworks.essentials.types.CharSequenceType;
import dk.trustworks.essentials.types.Identifier;

public class ProductId extends CharSequenceType<ProductId> implements Identifier {
    public ProductId(CharSequence value) { super(value); }
    public static ProductId of(CharSequence value) { return new ProductId(value); }
}
