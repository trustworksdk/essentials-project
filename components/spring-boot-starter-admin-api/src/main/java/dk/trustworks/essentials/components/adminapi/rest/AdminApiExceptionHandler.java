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

package dk.trustworks.essentials.components.adminapi.rest;

import dk.trustworks.essentials.components.adminapi.rest.dto.ApiError;
import dk.trustworks.essentials.shared.security.EssentialsSecurityException;
import org.slf4j.*;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Maps exceptions raised by the admin API controllers onto the statuses the contract declares, with the contract's
 * {@code Error} body.
 * <p>
 * Scoped to {@link AdminApiPaths} package by {@code basePackageClasses}, so it never intercepts the host
 * application's own controllers.
 * <p>
 * {@code 5xx} responses deliberately carry no exception detail — the cause is logged with its stack trace instead, so
 * an internal failure cannot leak schema names, SQL, or file paths to an HTTP caller.
 */
@RestControllerAdvice(basePackageClasses = AdminApiPaths.class)
public class AdminApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(AdminApiExceptionHandler.class);

    /** No authenticated caller: the host has not authenticated the request, or supplied no principal. */
    @ExceptionHandler(AdminApiUnauthenticatedException.class)
    public ResponseEntity<ApiError> handleUnauthenticated(AdminApiUnauthenticatedException e) {
        log.debug("Admin API request rejected as unauthenticated: {}", e.getMessage());
        return error(HttpStatus.UNAUTHORIZED, e.getMessage());
    }

    /** The caller is known but {@code EssentialsSecurityProvider} denied one of the operation's required roles. */
    @ExceptionHandler(EssentialsSecurityException.class)
    public ResponseEntity<ApiError> handleAuthorizationFailure(EssentialsSecurityException e) {
        log.debug("Admin API request rejected as unauthorized: {}", e.getMessage());
        return error(HttpStatus.FORBIDDEN, e.getMessage());
    }

    /** An {@link java.util.Optional}-returning SPI method had no value for the requested identifier. */
    @ExceptionHandler(AdminApiResourceNotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(AdminApiResourceNotFoundException e) {
        return error(HttpStatus.NOT_FOUND, e.getMessage());
    }

    /**
     * Malformed input. {@link IllegalArgumentException} is included because the Essentials {@code FailFast} guards —
     * which validate identifiers such as a queue name — throw it rather than an NPE.
     */
    @ExceptionHandler({IllegalArgumentException.class,
                       MethodArgumentTypeMismatchException.class,
                       MissingServletRequestParameterException.class,
                       ServletRequestBindingException.class,
                       HttpMessageNotReadableException.class})
    public ResponseEntity<ApiError> handleBadRequest(Exception e) {
        log.debug("Admin API request rejected as malformed: {}", e.getMessage());
        return error(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    /** Anything else. The detail is logged, never returned. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception e) {
        log.error("Admin API request failed unexpectedly", e);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, null);
    }

    private static ResponseEntity<ApiError> error(HttpStatus status, String message) {
        return ResponseEntity.status(status)
                             .contentType(MediaType.APPLICATION_JSON)
                             .body(new ApiError(status.value(), status.getReasonPhrase(), message));
    }
}
