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
import com.erudika.para.server.security.filters.PasswordlessAuthFilter;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for {@link PasswordlessAuthFilter}.
 */
class PasswordlessAuthFilterTest {

	private final PasswordlessAuthFilter filter =
			new PasswordlessAuthFilter("/" + PasswordlessAuthFilter.PASSWORDLESS_ACTION);

	@Test
	void getName_returnsPasswordless() {
		assertEquals("passwordless", filter.getName());
	}

	@Test
	void getOrCreateUser_nullToken_returnsNull() {
		App app = new App("test-app");
		app.setSecret("test-secret");

		// SignedJWT.parse(null) throws NPE which is caught by the filter's try/catch
		UserAuthentication result = filter.getOrCreateUser(app, null);
		assertNull(result);
	}

	@Test
	void getOrCreateUser_malformedJWT_returnsNull() {
		App app = new App("test-app");
		app.setSecret("test-secret");

		UserAuthentication result = filter.getOrCreateUser(app, "not.a.valid.jwt");

		assertNull(result);
	}

	@Test
	void implementsIdentityProvider() {
		assertNotNull(filter.getName());
		assert filter instanceof IdentityProvider;
	}
}
