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
package com.erudika.para.security;

import com.erudika.para.core.App;
import com.erudika.para.core.User;
import com.erudika.para.server.security.SecurityUtils;
import com.erudika.para.server.security.filters.PasswordlessAuthFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Regression coverage for the passwordless authentication filter, which self-renders its response.
 * Pins the REST bare-token contract, the 403 failure contracts, and the browser cookie+redirect /
 * absolute-URL behavior. Also pins that browser failures throw (rendered by the shared failure
 * handler) rather than being handled inline.
 *
 * @author Alex Bogdanovski [alex@erudika.com]
 */
public class PasswordlessAuthFilterTest {

	private static final String ACTION = "/" + PasswordlessAuthFilter.PASSWORDLESS_ACTION;

	@BeforeEach
	public void setUp() {
		AuthTestSupport.resetBackend();
	}

	@AfterEach
	public void tearDown() {
		SecurityContextHolder.clearContext();
		System.clearProperty("para.security.returnto");
		System.clearProperty("para.security.signin_failure");
	}

	private PasswordlessAuthFilter filter() {
		return new PasswordlessAuthFilter(ACTION);
	}

	/** Mints a valid passwordless token (signed for the given app+user). */
	private String validToken(App app, User user) {
		return SecurityUtils.generateIdToken(user, app).serialize();
	}

	@Test
	public void restSuccessReturnsBareToken() throws Exception {
		String appid = AuthTestSupport.uniqueAppId();
		App app = AuthTestSupport.createApp(appid);
		User u = AuthTestSupport.createUser(app);
		HttpServletRequest req = AuthTestSupport.mockRequest(ACTION);
		when(req.getParameter("appid")).thenReturn(appid);
		when(req.getParameter("token")).thenReturn(validToken(app, u));
		when(req.getParameter("redirect")).thenReturn("false");
		AuthTestSupport.Resp r = new AuthTestSupport.Resp();

		Authentication result = filter().attemptAuthentication(req, r.res);

		assertNull(result); // self-handled, returns null
		verify(r.res).setStatus(HttpServletResponse.SC_OK);
		verify(r.res).setContentType(MediaType.TEXT_PLAIN_VALUE);
		String body = r.text();
		assertFalse(body.isBlank());
		// a serialized JWS is three dot-separated parts (two dots)
		assertEquals(2L, body.chars().filter(c -> c == '.').count(), "body=" + body);
		verify(r.res, never()).addHeader(eq(HttpHeaders.SET_COOKIE), anyString());
	}

	@Test
	public void restFailureReturnsForbidden() throws Exception {
		String appid = AuthTestSupport.uniqueAppId();
		AuthTestSupport.createApp(appid);
		HttpServletRequest req = AuthTestSupport.mockRequest(ACTION);
		when(req.getParameter("appid")).thenReturn(appid);
		when(req.getParameter("token")).thenReturn("not-a-valid-jwt");
		when(req.getParameter("redirect")).thenReturn("false");
		AuthTestSupport.Resp r = new AuthTestSupport.Resp();

		Authentication result = filter().attemptAuthentication(req, r.res);

		assertNull(result);
		verify(r.res).sendError(HttpServletResponse.SC_FORBIDDEN);
		verify(r.res).setStatus(HttpServletResponse.SC_FORBIDDEN);
	}

	@Test
	public void restAppNotFoundReturnsForbidden() throws Exception {
		HttpServletRequest req = AuthTestSupport.mockRequest(ACTION);
		when(req.getParameter("appid")).thenReturn("does-not-exist");
		when(req.getParameter("token")).thenReturn("anything");
		when(req.getParameter("redirect")).thenReturn("false");
		AuthTestSupport.Resp r = new AuthTestSupport.Resp();

		Authentication result = filter().attemptAuthentication(req, r.res);

		assertNull(result);
		verify(r.res).sendError(HttpServletResponse.SC_FORBIDDEN);
		verify(r.res).setStatus(HttpServletResponse.SC_FORBIDDEN);
	}

	@Test
	public void browserAppNotFoundRedirectsForbidden() throws Exception {
		HttpServletRequest req = AuthTestSupport.mockRequest(ACTION);
		when(req.getParameter("appid")).thenReturn("does-not-exist");
		when(req.getParameter("token")).thenReturn("anything");
		AuthTestSupport.Resp r = new AuthTestSupport.Resp();

		Authentication result = filter().attemptAuthentication(req, r.res);

		assertNull(result);
		// returnToFailure resolves to the default signin_failure path
		verify(r.res).sendRedirect("/signin?error", HttpStatus.FORBIDDEN.value());
	}

	@Test
	public void browserSuccessSetsCookieAndRedirects() throws Exception {
		// passwordless browser success requires absolute returnto/signin_failure URLs
		System.setProperty("para.security.returnto", "https://app.example.com/ok");
		System.setProperty("para.security.signin_failure", "https://app.example.com/fail");
		String appid = AuthTestSupport.uniqueAppId();
		App app = AuthTestSupport.createApp(appid);
		User u = AuthTestSupport.createUser(app);
		HttpServletRequest req = AuthTestSupport.mockRequest(ACTION);
		when(req.getParameter("appid")).thenReturn(appid);
		when(req.getParameter("token")).thenReturn(validToken(app, u));
		AuthTestSupport.Resp r = new AuthTestSupport.Resp();

		Authentication result = filter().attemptAuthentication(req, r.res);

		// browser success commits the redirect itself and still returns the (non-null) auth, so the
		// shared success handler runs but is neutralized by response.isCommitted()
		assertNotNull(result);
		verify(r.res).addHeader(eq(HttpHeaders.SET_COOKIE), contains("-auth"));
		verify(r.res).sendRedirect("https://app.example.com/ok", HttpStatus.FOUND.value());
	}

	@Test
	public void browserNonAbsoluteReturnToFailsWithBadRequest() throws Exception {
		// default returnto/signin_failure are relative, which is rejected on the browser success path
		String appid = AuthTestSupport.uniqueAppId();
		App app = AuthTestSupport.createApp(appid);
		User u = AuthTestSupport.createUser(app);
		HttpServletRequest req = AuthTestSupport.mockRequest(ACTION);
		when(req.getParameter("appid")).thenReturn(appid);
		when(req.getParameter("token")).thenReturn(validToken(app, u));
		AuthTestSupport.Resp r = new AuthTestSupport.Resp();

		Authentication result = filter().attemptAuthentication(req, r.res);

		assertNull(result);
		verify(r.res).sendError(HttpServletResponse.SC_BAD_REQUEST);
		assertTrue(r.text().contains("must be absolute"), "body=" + r.text());
	}

	@Test
	public void browserFailureThrows() {
		String appid = AuthTestSupport.uniqueAppId();
		AuthTestSupport.createApp(appid);
		HttpServletRequest req = AuthTestSupport.mockRequest(ACTION);
		when(req.getParameter("appid")).thenReturn(appid);
		when(req.getParameter("token")).thenReturn("not-a-valid-jwt");
		AuthTestSupport.Resp r = new AuthTestSupport.Resp();

		// browser failure throws -> rendered by the shared SimpleAuthenticationFailureHandler,
		// proving the filter's inline browser 403-redirect is unreachable
		assertThrows(AuthenticationException.class, () -> filter().attemptAuthentication(req, r.res));
	}
}
