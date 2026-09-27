package com.sih.materialmaster.config;

import com.sih.materialmaster.security.JwtAuthenticationFilter;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * WP7 / D6: Authoritative security configuration enforcing the frozen role-route matrix.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    @Value("${app.cors.allowed-origins:http://localhost:5173,http://127.0.0.1:5173,http://localhost:3000,http://127.0.0.1:3000}")
    private String allowedOrigins;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration authConfig) throws Exception {
        return authConfig.getAuthenticationManager();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, authException) -> {
                            response.setContentType("application/json");
                            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                            response.getWriter().write("{\"error\":\"Unauthorized\",\"message\":\"" + authException.getMessage() + "\"}");
                        })
                        .accessDeniedHandler((request, response, accessDeniedException) -> {
                            response.setContentType("application/json");
                            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                            response.getWriter().write("{\"error\":\"Forbidden\",\"message\":\"" + accessDeniedException.getMessage() + "\"}");
                        })
                )
                .authorizeHttpRequests(auth -> auth
                        // 1. Public endpoints
                        .requestMatchers("/api/auth/login", "/error", "/actuator/health").permitAll()
                        // 2. Demo accounts and demo tamper helpers
                        .requestMatchers(HttpMethod.GET, "/api/auth/demo-accounts").permitAll()
                        // 3. Authenticated session endpoints
                        .requestMatchers("/api/auth/me", "/api/auth/logout").authenticated()
                        // 4. Admin management
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/demo/**").hasRole("ADMIN")
                        // 5. Procurement assumptions
                        .requestMatchers(HttpMethod.PUT, "/api/analytics/assumptions/**").hasRole("ADMIN")
                        // 6. Analytics and KPIs
                        .requestMatchers(HttpMethod.GET, "/api/analytics/**", "/api/dashboard/**").hasAnyRole("SENIOR_REVIEWER", "ADMIN")
                        // 7. Audit queries
                        .requestMatchers(HttpMethod.GET, "/api/mappings/audit/**", "/api/mappings/audit").hasAnyRole("SENIOR_REVIEWER", "ADMIN")
                        // 8. Supersede mapping decisions
                        .requestMatchers(HttpMethod.POST, "/api/mappings/*/supersede").hasAnyRole("SENIOR_REVIEWER", "ADMIN")
                        // 9. Bulk approve high-confidence mappings
                        .requestMatchers(HttpMethod.POST, "/api/mappings/bulk-approve").hasAnyRole("REVIEWER", "SENIOR_REVIEWER")
                        // 10. Individual mapping review decisions
                        .requestMatchers(HttpMethod.POST, "/api/mappings/*/approve", "/api/mappings/*/reject", "/api/mappings/*/edit").hasAnyRole("REVIEWER", "SENIOR_REVIEWER")
                        // 11. Read mappings
                        .requestMatchers(HttpMethod.GET, "/api/mappings/**").hasAnyRole("REVIEWER", "SENIOR_REVIEWER", "ADMIN")
                        // 12. Publishable groups queue
                        .requestMatchers(HttpMethod.GET, "/api/groups/publishable").hasAnyRole("SENIOR_REVIEWER", "ADMIN")
                        // 13. Mint official National Code
                        .requestMatchers(HttpMethod.POST, "/api/groups/*/mint").hasRole("SENIOR_REVIEWER")
                        // 14. National Code lookup & validation (all authenticated users)
                        .requestMatchers(HttpMethod.GET, "/api/codes/**").authenticated()
                        // 15. Pairwise ML Sandbox comparison (all authenticated users)
                        .requestMatchers(HttpMethod.POST, "/api/harmonization/compare").authenticated()
                        // 16. Batch harmonization trigger (Admin only)
                        .requestMatchers(HttpMethod.POST, "/api/harmonization/harmonize-all").hasRole("ADMIN")
                        // 17. Single material harmonization trigger
                        .requestMatchers(HttpMethod.POST, "/api/harmonization/**").hasAnyRole("OPERATOR", "ADMIN")
                        // 18. Material ingest and job tracking
                        .requestMatchers("/api/materials/**", "/api/jobs/**").hasAnyRole("OPERATOR", "ADMIN")
                        // 19. Export catalog
                        .requestMatchers(HttpMethod.GET, "/api/export/catalog").hasAnyRole("SENIOR_REVIEWER", "ADMIN")
                        // 20. Other exports
                        .requestMatchers(HttpMethod.GET, "/api/export/**").hasAnyRole("OPERATOR", "SENIOR_REVIEWER", "ADMIN")
                        // 21. Deny all other requests
                        .anyRequest().denyAll()
                )
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        List<String> patterns = new ArrayList<>();
        if (allowedOrigins != null) {
            for (String origin : allowedOrigins.split(",")) {
                String trimmed = origin.trim();
                if (!trimmed.isEmpty() && !patterns.contains(trimmed)) {
                    patterns.add(trimmed);
                }
            }
        }
        config.setAllowedOriginPatterns(patterns);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
