package com.example.library.lending.use_cases.register_copy;

import com.example.library.lending.routing.CopyCommand;

public record RegisterCopy(String copyId, String isbn) implements CopyCommand {
}
