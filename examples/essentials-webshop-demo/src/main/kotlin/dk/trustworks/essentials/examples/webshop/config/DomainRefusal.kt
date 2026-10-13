/*
 * Copyright 2021-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dk.trustworks.essentials.examples.webshop.config

import dk.trustworks.essentials.components.foundation.transaction.UnitOfWorkException
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * Marks an exception as *the domain said no*, rather than *the application broke*.
 *
 * The two need telling apart at the HTTP boundary, and nothing else can tell them apart: both are runtime
 * exceptions thrown out of a decider. A refusal is a legitimate answer to a legitimate request - the basket was
 * already checked out, the order has no shipping details yet - and the caller can do something about it. A
 * failure is a defect, and the caller cannot.
 *
 * It is an interface rather than a base class because these exceptions extend `RuntimeException` and Kotlin has
 * no multiple inheritance, and it lives here rather than in any one context because all three raise them - a
 * slice may not reach into another slice's package, and this is the module-level surface that is allowed to be
 * shared.
 */
interface DomainRefusal

/**
 * Turns a refusal into **409 Conflict** with the reason, and leaves everything else alone as a 500.
 *
 * Without this the shop page can only report "500", and a demo whose screen says nothing useful when a rule
 * fires teaches the wrong lesson about what deciders are for.
 *
 * The refusal arrives wrapped: a command goes through the command bus inside a UnitOfWork, so whatever the
 * decider threw comes back out as a [UnitOfWorkException] with the real cause underneath - hence the walk down
 * the cause chain rather than a handler per exception type. Anything that is not a [DomainRefusal] is rethrown
 * untouched, because a defect should look like one.
 */
@RestControllerAdvice
class DomainRefusalExceptionHandler {

    companion object {
        private val logger = LoggerFactory.getLogger(DomainRefusalExceptionHandler::class.java)
    }

    data class RefusalResponse(val error: String, val message: String)

    @ExceptionHandler(UnitOfWorkException::class)
    fun onUnitOfWorkException(e: UnitOfWorkException, request: HttpServletRequest): ResponseEntity<RefusalResponse> {
        val refusal = e.causeChain().firstOrNull { it is DomainRefusal } ?: throw e
        // One line, no stack trace: a rule firing is the system working, and it used to be findable in the log
        // only because an unhandled exception printed a 500 and a page of frames. Losing that entirely would
        // make a refused command invisible on the server while the browser was the only place it showed.
        logger.info("Refused {} {} - {}", request.method, request.requestURI, refusal.message)
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(
                RefusalResponse(
                    error = refusal::class.simpleName ?: "DomainRefusal",
                    message = refusal.message ?: "The command was refused"
                )
            )
    }

    private fun Throwable.causeChain(): Sequence<Throwable> =
        generateSequence(this) { previous -> previous.cause?.takeIf { it !== previous } }
}
