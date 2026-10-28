package com.example.library.lending.use_cases.return_book;

import com.example.library.lending.routing.CopyCommand;

/** Takes a borrowed copy back onto the shelf. */
public record ReturnBook(String copyId, String memberId) implements CopyCommand {
}
