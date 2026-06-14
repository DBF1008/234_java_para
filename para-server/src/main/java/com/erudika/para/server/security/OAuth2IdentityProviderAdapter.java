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
import com.erudika.para.server.security.filters.GenericOAuth2Filter;
import java.io.IOException;

/**
 * Adapter that wraps a single {@link GenericOAuth2Filter} instance as multiple logical
 * {@link IdentityProvider} entries. This is needed because one {@code GenericOAuth2Filter}
 * bean can serve up to three logical providers: "oauth2", "oauth2second" and "oauth2third",
 * each with a different alias for OAuth2 configuration lookup.
 *
 * <p>Each adapter instance holds an immutable name and alias, making it safe to register
 * multiple adapters in the {@link IdentityProviderRegistry} without thread-safety concerns.</p>
 *
 * @author Para
 */
public class OAuth2IdentityProviderAdapter implements IdentityProvider {

	private final String name;
	private final String alias;
	private final GenericOAuth2Filter delegate;

	/**
	 * Creates a new adapter for the generic OAuth2 filter.
	 * @param name the logical provider name (e.g. "oauth2", "oauth2second", "oauth2third")
	 * @param alias the OAuth2 config alias (null for default, "second", "third")
	 * @param delegate the shared GenericOAuth2Filter instance
	 */
	public OAuth2IdentityProviderAdapter(String name, String alias, GenericOAuth2Filter delegate) {
		this.name = name;
		this.alias = alias;
		this.delegate = delegate;
	}

	@Override
	public String getName() {
		return name;
	}

	@Override
	public UserAuthentication getOrCreateUser(App app, String accessToken) throws IOException {
		return delegate.getOrCreateUser(app, accessToken, alias);
	}

	@Override
	public boolean supportsDelegatedTokenValidation() {
		return true;
	}

	@Override
	public boolean isDelegatedTokenValid(App app, User user) {
		return delegate.isValidAccessToken(app, user);
	}
}
