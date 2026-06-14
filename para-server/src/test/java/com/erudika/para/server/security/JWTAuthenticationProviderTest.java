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
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.util.Date;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link JWTAuthenticationProvider}.
 */
class JWTAuthenticationProviderTest {

	private final JWTAuthenticationProvider provider = new JWTAuthenticationProvider();

	private static final String SECRET = "test_secret_key_that_is_at_least_32_bytes_long!!";

	@Test
	void supports_jwtAuthentication_returnsTrue() {
		assertTrue(provider.supports(JWTAuthentication.class));
	}

	@Test
	void supports_otherAuthentication_returnsFalse() {
		assertFalse(provider.supports(org.springframework.security.authentication.
				UsernamePasswordAuthenticationToken.class));
	}

	@Test
	void authenticate_validUserToken_returnsJwtAuthentication() throws Exception {
		App app = createApp("app1", SECRET);
		User user = createUser("user1");
		// getTokenSecret() auto-generates a secret if null; must use it for signing
		String signingSecret = SECRET + user.getTokenSecret();

		SignedJWT jwt = signToken(signingSecret, "user1", "app1", 3600);
		JWTAuthentication jwtAuth = new JWTAuthentication(
				new AuthenticatedUserDetails(user)).withJWT(jwt).withApp(app);

		Authentication result = provider.authenticate(jwtAuth);

		assertNotNull(result);
		assertTrue(result instanceof JWTAuthentication);
	}

	@Test
	void authenticate_validSuperToken_returnsAppAuthentication() throws Exception {
		App app = createApp("app1", SECRET);

		// super token has no subject (null user)
		SignedJWT jwt = signSuperToken(SECRET, "app1", 3600);
		JWTAuthentication jwtAuth = new JWTAuthentication(null).withJWT(jwt).withApp(app);

		Authentication result = provider.authenticate(jwtAuth);

		assertNotNull(result);
		assertTrue(result instanceof AppAuthentication);
	}

	@Test
	void authenticate_expiredToken_throwsBadCredentials() throws Exception {
		App app = createApp("app1", SECRET);
		User user = createUser("user1");
		String signingSecret = SECRET + user.getTokenSecret();

		// token expired 1 hour ago
		SignedJWT jwt = signToken(signingSecret, "user1", "app1", -3600);
		JWTAuthentication jwtAuth = new JWTAuthentication(
				new AuthenticatedUserDetails(user)).withJWT(jwt).withApp(app);

		assertThrows(BadCredentialsException.class, () -> provider.authenticate(jwtAuth));
	}

	@Test
	void authenticate_invalidSignature_throwsBadCredentials() throws Exception {
		App app = createApp("app1", SECRET);
		User user = createUser("user1");

		// sign with wrong secret
		SignedJWT jwt = signToken("wrong_secret_key_that_is_at_least_32_bytes!!", "user1", "app1", 3600);
		JWTAuthentication jwtAuth = new JWTAuthentication(
				new AuthenticatedUserDetails(user)).withJWT(jwt).withApp(app);

		assertThrows(BadCredentialsException.class, () -> provider.authenticate(jwtAuth));
	}

	@Test
	void authenticate_nullApp_throwsAuthenticationService() throws Exception {
		User user = createUser("user1");

		SignedJWT jwt = signToken(SECRET, "user1", "app1", 3600);
		JWTAuthentication jwtAuth = new JWTAuthentication(
				new AuthenticatedUserDetails(user)).withJWT(jwt).withApp(null);

		assertThrows(AuthenticationServiceException.class, () -> provider.authenticate(jwtAuth));
	}

	private User createUser(String id) {
		User user = new User();
		user.setId(id);
		user.setActive(true);
		user.setIdentifier(id + "@test.com");
		user.setAppid("app1");
		return user;
	}

	private App createApp(String id, String secret) {
		App app = new App(id);
		app.setSecret(secret);
		return app;
	}

	private SignedJWT signToken(String secret, String subject, String appid, long expiresAfterSec)
			throws Exception {
		Date now = new Date();
		JWTClaimsSet claims = new JWTClaimsSet.Builder()
				.subject(subject)
				.claim("appid", appid)
				.issueTime(now)
				.notBeforeTime(now)
				.expirationTime(new Date(now.getTime() + expiresAfterSec * 1000))
				.claim("refresh", now.getTime() + 1800000)
				.build();
		JWSSigner signer = new MACSigner(secret);
		SignedJWT signedJWT = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
		signedJWT.sign(signer);
		return signedJWT;
	}

	private SignedJWT signSuperToken(String secret, String appid, long expiresAfterSec)
			throws Exception {
		Date now = new Date();
		JWTClaimsSet claims = new JWTClaimsSet.Builder()
				.claim("appid", appid)
				.issueTime(now)
				.notBeforeTime(now)
				.expirationTime(new Date(now.getTime() + expiresAfterSec * 1000))
				.claim("refresh", now.getTime() + 1800000)
				.build();
		JWSSigner signer = new MACSigner(secret);
		SignedJWT signedJWT = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
		signedJWT.sign(signer);
		return signedJWT;
	}
}
