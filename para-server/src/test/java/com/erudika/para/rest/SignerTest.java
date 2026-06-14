/*
 * Copyright 2013-2026 Erudika. https://erudika.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * For issues and patches go to: https://github.com/erudika
 */
package com.erudika.para.rest;

import com.erudika.para.core.rest.Signer;
import com.erudika.para.server.security.SecurityUtils;
import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpHeaders;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;

/**
 * Tests for Signer and SecurityUtils.isValidSignature() round-trip consistency,
 * verifying that signing and verification produce matching signatures across
 * different context paths and signing methods.
 *
 * @author Para Team
 */
public class SignerTest {

	private static final String ACCESS_KEY = "app:test-app";
	private static final String SECRET_KEY = "test-secret-key-1234567890abcdef";
	private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter
			.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneId.of("Z"));

	/**
	 * Tests that a signed GET request without a context path verifies correctly.
	 */
	@Test
	public void testSignAndVerifyWithoutContextPath() {
		String method = "GET";
		String endpoint = "https://para.example.com";
		String resourcePath = "/v1/users/123";

		Map<String, String> headers = new HashMap<>();
		headers.put("Host", "para.example.com");
		headers.put("X-Amz-Date", TIME_FORMATTER.format(Instant.now()));

		Signer signer = new Signer();
		Map<String, String> signedHeaders = signer.sign(method, endpoint, resourcePath,
				headers, Collections.emptyMap(), null, ACCESS_KEY, SECRET_KEY);

		HttpServletRequest request = buildMockRequest(method, endpoint, resourcePath, signedHeaders);
		assertTrue(SecurityUtils.isValidSignature(request, SECRET_KEY),
				"Signature should verify for request without context path");
	}

	/**
	 * Tests that a signed GET request WITH a context path verifies correctly.
	 * The resourcePath includes the context path, and the server uses getRequestURI()
	 * which also includes the context path, so they must match.
	 */
	@Test
	public void testSignAndVerifyWithContextPath() {
		String method = "GET";
		String endpoint = "https://para.example.com";
		String contextPath = "/myapp";
		String resourcePath = contextPath + "/v1/users/123";

		Map<String, String> headers = new HashMap<>();
		headers.put("Host", "para.example.com");
		headers.put("X-Amz-Date", TIME_FORMATTER.format(Instant.now()));

		Signer signer = new Signer();
		Map<String, String> signedHeaders = signer.sign(method, endpoint, resourcePath,
				headers, Collections.emptyMap(), null, ACCESS_KEY, SECRET_KEY);

		HttpServletRequest request = buildMockRequest(method, endpoint, resourcePath, signedHeaders);
		Mockito.when(request.getContextPath()).thenReturn(contextPath);

		assertTrue(SecurityUtils.isValidSignature(request, SECRET_KEY),
				"Signature should verify for request with context path");
	}

	/**
	 * Tests that a signed POST request with a body verifies correctly.
	 */
	@Test
	public void testSignAndVerifyPostWithBody() {
		String method = "POST";
		String endpoint = "https://para.example.com";
		String resourcePath = "/v1/users";
		String body = "{\"name\":\"test\",\"email\":\"test@example.com\"}";

		Map<String, String> headers = new HashMap<>();
		headers.put("Host", "para.example.com");
		headers.put("X-Amz-Date", TIME_FORMATTER.format(Instant.now()));
		headers.put("Content-Type", "application/json");

		Signer signer = new Signer();
		Map<String, String> signedHeaders = signer.sign(method, endpoint, resourcePath,
				headers, Collections.emptyMap(),
				new ByteArrayInputStream(body.getBytes()), ACCESS_KEY, SECRET_KEY);

		HttpServletRequest request = buildMockRequest(method, endpoint, resourcePath, signedHeaders);
		try {
			Mockito.when(request.getInputStream()).thenReturn(
					new jakarta.servlet.ServletInputStream() {
						private final ByteArrayInputStream bais = new ByteArrayInputStream(body.getBytes());
						@Override public int read() { return bais.read(); }
						@Override public boolean isFinished() { return bais.available() == 0; }
						@Override public boolean isReady() { return true; }
						@Override public void setReadListener(jakarta.servlet.ReadListener l) { }
					});
		} catch (Exception e) {
			throw new RuntimeException(e);
		}

		assertTrue(SecurityUtils.isValidSignature(request, SECRET_KEY),
				"Signature should verify for POST request with body");
	}

	/**
	 * Tests that a signed request with query parameters verifies correctly.
	 */
	@Test
	public void testSignAndVerifyWithQueryParams() {
		String method = "GET";
		String endpoint = "https://para.example.com";
		String resourcePath = "/v1/search";

		Map<String, String> headers = new HashMap<>();
		headers.put("Host", "para.example.com");
		headers.put("X-Amz-Date", TIME_FORMATTER.format(Instant.now()));

		Map<String, String> params = new HashMap<>();
		params.put("q", "test query");
		params.put("limit", "10");

		Signer signer = new Signer();
		Map<String, String> signedHeaders = signer.sign(method, endpoint, resourcePath,
				headers, params, null, ACCESS_KEY, SECRET_KEY);

		HttpServletRequest request = buildMockRequest(method, endpoint, resourcePath, signedHeaders);
		Mockito.when(request.getParameter("q")).thenReturn("test query");
		Mockito.when(request.getParameter("limit")).thenReturn("10");
		Map<String, String[]> paramMap = new HashMap<>();
		paramMap.put("q", new String[]{"test query"});
		paramMap.put("limit", new String[]{"10"});
		Mockito.when(request.getParameterMap()).thenReturn(paramMap);

		assertTrue(SecurityUtils.isValidSignature(request, SECRET_KEY),
				"Signature should verify for request with query parameters");
	}

	/**
	 * Tests that an incorrect secret key fails verification.
	 */
	@Test
	public void testSignAndVerifyWithWrongSecret() {
		String method = "GET";
		String endpoint = "https://para.example.com";
		String resourcePath = "/v1/users/123";

		Map<String, String> headers = new HashMap<>();
		headers.put("Host", "para.example.com");
		headers.put("X-Amz-Date", TIME_FORMATTER.format(Instant.now()));

		Signer signer = new Signer();
		Map<String, String> signedHeaders = signer.sign(method, endpoint, resourcePath,
				headers, Collections.emptyMap(), null, ACCESS_KEY, SECRET_KEY);

		HttpServletRequest request = buildMockRequest(method, endpoint, resourcePath, signedHeaders);
		assertFalse(SecurityUtils.isValidSignature(request, "wrong-secret-key"),
				"Signature should NOT verify with wrong secret key");
	}

	/**
	 * Tests that a request with context path fails when the server uses a different path
	 * (simulating the bug where context path stripping causes mismatch).
	 */
	@Test
	public void testSignatureFailsWhenContextPathMismatched() {
		String method = "GET";
		String endpoint = "https://para.example.com";
		String contextPath = "/myapp";
		// Client signs with the full path including context
		String clientPath = contextPath + "/v1/users/123";

		Map<String, String> headers = new HashMap<>();
		headers.put("Host", "para.example.com");
		headers.put("X-Amz-Date", TIME_FORMATTER.format(Instant.now()));

		Signer signer = new Signer();
		Map<String, String> signedHeaders = signer.sign(method, endpoint, clientPath,
				headers, Collections.emptyMap(), null, ACCESS_KEY, SECRET_KEY);

		// Server receives the request with a different path (context path stripped incorrectly)
		String serverPath = "/v1/users/123";
		HttpServletRequest request = buildMockRequest(method, endpoint, serverPath, signedHeaders);
		Mockito.when(request.getContextPath()).thenReturn(contextPath);

		assertFalse(SecurityUtils.isValidSignature(request, SECRET_KEY),
				"Signature should fail when server path differs from client signing path");
	}

	/**
	 * Tests null/blank input handling.
	 */
	@Test
	public void testSignAndVerifyWithNullInputs() {
		assertFalse(SecurityUtils.isValidSignature(null, SECRET_KEY));
		assertFalse(SecurityUtils.isValidSignature(Mockito.mock(HttpServletRequest.class), null));
		assertFalse(SecurityUtils.isValidSignature(Mockito.mock(HttpServletRequest.class), ""));
		assertFalse(SecurityUtils.isValidSignature(Mockito.mock(HttpServletRequest.class), "  "));
	}

	/**
	 * Tests signRequest() method with anonymous request (blank secret key).
	 */
	@Test
	public void testSignRequestAnonymous() {
		Signer signer = new Signer();
		Map<String, String> result = signer.signRequest(ACCESS_KEY, "", "GET",
				"https://example.com", "/v1/users", new HashMap<>(), null, null);
		assertTrue(result.containsKey("Authorization"));
		assertTrue(result.get("Authorization").startsWith("Anonymous "));
	}

	/**
	 * Tests signRequest() with a deep context path.
	 */
	@Test
	public void testSignAndVerifyWithDeepContextPath() {
		String method = "GET";
		String endpoint = "https://para.example.com";
		String contextPath = "/org/team/para";
		String resourcePath = contextPath + "/v1/widgets/abc";

		Map<String, String> headers = new HashMap<>();
		headers.put("Host", "para.example.com");
		headers.put("X-Amz-Date", TIME_FORMATTER.format(Instant.now()));

		Signer signer = new Signer();
		Map<String, String> signedHeaders = signer.sign(method, endpoint, resourcePath,
				headers, Collections.emptyMap(), null, ACCESS_KEY, SECRET_KEY);

		HttpServletRequest request = buildMockRequest(method, endpoint, resourcePath, signedHeaders);
		Mockito.when(request.getContextPath()).thenReturn(contextPath);

		assertTrue(SecurityUtils.isValidSignature(request, SECRET_KEY),
				"Signature should verify for request with deep context path");
	}

	/**
	 * Helper: builds a mock HttpServletRequest that returns the necessary headers and URI.
	 */
	private HttpServletRequest buildMockRequest(String method, String endpoint, String resourcePath,
			Map<String, String> signedHeaders) {
		HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
		Mockito.when(request.getMethod()).thenReturn(method);
		Mockito.when(request.getRequestURI()).thenReturn(resourcePath);
		Mockito.when(request.getRequestURL()).thenReturn(new StringBuffer(endpoint + resourcePath));
		Mockito.when(request.getContextPath()).thenReturn("");

		// Return signed headers when asked
		for (Map.Entry<String, String> entry : signedHeaders.entrySet()) {
			Mockito.when(request.getHeader(entry.getKey())).thenReturn(entry.getValue());
			Mockito.when(request.getHeader(entry.getKey().toLowerCase())).thenReturn(entry.getValue());
		}

		// Mock header enumeration for signature verification
		Enumeration<String> headerNames = Collections.enumeration(signedHeaders.keySet());
		Mockito.when(request.getHeaderNames()).thenReturn(headerNames);

		// Mock empty parameter map by default
		Mockito.when(request.getParameterMap()).thenReturn(Collections.emptyMap());

		// Mock input stream (empty body by default)
		try {
			Mockito.when(request.getInputStream()).thenReturn(
					new jakarta.servlet.ServletInputStream() {
						private final ByteArrayInputStream bais = new ByteArrayInputStream(new byte[0]);
						@Override public int read() { return bais.read(); }
						@Override public boolean isFinished() { return bais.available() == 0; }
						@Override public boolean isReady() { return true; }
						@Override public void setReadListener(jakarta.servlet.ReadListener l) { }
					});
		} catch (Exception e) {
			throw new RuntimeException(e);
		}

		return request;
	}
}
