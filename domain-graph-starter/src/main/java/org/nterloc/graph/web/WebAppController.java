package org.nterloc.graph.web;

import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.server.ServerWebExchange;

import static org.nterloc.graph.web.WebAppController.PREFIX;

@Controller
@RequestMapping(PREFIX)
public class WebAppController {

    static final String PREFIX = "/app";

    @GetMapping("/**")
    public String testGet(ServerWebExchange exchange) {
        ServerHttpRequest request = exchange.getRequest();
        return request.getPath().toString().substring(PREFIX.length() + 1);
    }

}
