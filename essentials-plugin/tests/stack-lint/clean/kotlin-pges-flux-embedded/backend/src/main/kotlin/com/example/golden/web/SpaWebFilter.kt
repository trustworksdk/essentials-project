package com.example.golden.web

import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono

@Component
class SpaWebFilter : WebFilter {
    private val apiPrefixes = listOf("/api/", "/v3/", "/swagger-ui", "/actuator")
    private val extensionPattern = Regex("\\.[a-zA-Z0-9]+$")

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        val path = exchange.request.uri.path
        if (apiPrefixes.any { path.startsWith(it) }) return chain.filter(exchange)
        if (extensionPattern.containsMatchIn(path.substringAfterLast("/"))) return chain.filter(exchange)
        return chain.filter(exchange.mutate().request(exchange.request.mutate().path("/index.html").build()).build())
    }
}
