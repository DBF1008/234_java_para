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
import com.erudika.para.server.security.AuthenticatedUserDetails;
import com.erudika.para.server.security.SimpleAuthenticationFailureHandler;
import com.erudika.para.server.security.SimpleAuthenticationSuccessHandler;
import com.erudika.para.server.security.UserAuthentication;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.mockito.ArgumentCaptor;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Regression coverage for the shared authentication success/failure handlers, which render the
 * post-authentication response for the form-password flow (and every OAuth/LDAP/SAML flow that
 * shares these handlers). Pins the browser-vs-REST contract, the root-app conditional, and the
 * jwt/cause query-parameter injection.
 *
 * @author Alex Bogdanovski [alex@erudika.com]
 */
public class SigninHandlersTest {

	private static final String ACTION = "/password_auth";

	@BeforeEach
	public void setUp() {
		AuthTestSupport.resetBackend();
	}

	@AfterEach
	public void tearDown() {
		SecurityContextHolder.clearContext();
	}

	private SimpleAuthenticationSuccessHandler successHandler() {
		SimpleAuthenticationSuccessHandler h = new SimpleAuthenticationSuccessHandler();
		h.setDefaultTargetUrl(Para.getConfig().signinSuccessPath());
		h.setTargetUrlParameter(Para.getConfig().returnToPath());
		h.setUseReferer(false);
		return h;
	}

	private SimpleAuthenticationFailureHandler failureHandler() {
		SimpleAuthenticationFailureHandler h = new SimpleAuthenticationFailureHandler();
		h.setDefaultFailureUrl(Para.getConfig().signinFailurePath());
		return h;
	}

	private Authentication authOf(User u) {
		return new UserAuthentication(new AuthenticatedUserDetails(u));
	}

	// ---------- success ----------

	@Test
	public void successRestReturns204ForNonRootApp() throws Exception {
		String appid = AuthTestSupport.uniqueAppId();
		App app = AuthTestSupport.createApp(appid);
		User u = AuthTestSupport.createUser(app);
		HttpServletRequest req = AuthTestSupport.mockRequest(ACTION);
		when(req.getParameter("appid")).thenReturn(appid);
		AuthTestSupport.asRest(req);
		AuthTestSupport.Resp r = new AuthTestSupport.Resp();

		successHandler().onAuthenticationSuccess(req, r.res, authOf(u));

		verify(r.res).setStatus(HttpServletResponse.SC_NO_CONTENT);
		verify(r.res, never()).sendRedirect(anyString());
	}

	@Test
	public void successBrowserRedirectsToDefaultTarget() throws Exception {
		String appid = AuthTestSupport.uniqueAppId();
		App app = AuthTestSupport.createApp(appid);
		User u = AuthTestSupport.createUser(app);
		HttpServletRequest req = AuthTestSupport.mockRequest(ACTION);
		when(req.getParameter("appid")).thenReturn(appid);
		AuthTestSupport.Resp r = new AuthTestSupport.Resp();

		successHandler().onAuthenticationSuccess(req, r.res, authOf(u));

		verify(r.res).sendRedirect("/");
	}

	@Test
	public void successRootAppRestRedirectsInsteadOf204() throws Exception {
		App root = AuthTestSupport.createRootApp();
		User u = AuthTestSupport.createUser(root);
		HttpServletRequest req = AuthTestSupport.mockRequest(ACTION);
		when(req.getParameter("appid")).thenReturn(Para.getConfig().getRootAppIdentifier());
		AuthTestSupport.asRest(req);
		AuthTestSupport.Resp r = new AuthTestSupport.Resp();

		successHandler().onAuthenticationSuccess(req, r.res, authOf(u));

		// root app resolves a non-blank signin_success default, so even a REST call redirects
		verify(r.res).sendRedirect("/");
		verify(r.res, never()).setStatus(HttpServletResponse.SC_NO_CONTENT);
	}

	@Test
	public void successInjectsAccessTokenForJwtQuestionMark() throws Exception {
		String appid = AuthTestSupport.uniqueAppId();
		App app = new App(appid);
		app.addSetting("signin_success", "https://app.example.com/cb?jwt=?");
		app.create();
		User u = AuthTestSupport.createUser(app);
		HttpServletRequest req = AuthTestSupport.mockRequest(ACTION);
		when(req.getParameter("appid")).thenReturn(appid);
		AuthTestSupport.Resp r = new AuthTestSupport.Resp();

		successHandler().onAuthenticationSuccess(req, r.res, authOf(u));

		ArgumentCaptor<String> target = ArgumentCaptor.forClass(String.class);
		verify(r.res).sendRedirect(target.capture());
		assertTrue(target.getValue().startsWith("https://app.example.com/cb?jwt="), "actual=" + target.getValue());
		assertFalse(target.getValue().contains("jwt=?"));
	}

	@Test
	public void successInjectsIdTokenForJwtId() throws Exception {
		String appid = AuthTestSupport.uniqueAppId();
		App app = new App(appid);
		app.addSetting("signin_success", "https://app.example.com/cb?jwt=id");
		app.create();
		User u = AuthTestSupport.createUser(app);
		HttpServletRequest req = AuthTestSupport.mockRequest(ACTION);
		when(req.getParameter("appid")).thenReturn(appid);
		AuthTestSupport.Resp r = new AuthTestSupport.Resp();

		successHandler().onAuthenticationSuccess(req, r.res, authOf(u));

		ArgumentCaptor<String> target = ArgumentCaptor.forClass(String.class);
		verify(r.res).sendRedirect(target.capture());
		assertTrue(target.getValue().startsWith("https://app.example.com/cb?jwt="), "actual=" + target.getValue());
		assertFalse(target.getValue().contains("jwt=id"));
	}

	// ---------- failure ----------

	@Test
	public void failureRestReturns401ForNonRootApp() throws Exception {
		String appid = AuthTestSupport.uniqueAppId();
		AuthTestSupport.createApp(appid);
		HttpServletRequest req = AuthTestSupport.mockRequest(ACTION);
		when(req.getParameter("appid")).thenReturn(appid);
		AuthTestSupport.asRest(req);
		AuthTestSupport.Resp r = new AuthTestSupport.Resp();

		failureHandler().onAuthenticationFailure(req, r.res, new BadCredentialsException("Bad credentials."));

		verify(r.res).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
	}

	@Test
	public void failureBrowserRedirectsToFailureUrl() throws Exception {
		String appid = AuthTestSupport.uniqueAppId();
		AuthTestSupport.createApp(appid);
		HttpServletRequest req = AuthTestSupport.mockRequest(ACTION);
		when(req.getParameter("appid")).thenReturn(appid);
		AuthTestSupport.Resp r = new AuthTestSupport.Resp();

		failureHandler().onAuthenticationFailure(req, r.res, new BadCredentialsException("nope"));

		verify(r.res).sendRedirect("/signin?error");
	}

	@Test
	public void failureRootAppRestRedirectsInsteadOf401() throws Exception {
		AuthTestSupport.createRootApp();
		HttpServletRequest req = AuthTestSupport.mockRequest(ACTION);
		when(req.getParameter("appid")).thenReturn(Para.getConfig().getRootAppIdentifier());
		AuthTestSupport.asRest(req);
		AuthTestSupport.Resp r = new AuthTestSupport.Resp();

		failureHandler().onAuthenticationFailure(req, r.res, new BadCredentialsException("x"));

		verify(r.res).sendRedirect("/signin?error");
		verify(r.res, never()).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
	}

	@Test
	public void failureInjectsCauseForCauseQuestionMark() throws Exception {
		String appid = AuthTestSupport.uniqueAppId();
		App app = new App(appid);
		app.addSetting("signin_failure", "https://app.example.com/fail?cause=?");
		app.create();
		HttpServletRequest req = AuthTestSupport.mockRequest(ACTION);
		when(req.getParameter("appid")).thenReturn(appid);
		AuthTestSupport.Resp r = new AuthTestSupport.Resp();

		failureHandler().onAuthenticationFailure(req, r.res, new BadCredentialsException("boom"));

		ArgumentCaptor<String> target = ArgumentCaptor.forClass(String.class);
		verify(r.res).sendRedirect(target.capture());
		assertTrue(target.getValue().contains("cause=boom"), "actual=" + target.getValue());
	}
}
