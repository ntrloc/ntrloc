package org.ntrloc.graph.web;

import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.server.ServerWebExchange;

@Controller
public class WebAppController {

    static final String PREFIX = "/app";

    @GetMapping("/app/**")
    public String testGet(ServerWebExchange exchange) {
        ServerHttpRequest request = exchange.getRequest();
        // String path = request.getPath().toString().substring(1);
        // return path;
        return request.getPath().toString().substring(PREFIX.length() + 1);
    }

}
