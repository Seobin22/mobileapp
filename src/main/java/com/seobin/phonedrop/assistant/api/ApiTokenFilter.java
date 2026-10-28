package com.seobin.phonedrop.assistant.api;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.seobin.phonedrop.assistant.AssistantConfig.ApiToken;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** /api/assistant/v1/** 는 Authorization: Bearer <API 토큰> 이 있어야 한다. */
@Component
public class ApiTokenFilter extends OncePerRequestFilter {

    public static final String PREFIX = "/api/assistant/v1/";

    private final byte[] expected;

    public ApiTokenFilter(ApiToken token) {
        this.expected = ("Bearer " + token.value()).getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header == null || !MessageDigest.isEqual(expected, header.trim().getBytes(StandardCharsets.UTF_8))) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"error\":\"API 토큰이 맞지 않습니다\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
