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
package com.erudika.para.server.security;

import com.erudika.para.core.App;
import com.erudika.para.core.User;
import com.erudika.para.core.utils.Para;
import com.erudika.para.server.rest.RestUtils;
import com.erudika.para.server.utils.HttpUtils;
import com.nimbusds.jwt.SignedJWT;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.text.ParseException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.apache.commons.lang3.math.NumberUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.DefaultRedirectStrategy;
import org.springframework.security.web.RedirectStrategy;
import org.springframework.web.util.UriComponents;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Single source of truth for the post-authentication state transitions of every login entry.
 * <p>
 * The form-password flow (and every OAuth/LDAP/SAML flow) renders through the
 * {@link SimpleAuthenticationSuccessHandler}/{@link SimpleAuthenticationFailureHandler}, which
 * delegate here; the self-handled passwordless filter and the JWT REST endpoint call the relevant
 * methods directly. Co-locating the distinct renderings here keeps the browser/REST contracts,
 * token issuance, cookie policy and redirect-target resolution from drifting apart when one entry
 * is changed. The observable HTTP contracts are intentionally preserved per entry.
 *
 * @author Alex Bogdanovski [alex@erudika.com]
 */
public final class SigninOrchestrator {

	private static final Logger logger = LoggerFactory.getLogger(SigninOrchestrator.class);
	private static final RedirectStrategy REDIRECT = new DefaultRedirectStrategy();

	private SigninOrchestrator() { }

	/**
	 * Resolves and performs the configured {@code signin_success} redirect for a browser flow,
	 * injecting a fresh access token ({@code jwt=?}) or id token ({@code jwt=id}) into the URL when
	 * requested. Used by the shared success handler.
	 * @param request HTTP request
	 * @param response HTTP response
	 * @param authentication the successful authentication
	 * @return true if a redirect was performed (the caller must stop), false to fall back to the
	 * default success behavior (REST 204 or the framework's default redirect)
	 * @throws IOException if the redirect cannot be written
	 */
	public static boolean redirectToConfiguredSuccessUrl(HttpServletRequest request, HttpServletResponse response,
			Authentication authentication) throws IOException {
		User u = SecurityUtils.getAuthenticatedUser(authentication);
		String appid = StringUtils.defaultIfBlank(SecurityUtils.getAppidFromAuthRequest(request), u.getAppid());
		if (!StringUtils.isBlank(appid)) {
			// try to reload custom redirect URI from app
			App app = Para.getDAO().read(App.id(appid));
			if (app != null) {
				String customURI = resolveCustomUri(request, app, "signin_success",
						Para.getConfig().signinSuccessPath());
				if (Strings.CS.contains(customURI, "jwt=?")) {
					SignedJWT newJWT = SecurityUtils.generateJWToken(u, app);
					customURI = customURI.replace("jwt=?", "jwt=" + newJWT.serialize());
				}
				if (Strings.CS.contains(customURI, "jwt=id")) {
					SignedJWT newJWT = SecurityUtils.generateIdToken(u, app);
					customURI = customURI.replace("jwt=id", "jwt=" + newJWT.serialize());
				}
				if (!StringUtils.isBlank(customURI)) {
					if (!response.isCommitted()) {
						REDIRECT.sendRedirect(request, response, customURI);
					}
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Resolves and performs the configured {@code signin_failure} redirect for a browser flow,
	 * injecting the failure cause ({@code cause=?}) into the URL when requested. Used by the shared
	 * failure handler.
	 * @param request HTTP request
	 * @param response HTTP response
	 * @param exception the authentication failure
	 * @return true if a redirect was performed (the caller must stop), false to fall back to the
	 * default failure behavior (REST 401 or the framework's default redirect)
	 * @throws IOException if the redirect cannot be written
	 */
	public static boolean redirectToConfiguredFailureUrl(HttpServletRequest request, HttpServletResponse response,
			AuthenticationException exception) throws IOException {
		String appid = SecurityUtils.getAppidFromAuthRequest(request);
		if (!StringUtils.isBlank(appid)) {
			// try to reload custom redirect URI from app
			App app = Para.getDAO().read(App.id(appid));
			if (app != null) {
				String customURI = resolveCustomUri(request, app, "signin_failure",
						Para.getConfig().signinFailurePath());
				if (Strings.CS.contains(customURI, "cause=?")) {
					customURI = customURI.replace("cause=?", "cause=" + exception.getMessage());
				}
				if (!StringUtils.isBlank(customURI)) {
					if (!response.isCommitted()) {
						REDIRECT.sendRedirect(request, response, customURI);
					}
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Issues the auth cookie and redirects, completing a successful passwordless browser login.
	 * The redirect target is the (already-resolved) {@code returnto} URL, deliberately not
	 * {@code signin_success}, to avoid a redirect loop.
	 * @param request HTTP request
	 * @param response HTTP response
	 * @param app the app
	 * @param appid the app id
	 * @param user the authenticated user
	 * @param returnToSuccess the absolute return-to URL
	 * @throws IOException if the redirect cannot be written
	 */
	public static void completePasswordlessBrowserSuccess(HttpServletRequest request, HttpServletResponse response,
			App app, String appid, User user, String returnToSuccess) throws IOException {
		boolean httpOnly = "true".equals(StringUtils.defaultIfBlank(request.getParameter("httpOnlyCookie"), "true"));
		String sameSite = StringUtils.defaultIfBlank(request.getParameter("sameSiteCookie"), "Strict");
		String authCookieName = Para.getConfig().getSettingForApp(app, "auth_cookie",
				StringUtils.join(App.identifier(appid), "-auth"));
		String authCookieValue = SecurityUtils.generateJWToken(user, app).serialize();
		int maxAge = NumberUtils.toInt(Para.getConfig().getSettingForApp(app, "session_timeout", null),
				app.getTokenValiditySec().intValue());
		HttpUtils.setAuthCookie(authCookieName, authCookieValue, httpOnly, maxAge, sameSite, request, response);
		response.sendRedirect(returnToSuccess, HttpStatus.FOUND.value());
	}

	/**
	 * Writes a bare, serialized JWT as the REST response of a successful passwordless login.
	 * @param response HTTP response
	 * @param app the app
	 * @param user the authenticated user
	 * @throws IOException if the body cannot be written
	 */
	public static void writePasswordlessRestToken(HttpServletResponse response, App app, User user) throws IOException {
		response.setContentType(MediaType.TEXT_PLAIN_VALUE);
		response.setStatus(HttpStatus.OK.value());
		response.getWriter().print(SecurityUtils.generateJWToken(user, app).serialize());
	}

	/**
	 * Writes a {@code 403 Forbidden} REST failure (used by the passwordless filter).
	 * @param response HTTP response
	 * @throws IOException if the error cannot be written
	 */
	public static void writeForbidden(HttpServletResponse response) throws IOException {
		response.sendError(HttpStatus.FORBIDDEN.value());
		response.setStatus(HttpStatus.FORBIDDEN.value());
	}

	/**
	 * Redirects to the failure URL with a {@code 403 Forbidden} status (used by the passwordless
	 * filter for a browser flow when the app cannot be resolved).
	 * @param response HTTP response
	 * @param returnToFailure the failure redirect URL
	 * @throws IOException if the redirect cannot be written
	 */
	public static void redirectForbidden(HttpServletResponse response, String returnToFailure) throws IOException {
		response.sendRedirect(returnToFailure, HttpStatus.FORBIDDEN.value());
	}

	/**
	 * Writes the canonical JWT token envelope ({@code {jwt:{access_token,refresh,expires}, user}})
	 * as the REST response of the JWT auth endpoint.
	 * @param response HTTP response
	 * @param user the authenticated user
	 * @param token the issued token
	 */
	public static void writeTokenEnvelope(HttpServletResponse response, User user, final SignedJWT token) {
		if (user != null && token != null) {
			Map<String, Object> result = new HashMap<>();
			try {
				HashMap<String, Object> jwt = new HashMap<>();
				jwt.put("access_token", token.serialize());
				jwt.put("refresh", token.getJWTClaimsSet().getLongClaim("refresh"));
				jwt.put("expires", token.getJWTClaimsSet().getExpirationTime().getTime());
				result.put("jwt", jwt);
				result.put("user", user);
			} catch (ParseException ex) {
				logger.info("Unable to parse JWT.", ex);
				RestUtils.returnStatusResponse(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Bad token.");
			}
			RestUtils.returnObjectResponse(response, result);
		} else {
			RestUtils.returnStatusResponse(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Null token.");
		}
	}

	/**
	 * Shared resolution of a custom redirect URI from an app setting: applies the root-app default
	 * and the host-url-alias rewrite. The per-flow token/cause injection is done by the caller.
	 */
	private static String resolveCustomUri(HttpServletRequest request, App app, String settingKey,
			String rootDefault) {
		String customURI = (String) app.getSetting(settingKey);
		Set<String> hostUrlAliases = SecurityUtils.getHostUrlAliasesForReturn(app);
		String hostUrlParam = SecurityUtils.getHostUrlFromQueryStringOrStateParam(hostUrlAliases, request);
		if (app.isRootApp() && StringUtils.isBlank(customURI)) {
			customURI = rootDefault;
		}
		if (!StringUtils.isBlank(hostUrlParam)) {
			if (hostUrlAliases.contains(hostUrlParam) || Strings.CS.startsWith(customURI, hostUrlParam)) {
				UriComponents hostUrl = UriComponentsBuilder.fromUriString(hostUrlParam).build();
				customURI = UriComponentsBuilder.fromUriString(customURI).host(hostUrl.getHost()).toUriString();
			} else {
				UriComponents customHost = UriComponentsBuilder.fromUriString(customURI).build();
				customURI = customHost.getScheme() + "://" + customHost.getHost();
			}
		}
		return customURI;
	}
}
