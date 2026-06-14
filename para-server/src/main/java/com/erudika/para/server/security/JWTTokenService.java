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
import com.erudika.para.core.utils.CoreUtils;
import com.erudika.para.core.utils.Para;
import com.nimbusds.jwt.SignedJWT;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.text.ParseException;
import java.util.HashMap;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.AuthenticationManager;

/**
 * Service that centralizes all JWT token lifecycle operations: creation, refresh, revocation,
 * parsing, and response formatting. Extracted from {@link JWTRestfulAuthFilter} to provide
 * a single, consistent API for token management across all login entry points.
 *
 * @author Para
 */
public class JWTTokenService {

	private static final Logger logger = LoggerFactory.getLogger(JWTTokenService.class);

	/**
	 * Default constructor.
	 */
	public JWTTokenService() {
	}

	/**
	 * Parses a JWT token from the HTTP request's Authorization header or query parameter.
	 * Loads the associated User and App from the database and constructs a {@link JWTAuthentication}.
	 *
	 * <p>Supports two token modes:</p>
	 * <ul>
	 *   <li>Standard user token: subject is a user ID, returns JWTAuthentication with user details</li>
	 *   <li>"Super token": no subject (or subject not found in DB), returns JWTAuthentication with
	 *       null principal, representing app-level authentication</li>
	 * </ul>
	 *
	 * @param request the HTTP request containing the Bearer token
	 * @return a JWTAuthentication, or null if no valid token is found
	 */
	public JWTAuthentication parseJWTFromRequest(HttpServletRequest request) {
		String token = request.getHeader(HttpHeaders.AUTHORIZATION);
		if (token == null) {
			token = request.getParameter(HttpHeaders.AUTHORIZATION);
		}
		if (!StringUtils.isBlank(token) && token.contains("Bearer")) {
			try {
				SignedJWT jwt = SignedJWT.parse(token.substring(6).trim());
				String userid = jwt.getJWTClaimsSet().getSubject();
				String appid = (String) jwt.getJWTClaimsSet().getClaim(Config._APPID);
				App app = Para.getDAO().read(App.id(appid));
				if (app != null) {
					User user = Para.getDAO().read(app.getAppIdentifier(), userid);
					if (user != null) {
						// standard user JWT auth, restricted access through resource permissions
						return new JWTAuthentication(new AuthenticatedUserDetails(user)).withJWT(jwt).withApp(app);
					} else {
						// "super token" - subject is authenticated as app, full access
						return new JWTAuthentication(null).withJWT(jwt).withApp(app);
					}
				}
			} catch (ParseException e) {
				logger.debug("Unable to parse JWT.", e);
			}
		}
		return null;
	}

	/**
	 * Builds the JSON response map for a successful token operation.
	 * @param user the authenticated user
	 * @param token the signed JWT
	 * @return a map containing "jwt" (with access_token, refresh, expires) and "user"
	 */
	public Map<String, Object> buildTokenResponse(User user, SignedJWT token) {
		Map<String, Object> result = new HashMap<>();
		if (user != null && token != null) {
			try {
				HashMap<String, Object> jwt = new HashMap<>();
				jwt.put("access_token", token.serialize());
				jwt.put("refresh", token.getJWTClaimsSet().getLongClaim("refresh"));
				jwt.put("expires", token.getJWTClaimsSet().getExpirationTime().getTime());
				result.put("jwt", jwt);
				result.put("user", user);
			} catch (ParseException ex) {
				logger.info("Unable to parse JWT.", ex);
			}
		}
		return result;
	}

	/**
	 * Writes a successful token response to the HTTP response as JSON.
	 * This replaces the old {@code succesHandler()} method in JWTRestfulAuthFilter.
	 *
	 * @param response the HTTP response
	 * @param user the authenticated user
	 * @param token the signed JWT
	 */
	public void writeTokenResponse(HttpServletResponse response, User user, SignedJWT token) {
		if (user != null && token != null) {
			Map<String, Object> result = buildTokenResponse(user, token);
			if (!result.isEmpty()) {
				com.erudika.para.server.rest.RestUtils.returnObjectResponse(response, result);
			} else {
				com.erudika.para.server.rest.RestUtils.returnStatusResponse(response,
						HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Bad token.");
			}
		} else {
			com.erudika.para.server.rest.RestUtils.returnStatusResponse(response,
					HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Null token.");
		}
	}

	/**
	 * Refreshes a JWT token by re-authenticating via the AuthenticationManager and issuing a new token.
	 * Also validates any delegated tokens (e.g. OAuth2 access tokens) if applicable.
	 *
	 * @param jwtAuth the existing JWT authentication
	 * @param authManager the authentication manager for re-validation
	 * @param registry the identity provider registry for delegated token validation
	 * @return a refreshed JWTAuthentication, or null if refresh failed
	 */
	public JWTAuthentication refreshToken(JWTAuthentication jwtAuth,
			AuthenticationManager authManager, IdentityProviderRegistry registry) {
		if (jwtAuth == null) {
			return null;
		}
		try {
			User user = SecurityUtils.getAuthenticatedUser(jwtAuth);
			if (user != null) {
				jwtAuth = (JWTAuthentication) authManager.authenticate(jwtAuth);
				validateDelegatedTokenIfNecessary(jwtAuth, registry);
				return jwtAuth;
			}
		} catch (Exception ex) {
			logger.debug("Token refresh failed.", ex);
		}
		return null;
	}

	/**
	 * Revokes all tokens for a user by resetting the user's token secret.
	 * This invalidates all existing JWTs signed with the previous secret.
	 *
	 * @param user the user whose tokens should be revoked
	 * @param app the app the user belongs to
	 */
	public void revokeAllTokens(User user, App app) {
		if (user != null && app != null) {
			user.resetTokenSecret();
			CoreUtils.getInstance().overwrite(app.getAppIdentifier(), user);
		}
	}

	/**
	 * Validates delegated tokens (e.g. OAuth2 access tokens) if the originating identity provider
	 * supports delegation and it is enabled for the user.
	 *
	 * @param jwtAuth the JWT authentication containing the user and IDP information
	 * @param registry the identity provider registry
	 */
	public void validateDelegatedTokenIfNecessary(JWTAuthentication jwtAuth,
			IdentityProviderRegistry registry) {
		User user = SecurityUtils.getAuthenticatedUser(jwtAuth);
		if (user != null && jwtAuth != null) {
			String identityProvider = null;
			try {
				identityProvider = (String) jwtAuth.getJwt().getJWTClaimsSet().getClaim("idp");
			} catch (ParseException ex) {
				logger.error(null, ex);
			}
			if (StringUtils.isBlank(identityProvider)) {
				identityProvider = user.getIdentityProvider();
			}
			registry.validateDelegatedToken(jwtAuth.getApp(), user, identityProvider);
		}
	}

	/**
	 * Extracts the User from a JWTAuthentication and generates a new JWT token.
	 * Convenience method for the refresh flow.
	 *
	 * @param jwtAuth the refreshed JWT authentication
	 * @return a new signed JWT, or null
	 */
	public SignedJWT generateNewToken(JWTAuthentication jwtAuth) {
		if (jwtAuth != null && jwtAuth.getApp() != null) {
			User user = SecurityUtils.getAuthenticatedUser(jwtAuth);
			if (user != null) {
				return SecurityUtils.generateJWToken(user, jwtAuth.getApp());
			}
		}
		return null;
	}

	/**
	 * Sets the Bearer error header on the response for authentication failures.
	 * @param response the HTTP response
	 * @param errorDetail optional error detail (e.g. "invalid_token"), or null for plain "Bearer"
	 */
	public void setBearerErrorHeader(HttpServletResponse response, String errorDetail) {
		if (!StringUtils.isBlank(errorDetail)) {
			response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"" + errorDetail + "\"");
		} else {
			response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
		}
	}
}
