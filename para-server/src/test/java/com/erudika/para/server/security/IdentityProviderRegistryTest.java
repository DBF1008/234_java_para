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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AuthenticationServiceException;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link IdentityProviderRegistry}.
 */
class IdentityProviderRegistryTest {

	private IdentityProviderRegistry registry;

	@BeforeEach
	void setUp() {
		registry = new IdentityProviderRegistry();
	}

	@Test
	void resolve_knownProvider_returnsProvider() {
		IdentityProvider provider = createProvider("password");
		registry.register(provider);

		IdentityProvider resolved = registry.resolve("password");

		assertNotNull(resolved);
		assertEquals("password", resolved.getName());
	}

	@Test
	void resolve_caseInsensitive() {
		registry.register(createProvider("password"));

		assertNotNull(registry.resolve("PASSWORD"));
		assertNotNull(registry.resolve("Password"));
		assertNotNull(registry.resolve("password"));
	}

	@Test
	void resolve_unknownProvider_returnsNull() {
		registry.register(createProvider("password"));

		assertNull(registry.resolve("facebook"));
	}

	@Test
	void resolve_nullName_returnsNull() {
		registry.register(createProvider("password"));

		assertNull(registry.resolve(null));
	}

	@Test
	void resolve_blankName_returnsNull() {
		registry.register(createProvider("password"));

		assertNull(registry.resolve(""));
		assertNull(registry.resolve("   "));
	}

	@Test
	void register_duplicateName_overridesPrevious() {
		IdentityProvider first = createProvider("password");
		IdentityProvider second = createProvider("password");
		registry.register(first);
		registry.register(second);

		assertEquals(second, registry.resolve("password"));
		assertEquals(1, registry.size());
	}

	@Test
	void register_nullProvider_ignored() {
		registry.register(null);
		assertEquals(0, registry.size());
	}

	@Test
	void register_multipleProviders() {
		registry.register(createProvider("password"));
		registry.register(createProvider("facebook"));
		registry.register(createProvider("google"));

		assertEquals(3, registry.size());
		assertNotNull(registry.resolve("password"));
		assertNotNull(registry.resolve("facebook"));
		assertNotNull(registry.resolve("google"));
	}

	@Test
	void getProviders_returnsUnmodifiableMap() {
		registry.register(createProvider("password"));

		assertThrows(UnsupportedOperationException.class, () ->
				registry.getProviders().put("hack", createProvider("hack")));
	}

	@Test
	void validateDelegatedToken_noSupport_doesNotThrow() {
		IdentityProvider provider = createProvider("password");
		registry.register(provider);

		// should not throw since password provider doesn't support delegation
		App app = mock(App.class);
		User user = mock(User.class);
		registry.validateDelegatedToken(app, user, "password");
	}

	@Test
	void validateDelegatedToken_enabledAndInvalid_throwsException() {
		App app = mock(App.class);
		User user = mock(User.class);
		IdentityProvider provider = mock(IdentityProvider.class);
		when(provider.getName()).thenReturn("oauth2");
		when(provider.supportsDelegatedTokenValidation()).thenReturn(true);
		when(provider.isDelegatedTokenValid(app, user)).thenReturn(false);
		registry.register(provider);

		assertThrows(AuthenticationServiceException.class, () ->
				registry.validateDelegatedToken(app, user, "oauth2"));
	}

	@Test
	void validateDelegatedToken_enabledAndValid_doesNotThrow() {
		App app = mock(App.class);
		User user = mock(User.class);
		IdentityProvider provider = mock(IdentityProvider.class);
		when(provider.getName()).thenReturn("oauth2");
		when(provider.supportsDelegatedTokenValidation()).thenReturn(true);
		when(provider.isDelegatedTokenValid(app, user)).thenReturn(true);
		registry.register(provider);

		registry.validateDelegatedToken(app, user, "oauth2");
		// no exception
	}

	@Test
	void validateDelegatedToken_unknownProvider_doesNotThrow() {
		registry.validateDelegatedToken(null, null, "unknown");
		// no exception - unknown provider is silently ignored
	}

	@Test
	void validateDelegatedToken_nullUser_doesNotThrow() {
		registry.validateDelegatedToken(null, null, null);
		// no exception
	}

	private IdentityProvider createProvider(String name) {
		return new IdentityProvider() {
			@Override
			public String getName() {
				return name;
			}

			@Override
			public UserAuthentication getOrCreateUser(App app, String accessToken) throws IOException {
				return null;
			}
		};
	}
}
