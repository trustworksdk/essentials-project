package com.example.library.lending.use_cases.borrow_book;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class BorrowBookAPI {
    @PostMapping("/api/lending/loans")
    public void borrow(@RequestBody BorrowBook command) {
    }
}
