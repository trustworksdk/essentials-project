package com.acme.multi.ledger.types;

import dk.trustworks.essentials.components.foundation.types.RandomIdGenerator;
import dk.trustworks.essentials.types.CharSequenceType;
import dk.trustworks.essentials.types.Identifier;

public class AccountId extends CharSequenceType<AccountId> implements Identifier {
    public AccountId(CharSequence value) { super(value); }
    public static AccountId of(CharSequence value) { return new AccountId(value); }
    public static AccountId random() { return new AccountId(RandomIdGenerator.generate()); }
}
