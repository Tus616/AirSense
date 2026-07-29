package com.airsense.api.config;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class CorsConfigTest {

    @Test
    void defaultOriginsAllowVercelAndLocalDevelopmentOnly() throws Exception {
        CorsConfig config = new CorsConfig();
        ReflectionTestUtils.setField(config, "allowedOrigins", "https://air-sense-lyart.vercel.app,http://localhost:5173");
        Method method = CorsConfig.class.getDeclaredMethod("corsConfigurationSource");
        method.setAccessible(true);
        UrlBasedCorsConfigurationSource source = (UrlBasedCorsConfigurationSource) method.invoke(config);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/intelligence/decision");
        request.addHeader("Origin", "https://air-sense-lyart.vercel.app");
        CorsConfiguration cors = source.getCorsConfiguration((HttpServletRequest) request);

        assertThat(cors).isNotNull();
        assertThat(cors.getAllowedOrigins()).containsExactly("https://air-sense-lyart.vercel.app", "http://localhost:5173");
        assertThat(cors.getAllowCredentials()).isTrue();
    }
}
