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
import com.erudika.para.core.utils.Para;
import com.erudika.para.server.security.JWTAuthenticationProvider;
import com.erudika.para.server.security.JWTRestfulAuthFilter;
import com.erudika.para.server.security.SecurityUtils;
import com.erudika.para.server.security.filters.AmazonAuthFilter;
import com.erudika.para.server.security.filters.FacebookAuthFilter;
import com.erudika.para.server.security.filters.GenericOAuth2Filter;
import com.erudika.para.server.security.filters.GitHubAuthFilter;
import com.erudika.para.server.security.filters.GoogleAuthFilter;
import com.erudika.para.server.security.filters.LdapAuthFilter;
import com.erudika.para.server.security.filters.LinkedInAuthFilter;
import com.erudika.para.server.security.filters.MicrosoftAuthFilter;
import com.erudika.para.server.security.filters.PasswordAuthFilter;
import com.erudika.para.server.security.filters.PasswordlessAuthFilter;
import com.erudika.para.server.security.filters.SlackAuthFilter;
import com.erudika.para.server.security.filters.TwitterAuthFilter;
import com.nimbusds.jwt.SignedJWT;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Regression coverage for the JWT REST authentication endpoint ({@code /jwt_auth}). Pins the
 * success token envelope shape (POST new-token, GET refresh) and the failure contracts
 * (400/403 for POST, 401 + WWW-Authenticate for GET/DELETE), plus DELETE revoke-all.
 *
 * @author Alex Bogdanovski [alex@erudika.com]
 */
public class JWTRestfulAuthFilterTest {

	private static final String ACTION = "/" + JWTRestfulAuthFilter.JWT_ACTION;

	@BeforeEach
	public void setUp() {
		AuthTestSupport.resetBackend();
	}

	@AfterEach
	public void tearDown() {
		SecurityContextHolder.clearContext();
	}

	/** Builds the filter with a working JWT auth manager and a real password provider; rest mocked. */
	private JWTRestfulAuthFilter filter() {
		AuthenticationManager am = new ProviderManager(new JWTAuthenticationProvider());
		return new JWTRestfulAuthFilter(am,
				mock(FacebookAuthFilter.class), mock(GoogleAuthFilter.class), mock(GitHubAuthFilter.class),
				mock(LinkedInAuthFilter.class), mock(TwitterAuthFilter.class), mock(MicrosoftAuthFilter.class),
				mock(SlackAuthFilter.class), mock(AmazonAuthFilter.class), mock(GenericOAuth2Filter.class),
				mock(LdapAuthFilter.class), new PasswordAuthFilter("/password_auth"), mock(PasswordlessAuthFilter.class));
	}

	private HttpServletRequest jwtRequest(String method) {
		HttpServletRequest req = AuthTestSupport.mockRequest(ACTION);
		when(req.getMethod()).thenReturn(method);
		return req;
	}

	// ---------- POST: new token ----------

	@Test
	public void postSuccessReturnsTokenEnvelope() throws Exception {
		String appid = AuthTestSupport.uniqueAppId();
		App app = AuthTestSupport.createApp(appid);
		User u = AuthTestSupport.createUser(app);
		HttpServletRequest req = jwtRequest("POST");
		AuthTestSupport.withBody(req, "{\"provider\":\"password\",\"appid\":\"" + appid + "\",\"token\":\""
				+ u.getEmail() + ":Test User:secret123\"}");
		AuthTestSupport.Resp r = new AuthTestSupport.Resp();

		filter().doFilter(req, r.res, mock(FilterChain.class));

		verify(r.res).setStatus(HttpServletResponse.SC_OK);
		String body = r.json();
		assertTrue(body.contains("\"jwt\""), "body=" + body);
		assertTrue(body.contains("access_token"), "body=" + body);
		assertTrue(body.contains("\"user\""), "body=" + body);
	}

	@Test
	public void postRootAppForbidden() throws Exception {
		HttpServletRequest req = jwtRequest("POST");
		AuthTestSupport.withBody(req, "{\"provider\":\"password\",\"appid\":\""
				+ Para.getConfig().getRootAppIdentifier() + "\",\"token\":\"a@b.com:N:secret123\"}");
		AuthTestSupport.Resp r = new AuthTestSupport.Resp();

		filter().doFilter(req, r.res, mock(FilterChain.class));

		verify(r.res).setStatus(HttpServletResponse.SC_FORBIDDEN);
	}

	@Test
	public void postMissingParamsBadRequest() throws Exception {
		String appid = AuthTestSupport.uniqueAppId();
		AuthTestSupport.createApp(appid);
		HttpServletRequest req = jwtRequest("POST");
		AuthTestSupport.withBody(req, "{\"provider\":\"password\",\"appid\":\"" + appid + "\"}"); // no token
		AuthTestSupport.Resp r = new AuthTestSupport.Resp();

		filter().doFilter(req, r.res, mock(FilterChain.class));

		verify(r.res).setStatus(HttpServletResponse.SC_BAD_REQUEST);
	}

	@Test
	public void postAppNotFoundBadRequest() throws Exception {
		HttpServletRequest req = jwtRequest("POST");
		AuthTestSupport.withBody(req, "{\"provider\":\"password\",\"appid\":\"does-not-exist\",\"token\":\"a@b.com:N:secret123\"}");
		AuthTestSupport.Resp r = new AuthTestSupport.Resp();

		filter().doFilter(req, r.res, mock(FilterChain.class));

		verify(r.res).setStatus(HttpServletResponse.SC_BAD_REQUEST);
	}

	@Test
	public void postWrongPasswordBadRequest() throws Exception {
		String appid = AuthTestSupport.uniqueAppId();
		App app = AuthTestSupport.createApp(appid);
		User u = AuthTestSupport.createUser(app);
		HttpServletRequest req = jwtRequest("POST");
		AuthTestSupport.withBody(req, "{\"provider\":\"password\",\"appid\":\"" + appid + "\",\"token\":\""
				+ u.getEmail() + ":Test User:wrongpassword\"}");
		AuthTestSupport.Resp r = new AuthTestSupport.Resp();

		filter().doFilter(req, r.res, mock(FilterChain.class));

		verify(r.res).setStatus(HttpServletResponse.SC_BAD_REQUEST);
	}

	// ---------- GET / DELETE: failures ----------

	@Test
	public void getWithoutTokenUnauthorized() throws Exception {
		HttpServletRequest req = jwtRequest("GET");
		AuthTestSupport.Resp r = new AuthTestSupport.Resp();

		filter().doFilter(req, r.res, mock(FilterChain.class));

		verify(r.res).setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"invalid_token\"");
		verify(r.res).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
	}

	@Test
	public void deleteWithoutTokenUnauthorized() throws Exception {
		HttpServletRequest req = jwtRequest("DELETE");
		AuthTestSupport.Resp r = new AuthTestSupport.Resp();

		filter().doFilter(req, r.res, mock(FilterChain.class));

		verify(r.res).setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
		verify(r.res).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
	}

	// ---------- GET / DELETE: success ----------

	@Test
	public void getRefreshSuccessReturnsEnvelope() throws Exception {
		String appid = AuthTestSupport.uniqueAppId();
		App app = AuthTestSupport.createApp(appid);
		User u = AuthTestSupport.createUser(app);
		SignedJWT token = SecurityUtils.generateJWToken(u, app);
		HttpServletRequest req = jwtRequest("GET");
		when(req.getHeader(HttpHeaders.AUTHORIZATION)).thenReturn("Bearer " + token.serialize());
		AuthTestSupport.Resp r = new AuthTestSupport.Resp();

		filter().doFilter(req, r.res, mock(FilterChain.class));

		verify(r.res).setStatus(HttpServletResponse.SC_OK);
		assertTrue(r.json().contains("\"jwt\""), "body=" + r.json());
	}

	@Test
	public void deleteRevokesAllTokens() throws Exception {
		String appid = AuthTestSupport.uniqueAppId();
		App app = AuthTestSupport.createApp(appid);
		User u = AuthTestSupport.createUser(app);
		SignedJWT token = SecurityUtils.generateJWToken(u, app);
		HttpServletRequest req = jwtRequest("DELETE");
		when(req.getHeader(HttpHeaders.AUTHORIZATION)).thenReturn("Bearer " + token.serialize());
		AuthTestSupport.Resp r = new AuthTestSupport.Resp();

		filter().doFilter(req, r.res, mock(FilterChain.class));

		verify(r.res).setStatus(HttpServletResponse.SC_OK);
		assertTrue(r.json().toLowerCase().contains("revoked"), "body=" + r.json());
	}
}
