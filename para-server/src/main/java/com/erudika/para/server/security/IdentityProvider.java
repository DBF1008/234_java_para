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
import java.io.IOException;

/**
 * A common abstraction for all identity providers in Para.
 * Each provider can authenticate users via an external access token (OAuth2 code, password, JWT, etc.)
 * and return a {@link UserAuthentication} representing the authenticated or newly-created user.
 *
 * <p>Implementations are registered in the {@link IdentityProviderRegistry} and looked up by
 * their provider name when REST clients call {@code POST /jwt_auth} with a {@code provider} field.</p>
 *
 * @author Para
 */
public interface IdentityProvider {

	/**
	 * Returns the unique name of this identity provider (e.g. "password", "facebook", "passwordless").
	 * The name is matched case-insensitively against the {@code provider} field in REST auth requests.
	 * @return provider name, never null
	 */
	String getName();

	/**
	 * Authenticates or creates a {@link User} using the given access token.
	 * The token format depends on the provider:
	 * <ul>
	 *   <li>password: "email:name:password"</li>
	 *   <li>passwordless: a signed JWT</li>
	 *   <li>OAuth2 providers: an access token or authorization code</li>
	 * </ul>
	 *
	 * @param app the app where the user will be created or authenticated
	 * @param accessToken the provider-specific credential token
	 * @return a {@link UserAuthentication} on success, or null if authentication failed
	 * @throws IOException if communication with the external IDP fails
	 */
	UserAuthentication getOrCreateUser(App app, String accessToken) throws IOException;

	/**
	 * Whether this provider supports delegated access-token validation.
	 * When true, {@link #isDelegatedTokenValid(App, User)} will be called during
	 * token refresh to ensure the original IDP token is still valid.
	 * @return true if delegated validation is supported
	 */
	default boolean supportsDelegatedTokenValidation() {
		return false;
	}

	/**
	 * Validates the delegated access token stored on the user against the external IDP.
	 * Only called when {@link #supportsDelegatedTokenValidation()} returns true.
	 * @param app the app
	 * @param user the user whose delegated token should be validated
	 * @return true if the delegated token is still valid
	 */
	default boolean isDelegatedTokenValid(App app, User user) {
		return true;
	}
}
