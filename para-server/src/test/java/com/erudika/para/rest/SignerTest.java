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
import com.erudika.para.server.rest.RestUtils;
import com.erudika.para.server.security.SecurityUtils;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpHeaders;

/**
 * Regression tests for the AWS V4 request signing / verification chain.
 * <p>
 * The central property under test is that the path used to <b>sign</b> a request on the client
 * ({@link Signer}) and the path used to <b>verify</b> it on the server
 * ({@link SecurityUtils#isValidSignature(HttpServletRequest, String)}) stay in the same semantic
 * space across different servlet context paths. The client signs the full request path (context
 * path included) and the server rebuilds the same base string via
 * {@link RestUtils#getSignedRequestPath(HttpServletRequest)} - never {@code getServletPath()} -
 * so verification succeeds regardless of the context path the server is mounted under, and fails
 * the moment the context path (or method, or secret) is tampered with.
 *
 * @author Alex Bogdanovski [alex@erudika.com]
 */
public class SignerTest {

	private static final String ACCESS_KEY = "app:test-signing";
	private static final String SECRET_KEY = "very-secret-key-1234567890";
	private static final String HOST = "https://paas.example.com";
	private static final String HOST_AUTHORITY = URI.create(HOST).getAuthority();

	@BeforeAll
	public static void setUpClass() {
		// the signing chain only needs Para.getConfig() (a config facade); no Spring context required
		System.setProperty("para.env", "embedded");
		System.setProperty("para.app_name", "para-test");
		System.setProperty("para.cluster_name", "para-test");
		System.setProperty("para.print_logo", "false");
	}

	//////////////////////////////////////////////////
	//	      AWS V4 SIGNATURE - HEADER CARRIER
	//////////////////////////////////////////////////

	@Test
	public void testHeaderSignatureRoundTripAcrossContextPaths() throws Exception {
		// the same resource signed and verified under several context paths must always validate
		for (String contextPath : List.of("", "/para", "/a/b")) {
			HttpServletRequest req = signThenBuildServerRequest("GET", contextPath, "_test/path/id", null);
			assertTrue(SecurityUtils.isValidSignature(req, SECRET_KEY),
					"signature must validate for context path '" + contextPath + "'");
		}
	}

	@Test
	public void testHeaderSignatureRoundTripWithBody() throws Exception {
		byte[] body = "{\"name\":\"test\"}".getBytes(StandardCharsets.UTF_8);
		for (String contextPath : List.of("", "/para")) {
			HttpServletRequest req = signThenBuildServerRequest("POST", contextPath, "todos", body);
			assertTrue(SecurityUtils.isValidSignature(req, SECRET_KEY),
					"POST signature with a body must validate for context path '" + contextPath + "'");
		}
	}

	@Test
	public void testSignatureRejectsWrongSecret() throws Exception {
		HttpServletRequest req = signThenBuildServerRequest("GET", "/para", "_test", null);
		assertTrue(SecurityUtils.isValidSignature(req, SECRET_KEY));
		assertFalse(SecurityUtils.isValidSignature(req, "a-different-secret-key"),
				"a request signed with one secret must not validate against another");
	}

	@Test
	public void testSignatureRejectsContextPathMismatch() throws Exception {
		// sign the request as if mounted under "/para" ...
		Map<String, String> signed = signOnClient("GET", "/para", "_test", null);
		// ... but verify it as if the context path were dropped (e.g. a proxy stripped it, or
		// the server used getServletPath() instead of the full URI). It must NOT validate.
		HttpServletRequest droppedCtx = buildServerRequest("GET", "", "_test", null, signed);
		assertFalse(SecurityUtils.isValidSignature(droppedCtx, SECRET_KEY),
				"dropping the signed context path must break verification");
		// ... and verifying under a different context path must also fail
		HttpServletRequest otherCtx = buildServerRequest("GET", "/other", "_test", null, signed);
		assertFalse(SecurityUtils.isValidSignature(otherCtx, SECRET_KEY),
				"changing the context path must break verification");
	}

	@Test
	public void testSignatureRejectsMethodMismatch() throws Exception {
		Map<String, String> signed = signOnClient("GET", "/para", "_test", null);
		HttpServletRequest req = buildServerRequest("POST", "/para", "_test", null, signed);
		assertFalse(SecurityUtils.isValidSignature(req, SECRET_KEY),
				"changing the HTTP method must break verification");
	}

	//////////////////////////////////////////////////
	//	   SIGNATURE BASE STRING NORMALIZATION
	//////////////////////////////////////////////////

	@Test
	public void testSignedRequestPathAndEndpoint() {
		HttpServletRequest req = Mockito.mock(HttpServletRequest.class);
		Mockito.when(req.getRequestURI()).thenReturn("/para/v1/_test");
		Mockito.when(req.getRequestURL()).thenReturn(new StringBuffer(HOST + "/para/v1/_test"));
		// the signed path is the full URI (context path INCLUDED) - this is what the client signed
		assertEquals("/para/v1/_test", RestUtils.getSignedRequestPath(req));
		// the endpoint is the URL with the signed path removed
		assertEquals(HOST, RestUtils.getSignedRequestEndpoint(req));
		// null-safe
		assertEquals("", RestUtils.getSignedRequestPath(null));
		assertEquals("", RestUtils.getSignedRequestEndpoint(null));
	}

	@Test
	public void testNormalizeContextPath() {
		assertEquals("", RestUtils.normalizeContextPath(null));
		assertEquals("", RestUtils.normalizeContextPath(""));
		assertEquals("", RestUtils.normalizeContextPath("  "));
		assertEquals("", RestUtils.normalizeContextPath("/"));
		assertEquals("/para", RestUtils.normalizeContextPath("/para"));
		assertEquals("/a/b", RestUtils.normalizeContextPath("/a/b"));
		// under the default test config no context path is set
		assertEquals("", RestUtils.getServerContextPath());
	}

	//////////////////////////////////////////////////
	//	   QUERY-PARAM CARRIER & ANONYMOUS METHOD
	//////////////////////////////////////////////////

	@Test
	public void testQueryParamSignatureCarrierIsRecognized() {
		// the alternate "signature method" carries the credentials in query params instead of the
		// Authorization header. Verify the server reads them from the right place.
		HttpServletRequest req = Mockito.mock(HttpServletRequest.class);
		Mockito.when(req.getHeader(HttpHeaders.AUTHORIZATION)).thenReturn(null);
		Mockito.when(req.getParameter("X-Amz-Credential")).thenReturn("AWS4-HMAC-SHA256 Credential="
				+ ACCESS_KEY + "/20260614/us-east-1/para/aws4_request");
		Mockito.when(req.getParameter("X-Amz-Date")).thenReturn("20260614T120000Z");
		assertEquals(ACCESS_KEY, RestUtils.extractAccessKey(req));
		assertEquals("20260614T120000Z", RestUtils.extractDate(req));
	}

	@Test
	public void testQueryParamSignatureRejectsBadSignature() throws Exception {
		// a query-param-carried signature that does not match must be rejected (and must not throw)
		HttpServletRequest req = Mockito.mock(HttpServletRequest.class);
		Mockito.when(req.getMethod()).thenReturn("GET");
		Mockito.when(req.getRequestURI()).thenReturn("/v1/_test");
		Mockito.when(req.getContextPath()).thenReturn("");
		Mockito.when(req.getRequestURL()).thenReturn(new StringBuffer(HOST + "/v1/_test"));
		Mockito.when(req.getHeader(HttpHeaders.AUTHORIZATION)).thenReturn(null);
		Mockito.when(req.getParameter("X-Amz-Signature")).thenReturn("deadbeef");
		Mockito.when(req.getParameter("X-Amz-SignedHeaders")).thenReturn("host;x-amz-date");
		Mockito.when(req.getParameter("X-Amz-Credential")).thenReturn(ACCESS_KEY + "/20260614/us-east-1/para/aws4_request");
		Mockito.when(req.getParameter("X-Amz-Date")).thenReturn("20260614T120000Z");
		Map<String, String[]> params = new HashMap<>();
		params.put("X-Amz-Signature", new String[]{"deadbeef"});
		params.put("X-Amz-SignedHeaders", new String[]{"host;x-amz-date"});
		params.put("X-Amz-Credential", new String[]{ACCESS_KEY + "/20260614/us-east-1/para/aws4_request"});
		params.put("X-Amz-Date", new String[]{"20260614T120000Z"});
		Mockito.when(req.getParameterMap()).thenReturn(params);
		Mockito.when(req.getHeaderNames()).thenAnswer(i -> Collections.enumeration(List.of("host")));
		Mockito.when(req.getHeader("host")).thenReturn(HOST_AUTHORITY);
		Mockito.when(req.getInputStream()).thenAnswer(i -> servletInputStream(null));
		assertFalse(SecurityUtils.isValidSignature(req, SECRET_KEY));
	}

	@Test
	public void testAnonymousSignatureMethod() {
		HttpServletRequest req = Mockito.mock(HttpServletRequest.class);
		// "Anonymous {accessKey}" header is the unsigned (guest) method
		Mockito.when(req.getHeader(HttpHeaders.AUTHORIZATION)).thenReturn("Anonymous " + ACCESS_KEY);
		assertTrue(RestUtils.isAnonymousRequest(req));
		assertEquals(ACCESS_KEY, RestUtils.extractAccessKey(req));

		// no Authorization header at all is also anonymous, with the key optionally in a param
		Mockito.when(req.getHeader(HttpHeaders.AUTHORIZATION)).thenReturn(null);
		Mockito.when(req.getParameter("accessKey")).thenReturn(ACCESS_KEY);
		assertTrue(RestUtils.isAnonymousRequest(req));
		assertEquals(ACCESS_KEY, RestUtils.extractAccessKey(req));
	}

	//////////////////////////////////////////////////
	//	                 HELPERS
	//////////////////////////////////////////////////

	private static String resourceUri(String contextPath, String resource) {
		return contextPath + "/v1/" + resource;
	}

	/**
	 * Signs a request the way a client would: full path (context path included), AWS V4 header form.
	 */
	private Map<String, String> signOnClient(String method, String contextPath, String resource, byte[] body) {
		Map<String, List<String>> params = Collections.emptyMap();
		return new Signer().signRequest(ACCESS_KEY, SECRET_KEY, method, HOST,
				resourceUri(contextPath, resource), new HashMap<>(), params, body);
	}

	/**
	 * Builds a mock server request that reflects what the container delivers for a request signed by
	 * {@link #signOnClient}. The signed headers are {@code host} and {@code x-amz-date}.
	 */
	private HttpServletRequest buildServerRequest(String method, String contextPath, String resource,
			byte[] body, Map<String, String> signedHeaders) throws IOException {
		String uri = resourceUri(contextPath, resource);
		HttpServletRequest req = Mockito.mock(HttpServletRequest.class);
		Mockito.when(req.getMethod()).thenReturn(method);
		Mockito.when(req.getRequestURI()).thenReturn(uri);
		Mockito.when(req.getContextPath()).thenReturn(contextPath);
		Mockito.when(req.getRequestURL()).thenAnswer(i -> new StringBuffer(HOST + uri));
		Mockito.when(req.getHeader(HttpHeaders.AUTHORIZATION)).thenReturn(signedHeaders.get(HttpHeaders.AUTHORIZATION));
		Mockito.when(req.getHeader("x-amz-date")).thenReturn(signedHeaders.get("X-Amz-Date"));
		Mockito.when(req.getHeader("host")).thenReturn(HOST_AUTHORITY);
		Mockito.when(req.getHeaderNames()).thenAnswer(i -> Collections.enumeration(List.of("host", "x-amz-date")));
		Mockito.when(req.getParameterMap()).thenReturn(Collections.<String, String[]>emptyMap());
		byte[] payload = body;
		Mockito.when(req.getInputStream()).thenAnswer(i -> servletInputStream(payload));
		return req;
	}

	private HttpServletRequest signThenBuildServerRequest(String method, String contextPath, String resource,
			byte[] body) throws IOException {
		return buildServerRequest(method, contextPath, resource, body, signOnClient(method, contextPath, resource, body));
	}

	private static ServletInputStream servletInputStream(byte[] body) {
		ByteArrayInputStream delegate = new ByteArrayInputStream(body == null ? new byte[0] : body);
		return new ServletInputStream() {
			@Override
			public int read() throws IOException {
				return delegate.read();
			}

			@Override
			public boolean isFinished() {
				return delegate.available() == 0;
			}

			@Override
			public boolean isReady() {
				return true;
			}

			@Override
			public void setReadListener(ReadListener readListener) {
			}
		};
	}
}
