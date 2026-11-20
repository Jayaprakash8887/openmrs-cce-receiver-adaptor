package org.openphc.cce.receiver.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Value("${cce.security.enabled:false}")
    private boolean securityEnabled;

    @Value("${cce.security.token:}")
    private String securityToken;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));

        if (securityEnabled && securityToken != null && !securityToken.isBlank()) {
            http.addFilterBefore(new BearerTokenFilter(securityToken), UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth
                    .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                    // Sygnal endpoint: the signature is checked by onix-openmrs before it
                    // forwards. onix passes on "Authorization: Signature ...", which the bearer
                    // check would refuse. See docs/sygnal-integration.md §5.
                    .requestMatchers("/sygnal/**").permitAll()
                    .requestMatchers("/api/**").authenticated()
                    .anyRequest().permitAll()
                );
        } else {
            http.authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        }

        return http.build();
    }

    private static class BearerTokenFilter extends OncePerRequestFilter {

        private final String expectedToken;

        BearerTokenFilter(String expectedToken) {
            this.expectedToken = expectedToken;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                        FilterChain filterChain) throws ServletException, IOException {
            String path = request.getRequestURI();
            if (path.startsWith("/actuator/health") || path.startsWith("/actuator/info")) {
                filterChain.doFilter(request, response);
                return;
            }

            // Only /api/** carries the bearer token. /sygnal/** is named here as well, so that a
            // later change to the rule below cannot catch the Sygnal endpoint by accident.
            if (path.startsWith("/sygnal/") || !path.startsWith("/api/")) {
                filterChain.doFilter(request, response);
                return;
            }

            String authHeader = request.getHeader("Authorization");
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType("application/json");
                response.getWriter().write("{\"error\":\"Missing or invalid Authorization header\"}");
                return;
            }

            String token = authHeader.substring(7);
            if (!expectedToken.equals(token)) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType("application/json");
                response.getWriter().write("{\"error\":\"Invalid token\"}");
                return;
            }

            // Record the caller as authenticated. Without this, ".authenticated()" on /api/**
            // refuses a request with the correct token with 403.
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(new UsernamePasswordAuthenticationToken(
                    "cce-client", null, List.of(new SimpleGrantedAuthority("ROLE_CCE_CLIENT"))));
            SecurityContextHolder.setContext(context);

            filterChain.doFilter(request, response);
        }
    }
}
