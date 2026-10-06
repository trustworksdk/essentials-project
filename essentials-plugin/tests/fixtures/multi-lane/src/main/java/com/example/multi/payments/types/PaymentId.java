package com.example.multi.payments.types;

import dk.trustworks.essentials.types.CharSequenceType;
import dk.trustworks.essentials.types.Identifier;

public class PaymentId extends CharSequenceType<PaymentId> implements Identifier {
    public PaymentId(CharSequence value) { super(value); }
    public static PaymentId of(CharSequence value) { return new PaymentId(value); }
}
