package com.acme.inbox.views.templated

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

private const val BASE = "/api/inbox"

/** UNPARSED: a string template is not a literal the reader will evaluate. */
@RestController
class TemplatedAPI {
    @GetMapping("$BASE/messages")
    fun messages(): List<String> = emptyList()
}
