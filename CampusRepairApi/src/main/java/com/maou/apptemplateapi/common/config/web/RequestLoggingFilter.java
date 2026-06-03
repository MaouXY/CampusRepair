package com.maou.apptemplateapi.common.config.web;

import com.maou.apptemplateapi.common.security.CurrentUser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;
import java.util.Map;

@Slf4j
@Component
public class RequestLoggingFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        long startTime = System.currentTimeMillis();
        Exception failure = null;
        try {
            filterChain.doFilter(request, response);
        } catch (ServletException | IOException | RuntimeException exception) {
            failure = exception;
            throw exception;
        } finally {
            writeRequestLog(request, response, startTime, failure);
        }
    }

    private void writeRequestLog(HttpServletRequest request,
                                 HttpServletResponse response,
                                 long startTime,
                                 Exception failure) {
        long costMs = System.currentTimeMillis() - startTime;
        CurrentUser currentUser = resolveCurrentUser();
        String endpointName = resolveEndpointName(request);
        String clientIp = resolveClientIp(request);

        if (failure != null) {
            log.error(
                    "http request failed, scenario=http-request, method={}, uri={}, endpointName={}, pathVariables={}, status={}, costMs={}, userId={}, subject={}, roleCode={}, organizationId={}, clientIp={}, userAgent={}",
                    request.getMethod(),
                    request.getRequestURI(),
                    endpointName,
                    resolvePathVariables(request),
                    response.getStatus(),
                    costMs,
                    currentUser == null ? null : currentUser.getId(),
                    currentUser == null ? null : currentUser.getSubject(),
                    currentUser == null ? null : currentUser.getRoleCode(),
                    currentUser == null ? null : currentUser.getOrganizationId(),
                    clientIp,
                    request.getHeader("User-Agent"),
                    failure
            );
            return;
        }

        log.info(
                "http request completed, scenario=http-request, method={}, uri={}, endpointName={}, pathVariables={}, status={}, costMs={}, userId={}, subject={}, roleCode={}, organizationId={}, clientIp={}, userAgent={}",
                request.getMethod(),
                request.getRequestURI(),
                endpointName,
                resolvePathVariables(request),
                response.getStatus(),
                costMs,
                currentUser == null ? null : currentUser.getId(),
                currentUser == null ? null : currentUser.getSubject(),
                currentUser == null ? null : currentUser.getRoleCode(),
                currentUser == null ? null : currentUser.getOrganizationId(),
                clientIp,
                request.getHeader("User-Agent")
        );
    }

    private CurrentUser resolveCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof CurrentUser currentUser) {
            return currentUser;
        }
        return null;
    }

    private String resolveEndpointName(HttpServletRequest request) {
        Object bestMatchingPattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (bestMatchingPattern instanceof String pattern && !pattern.isBlank()) {
            return pattern;
        }
        return "unmatched";
    }

    private Object resolvePathVariables(HttpServletRequest request) {
        Object variables = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (variables instanceof Map<?, ?> pathVariables && !pathVariables.isEmpty()) {
            return pathVariables;
        }
        return "{}";
    }

    private String resolveClientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",")[0].trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        return request.getRemoteAddr();
    }
}

