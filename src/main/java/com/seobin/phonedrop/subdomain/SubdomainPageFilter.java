package com.seobin.phonedrop.subdomain;

import java.io.IOException;
import java.util.regex.Pattern;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * <name>.seobin.page/ 로 들어온 요청을 static/<name>.html 로 연결한다.
 * 해당 html 파일이 없으면 기존 동작(메인 화면)을 그대로 따른다.
 */
@Component
public class SubdomainPageFilter extends OncePerRequestFilter {

    private static final String ROOT_DOMAIN = ".seobin.page";
    private static final Pattern NAME = Pattern.compile("[a-z0-9-]+");

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String host = request.getServerName().toLowerCase();

        if ("/".equals(request.getRequestURI()) && host.endsWith(ROOT_DOMAIN)) {
            String name = host.substring(0, host.length() - ROOT_DOMAIN.length());

            if (!"www".equals(name)
                    && NAME.matcher(name).matches()
                    && new ClassPathResource("static/" + name + ".html").exists()) {
                request.getRequestDispatcher("/" + name + ".html").forward(request, response);
                return;
            }
        }

        filterChain.doFilter(request, response);
    }
}
