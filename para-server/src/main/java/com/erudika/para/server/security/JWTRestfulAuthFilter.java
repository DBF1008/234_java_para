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
import com.erudika.para.core.utils.Config;
import com.erudika.para.core.utils.Para;
import com.erudika.para.core.utils.Utils;
import com.erudika.para.server.rest.RestUtils;
import com.nimbusds.jwt.SignedJWT;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.web.filter.GenericFilterBean;

/**
 * Security filter that intercepts authentication requests (usually coming from the client-side)
 * and validates JWT tokens. Dispatches identity resolution to the {@link IdentityProviderRegistry}
 * and delegates token lifecycle operations to the {@link JWTTokenService}.
 *
 * <p>Handles three endpoints on {@code /jwt_auth}:</p>
 * <ul>
 *   <li>POST — create a new JWT by authenticating via a named identity provider</li>
 *   <li>GET — refresh an existing JWT</li>
 *   <li>DELETE — revoke all tokens for the authenticated user</li>
 * </ul>
 *
 * <p>For all other REST requests, validates the Bearer JWT token from the Authorization header
 * and sets the Spring Security context.</p>
 *
 * @author Alex Bogdanovski [alex@erudika.com]
 */
public class JWTRestfulAuthFilter extends GenericFilterBean {

	private static final Logger logger = LoggerFactory.getLogger(JWTRestfulAuthFilter.class);

	private final AuthenticationManager authenticationManager;
	private final IdentityProviderRegistry registry;
	private final JWTTokenService tokenService;
	private final PathPatternRequestMatcher authenticationRequestMatcher;

	/**
	 * The default filter mapping.
	 */
	public static final String JWT_ACTION = "jwt_auth";

	/**
	 * Constructor using the identity provider registry and token service.
	 * @param authenticationManager auth manager
	 * @param registry identity provider registry for dispatching auth requests
	 * @param tokenService JWT token lifecycle service
	 */
	public JWTRestfulAuthFilter(AuthenticationManager authenticationManager,
			IdentityProviderRegistry registry, JWTTokenService tokenService) {
		this.authenticationManager = authenticationManager;
		this.registry = registry;
		this.tokenService = tokenService;
		this.authenticationRequestMatcher = PathPatternRequestMatcher.withDefaults().matcher("/" + JWT_ACTION);
	}

	@Override
	public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
			throws IOException, ServletException {
		HttpServletRequest request = (HttpServletRequest) req;
		HttpServletResponse response = (HttpServletResponse) res;
		if (authenticationRequestMatcher.matches(request)) {
			if (HttpMethod.POST.matches(request.getMethod())) {
				newTokenHandler(request, response);
			} else if (HttpMethod.GET.matches(request.getMethod())) {
				refreshTokenHandler(request, response);
			} else if (HttpMethod.DELETE.matches(request.getMethod())) {
				revokeAllTokensHandler(request, response);
			}
			return;
		} else if (RestRequestMatcher.INSTANCE_STRICT.matches(request) &&
				SecurityContextHolder.getContext().getAuthentication() == null) {
			try {
				// validate token if present
				JWTAuthentication jwtAuth = tokenService.parseJWTFromRequest(request);
				if (jwtAuth != null) {
					Authentication auth = authenticationManager.authenticate(jwtAuth);
					tokenService.validateDelegatedTokenIfNecessary(jwtAuth, registry);
					// success!
					SecurityContextHolder.getContext().setAuthentication(auth);
				} else {
					tokenService.setBearerErrorHeader(response, null);
				}
			} catch (AuthenticationException authenticationException) {
				tokenService.setBearerErrorHeader(response, "invalid_token");
				logger.debug("AuthenticationManager rejected JWT Authentication.", authenticationException);
			}
		}

		chain.doFilter(request, response);
	}

	@SuppressWarnings("unchecked")
	private boolean newTokenHandler(HttpServletRequest request, HttpServletResponse response)
			throws IOException {
		ResponseEntity<?> res = RestUtils.getEntity(request.getInputStream(), Map.class);
		if (!res.getStatusCode().is2xxSuccessful()) {
			RestUtils.returnStatusResponse(response, res.getStatusCode().value(),
					Optional.ofNullable(res.getBody()).orElse("").toString());
			return false;
		}
		Map<String, Object> entity = (Map<String, Object>) res.getBody();
		String provider = (String) entity.get("provider");
		String appid = (String) entity.get(Config._APPID);
		String token = (String) entity.get("token");

		if (provider != null && appid != null && token != null) {
			// don't allow clients to create users on root app
			if (!App.isRoot(appid)) {
				App app = Para.getDAO().read(App.id(appid));
				if (app != null) {
					UserAuthentication userAuth = getOrCreateUser(app, provider, token);
					User user = SecurityUtils.getAuthenticatedUser(userAuth);
					if (user != null) {
						// issue token
						SignedJWT newJWT = SecurityUtils.generateJWToken(user, app);
						if (newJWT != null) {
							tokenService.writeTokenResponse(response, user, newJWT);
							return true;
						}
					} else {
						RestUtils.returnStatusResponse(response, HttpServletResponse.SC_BAD_REQUEST,
								"Failed to authenticate user with '" + provider + "'. Check if user is active.");
						return false;
					}
				} else {
					RestUtils.returnStatusResponse(response, HttpServletResponse.SC_BAD_REQUEST,
							"User belongs to app '" + appid + "' which does not exist. " +
									(App.isRoot(appid) ? "Make sure you have initialized Para." : ""));
					return false;
				}
			} else {
				RestUtils.returnStatusResponse(response, HttpServletResponse.SC_FORBIDDEN,
							"Can't authenticate user with app '" + appid + "' using provider '" + provider + "'. "
									+ "Reason: clients aren't allowed to access root app.");
					return false;
			}
		}
		RestUtils.returnStatusResponse(response, HttpServletResponse.SC_BAD_REQUEST,
				"Some of the required query parameters 'provider', 'appid', 'token', are missing.");
		return false;
	}

	private boolean refreshTokenHandler(HttpServletRequest request, HttpServletResponse response) {
		JWTAuthentication jwtAuth = tokenService.parseJWTFromRequest(request);
		JWTAuthentication refreshed = tokenService.refreshToken(jwtAuth, authenticationManager, registry);
		if (refreshed != null) {
			SignedJWT newToken = tokenService.generateNewToken(refreshed);
			if (newToken != null) {
				User user = SecurityUtils.getAuthenticatedUser(refreshed);
				tokenService.writeTokenResponse(response, user, newToken);
				return true;
			}
		}
		tokenService.setBearerErrorHeader(response, "invalid_token");
		RestUtils.returnStatusResponse(response, HttpServletResponse.SC_UNAUTHORIZED, "User must reauthenticate.");
		return false;
	}

	private boolean revokeAllTokensHandler(HttpServletRequest request, HttpServletResponse response) {
		JWTAuthentication jwtAuth = tokenService.parseJWTFromRequest(request);
		if (jwtAuth != null) {
			try {
				User user = SecurityUtils.getAuthenticatedUser(jwtAuth);
				if (user != null) {
					jwtAuth = (JWTAuthentication) authenticationManager.authenticate(jwtAuth);
					tokenService.validateDelegatedTokenIfNecessary(jwtAuth, registry);
					if (jwtAuth != null && jwtAuth.getApp() != null) {
						tokenService.revokeAllTokens(user, jwtAuth.getApp());
						RestUtils.returnStatusResponse(response, HttpServletResponse.SC_OK,
								Utils.formatMessage("All tokens revoked for user {0}!", user.getId()));
						return true;
					}
				}
			} catch (Exception ex) {
				logger.debug(null, ex);
			}
		}
		tokenService.setBearerErrorHeader(response, null);
		RestUtils.returnStatusResponse(response, HttpServletResponse.SC_UNAUTHORIZED,
				"Invalid or expired token.");
		return false;
	}

	private UserAuthentication getOrCreateUser(App app, String identityProvider, String accessToken)
			throws IOException {
		IdentityProvider provider = registry.resolve(identityProvider);
		if (provider == null) {
			return null;
		}
		try {
			return provider.getOrCreateUser(app, accessToken);
		} catch (Exception e) {
			logger.error("Failed to authenticate user with provider '{}': {}", identityProvider, e.getMessage());
			return null;
		}
	}
}
