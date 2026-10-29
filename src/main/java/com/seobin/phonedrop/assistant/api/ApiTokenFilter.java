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
            response.getWriter().write("{\"error\":\"" + reason(header) + "\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    /** 무엇이 틀렸는지 알려준다(값 자체는 드러내지 않는다). */
    private String reason(String header) {
        if (header == null || header.isBlank()) {
            return "API 토큰이 없습니다. 맨 위 'API 토큰' 칸에 서버 토큰을 저장하세요";
        }
        String value = header.trim().startsWith("Bearer ") ? header.trim().substring(7).trim() : header.trim();
        if (value.isEmpty()) {
            return "API 토큰이 비어 있습니다. 맨 위 'API 토큰' 칸에 서버 토큰을 저장하세요";
        }
        if (value.startsWith("sk-ant-")) {
            return "맨 위 칸에 Anthropic 키를 넣었습니다. 맨 위에는 서버 토큰(/root/data/assistant/api_token)을, Anthropic 키는 'AI 연결' 칸에 넣으세요";
        }
        int expectedLength = expected.length - "Bearer ".length();
        return "API 토큰이 맞지 않습니다(받은 길이 " + value.length() + "자, 서버 토큰은 " + expectedLength + "자)";
    }
}
