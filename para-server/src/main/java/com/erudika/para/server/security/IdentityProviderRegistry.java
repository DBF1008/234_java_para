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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AuthenticationServiceException;

/**
 * Registry that maps provider names to {@link IdentityProvider} instances.
 * Used by {@link JWTRestfulAuthFilter} to dispatch REST authentication requests
 * to the correct identity provider without hardcoding provider references.
 *
 * @author Para
 */
public class IdentityProviderRegistry {

	private static final Logger logger = LoggerFactory.getLogger(IdentityProviderRegistry.class);

	private final Map<String, IdentityProvider> providers = new LinkedHashMap<>();

	/**
	 * Default constructor.
	 */
	public IdentityProviderRegistry() {
	}

	/**
	 * Registers an identity provider. The provider name is stored in lowercase
	 * for case-insensitive lookup.
	 * @param provider the identity provider to register
	 */
	public void register(IdentityProvider provider) {
		if (provider != null && !StringUtils.isBlank(provider.getName())) {
			String name = provider.getName().toLowerCase();
			providers.put(name, provider);
			logger.debug("Registered identity provider: '{}'", name);
		}
	}

	/**
	 * Looks up an identity provider by name (case-insensitive).
	 * @param providerName the provider name (e.g. "password", "facebook", "oauth2second")
	 * @return the matching provider, or null if not found
	 */
	public IdentityProvider resolve(String providerName) {
		if (StringUtils.isBlank(providerName)) {
			return null;
		}
		return providers.get(providerName.toLowerCase());
	}

	/**
	 * Validates the delegated access token for a user, if the originating identity provider
	 * supports delegated token validation. Throws an {@link AuthenticationServiceException}
	 * if the delegated token is invalid.
	 *
	 * @param app the app
	 * @param user the user whose delegated token should be validated
	 * @param idpName the identity provider name from the JWT claims
	 */
	public void validateDelegatedToken(App app, User user, String idpName) {
		if (user != null && !StringUtils.isBlank(idpName)) {
			IdentityProvider provider = resolve(idpName);
			if (provider != null && provider.supportsDelegatedTokenValidation()
					&& !provider.isDelegatedTokenValid(app, user)) {
				throw new AuthenticationServiceException(
						"The access token delegated from '" + idpName + "' is invalid.");
			}
		}
	}

	/**
	 * Returns an unmodifiable view of all registered providers.
	 * @return map of provider name (lowercase) to provider instance
	 */
	public Map<String, IdentityProvider> getProviders() {
		return Collections.unmodifiableMap(providers);
	}

	/**
	 * Returns the number of registered providers.
	 * @return provider count
	 */
	public int size() {
		return providers.size();
	}
}
