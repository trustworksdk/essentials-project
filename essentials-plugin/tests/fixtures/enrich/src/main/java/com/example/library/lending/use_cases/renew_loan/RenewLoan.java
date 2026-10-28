package com.example.library.lending.use_cases.renew_loan;

import com.example.library.lending.routing.CopyCommand;

/** Extends a running loan by a further period. */
public record RenewLoan(String copyId, int extraDays) implements CopyCommand {
}
