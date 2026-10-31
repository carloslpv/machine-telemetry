package dev.carloslpv.machinetelemetry.platform.api;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import dev.carloslpv.machinetelemetry.platform.PlatformProperties;

/**
 * The REST API takes an {@code X-API-Key}. Integrations have their own credentials (the TTN webhook token), and the
 * actuator endpoints stay open for health checks and Prometheus inside the compose network.
 */
@Component
class ApiKeyFilter extends OncePerRequestFilter {

	private final byte[] key;

	ApiKeyFilter(PlatformProperties properties) {
		this.key = properties.apiKey().getBytes(StandardCharsets.UTF_8);
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		String path = request.getRequestURI();
		return !path.startsWith("/api/") || path.startsWith("/api/v1/integrations/");
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String given = request.getHeader("X-API-Key");
		if (given == null || !MessageDigest.isEqual(given.getBytes(StandardCharsets.UTF_8), key)) {
			response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "missing or wrong X-API-Key");
			return;
		}
		chain.doFilter(request, response);
	}
}
