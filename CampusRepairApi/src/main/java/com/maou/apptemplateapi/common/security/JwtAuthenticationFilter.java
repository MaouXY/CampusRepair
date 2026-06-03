package com.maou.apptemplateapi.common.security;

import com.maou.apptemplateapi.common.exception.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenService jwtTokenService;
    private final AuthenticationEntryPoint authenticationEntryPoint;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String authorization = request.getHeader("Authorization");
        if (!StringUtils.hasText(authorization) || !authorization.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = authorization.substring(7);
        if (!StringUtils.hasText(token)) {
            request.setAttribute(RestAuthenticationEntryPoint.REQUEST_ERROR_CODE_ATTR, ErrorCode.AUTH_TOKEN_INVALID_OR_EXPIRED);
            authenticationEntryPoint.commence(request, response, new AuthenticationCredentialsNotFoundException("Missing bearer token"));
            return;
        }

        try {
            CurrentUser currentUser = jwtTokenService.parseToken(token);
            UsernamePasswordAuthenticationToken authenticationToken = new UsernamePasswordAuthenticationToken(
                    currentUser,
                    null,
                    List.of(new SimpleGrantedAuthority("ROLE_" + currentUser.getRoleCode()))
            );
            SecurityContextHolder.getContext().setAuthentication(authenticationToken);
            filterChain.doFilter(request, response);
        } catch (JwtAuthenticationException exception) {
            SecurityContextHolder.clearContext();
            request.setAttribute(RestAuthenticationEntryPoint.REQUEST_ERROR_CODE_ATTR, ErrorCode.AUTH_TOKEN_INVALID_OR_EXPIRED);
            log.warn(
                    "jwt validation failed, scenario=jwt-parse, uri={}, tokenPresent={}, reason={}",
                    request.getRequestURI(),
                    true,
                    exception.getMessage()
            );
            authenticationEntryPoint.commence(request, response, exception);
        }
    }
}

