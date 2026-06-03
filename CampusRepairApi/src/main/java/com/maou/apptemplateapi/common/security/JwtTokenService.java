package com.maou.apptemplateapi.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

@Component
public class JwtTokenService {

    private static final String CLAIM_PRINCIPAL_ID = "principalId";
    private static final String CLAIM_LOGIN_TYPE = "loginType";
    private static final String CLAIM_ROLE_CODE = "roleCode";
    private static final String CLAIM_ORGANIZATION_ID = "organizationId";

    private final JwtProperties jwtProperties;
    private final SecretKey secretKey;

    public JwtTokenService(JwtProperties jwtProperties) {
        this.jwtProperties = jwtProperties;
        this.secretKey = Keys.hmacShaKeyFor(jwtProperties.getSecret().getBytes(StandardCharsets.UTF_8));
    }

    public String generateToken(CurrentUser currentUser) {
        Instant now = Instant.now();
        Instant expiration = now.plusSeconds(jwtProperties.getExpirationSeconds());
        return Jwts.builder()
                .subject(currentUser.getSubject())
                .claim(CLAIM_PRINCIPAL_ID, currentUser.getPrincipalId())
                .claim(CLAIM_LOGIN_TYPE, currentUser.getLoginType().name())
                .claim(CLAIM_ROLE_CODE, currentUser.getRoleCode())
                .claim(CLAIM_ORGANIZATION_ID, currentUser.getOrganizationId())
                .issuer(jwtProperties.getIssuer())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiration))
                .signWith(secretKey)
                .compact();
    }

    public CurrentUser parseToken(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(secretKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            return CurrentUser.builder()
                    .principalId(claims.get(CLAIM_PRINCIPAL_ID, Long.class))
                    .subject(claims.getSubject())
                    .loginType(LoginType.valueOf(claims.get(CLAIM_LOGIN_TYPE, String.class)))
                    .roleCode(claims.get(CLAIM_ROLE_CODE, String.class))
                    .organizationId(claims.get(CLAIM_ORGANIZATION_ID, Long.class))
                    .build();
        } catch (JwtException | IllegalArgumentException exception) {
            throw new JwtAuthenticationException("Invalid JWT token", exception);
        }
    }

    public long getExpirationSeconds() {
        return jwtProperties.getExpirationSeconds();
    }
}

