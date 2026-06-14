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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link JWTTokenService}.
 */
class JWTTokenServiceTest {

	private JWTTokenService tokenService;

	@BeforeEach
	void setUp() {
		tokenService = new JWTTokenService();
	}

	@Test
	void parseJWTFromRequest_noHeader_returnsNull() {
		MockHttpServletRequest request = new MockHttpServletRequest();

		assertNull(tokenService.parseJWTFromRequest(request));
	}

	@Test
	void parseJWTFromRequest_noBearer_returnsNull() {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.addHeader("Authorization", "Basic dXNlcjpwYXNz");

		assertNull(tokenService.parseJWTFromRequest(request));
	}

	@Test
	void parseJWTFromRequest_malformedJWT_returnsNull() {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.addHeader("Authorization", "Bearer not-a-valid-jwt");

		assertNull(tokenService.parseJWTFromRequest(request));
	}

	@Test
	void buildTokenResponse_nullUser_returnsEmptyMap() {
		var result = tokenService.buildTokenResponse(null, null);

		assertEquals(0, result.size());
	}

	@Test
	void setBearerErrorHeader_withDetail() {
		MockHttpServletResponse response = new MockHttpServletResponse();

		tokenService.setBearerErrorHeader(response, "invalid_token");

		assertEquals("Bearer error=\"invalid_token\"", response.getHeader("WWW-Authenticate"));
	}

	@Test
	void setBearerErrorHeader_withoutDetail() {
		MockHttpServletResponse response = new MockHttpServletResponse();

		tokenService.setBearerErrorHeader(response, null);

		assertEquals("Bearer", response.getHeader("WWW-Authenticate"));
	}

	@Test
	void setBearerErrorHeader_blankDetail() {
		MockHttpServletResponse response = new MockHttpServletResponse();

		tokenService.setBearerErrorHeader(response, "");

		assertEquals("Bearer", response.getHeader("WWW-Authenticate"));
	}

	@Test
	void revokeAllTokens_nullUser_noOp() {
		App app = mock(App.class);

		// should not throw
		tokenService.revokeAllTokens(null, app);
	}

	@Test
	void revokeAllTokens_nullApp_noOp() {
		com.erudika.para.core.User user = mock(com.erudika.para.core.User.class);

		// should not throw
		tokenService.revokeAllTokens(user, null);
	}

	@Test
	void generateNewToken_nullAuth_returnsNull() {
		assertNull(tokenService.generateNewToken(null));
	}

	@Test
	void generateNewToken_nullApp_returnsNull() {
		JWTAuthentication jwtAuth = new JWTAuthentication(null);
		// no app set

		assertNull(tokenService.generateNewToken(jwtAuth));
	}

	@Test
	void refreshToken_nullAuth_returnsNull() {
		IdentityProviderRegistry registry = new IdentityProviderRegistry();

		assertNull(tokenService.refreshToken(null, null, registry));
	}
}
