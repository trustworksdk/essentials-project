package com.example.billing.types;

import dk.trustworks.essentials.components.foundation.types.RandomIdGenerator;
import dk.trustworks.essentials.types.CharSequenceType;
import dk.trustworks.essentials.types.Identifier;

public class InvoiceId extends CharSequenceType<InvoiceId> implements Identifier {
    public InvoiceId(CharSequence value) { super(value); }
    public static InvoiceId of(String value) { return new InvoiceId(value); }
    public static InvoiceId random() { return new InvoiceId(RandomIdGenerator.generate()); }
}
