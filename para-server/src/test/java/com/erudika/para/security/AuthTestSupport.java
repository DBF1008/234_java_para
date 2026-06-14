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
package com.erudika.para.security;

import com.erudika.para.core.App;
import com.erudika.para.core.User;
import com.erudika.para.core.persistence.MockDAO;
import com.erudika.para.core.search.Search;
import com.erudika.para.core.utils.CoreUtils;
import com.erudika.para.core.utils.Para;
import com.erudika.para.core.utils.Utils;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.ReadListener;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletMapping;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.MappingMatch;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Shared bootstrap and servlet-mocking helpers for the authentication filter/handler regression tests.
 * Pure unit-test scaffolding: the default {@code MockDAO} is reset per test, servlet objects are
 * Mockito mocks (no spring-test on the classpath), and response sinks are captured for assertions.
 */
final class AuthTestSupport {

	private AuthTestSupport() { }

	/**
	 * Resets the shared in-memory backend so tests don't leak state into each other.
	 */
	static void resetBackend() {
		System.setProperty("para.min_password_length", "6");
		CoreUtils.getInstance().setDao(new MockDAO());
		CoreUtils.getInstance().setSearch(mock(Search.class));
	}

	/**
	 * Returns a fresh, unique app id. {@code MockDAO} is backed by a static map and
	 * {@code App.create()} is a no-op when the id already exists, so each test must use its own id
	 * to reliably persist app-specific settings.
	 * @return a unique short app id
	 */
	static String uniqueAppId() {
		return "t" + Utils.getNewId();
	}

	/**
	 * Creates and persists an app (generating its secret) so it is readable via {@code Para.getDAO()}.
	 * @param id the short app id (e.g. "test"); the stored id becomes {@code app:id}
	 * @return the persisted app
	 */
	static App createApp(String id) {
		App app = new App(id);
		app.create();
		return app;
	}

	/**
	 * Creates and persists the root app, so {@code app.isRootApp()} is true.
	 * @return the persisted root app
	 */
	static App createRootApp() {
		return createApp(Para.getConfig().getRootAppIdentifier());
	}

	/**
	 * Creates and persists an active user belonging to the given app.
	 * @param app the owning app
	 * @return the persisted user
	 */
	static User createUser(App app) {
		User u = new User(Utils.getNewId());
		u.setAppid(app.getAppIdentifier());
		u.setName("Test User");
		u.setEmail(u.getId() + "@example.com");
		u.setIdentifier(u.getEmail());
		u.setPassword("secret123");
		u.setActive(true);
		if (u.create() == null) {
			throw new IllegalStateException("Test user was not persisted (check min_password_length / validation).");
		}
		return u;
	}

	/**
	 * Builds a Mockito {@link HttpServletRequest} with sane defaults for the given servlet path.
	 * Unstubbed {@code getParameter}/{@code getHeader} return null (Mockito default); request
	 * attributes are backed by a real map so Spring's path matchers can cache a parsed path.
	 * @param servletPath the servlet path (e.g. "/password_auth")
	 * @return the mock request
	 */
	static HttpServletRequest mockRequest(String servletPath) {
		HttpServletRequest req = mock(HttpServletRequest.class);
		when(req.getServletPath()).thenReturn(servletPath);
		when(req.getRequestURI()).thenReturn(servletPath);
		when(req.getContextPath()).thenReturn("");
		when(req.getPathInfo()).thenReturn(null);
		when(req.getMethod()).thenReturn("GET");
		when(req.getQueryString()).thenReturn(null);
		when(req.getCookies()).thenReturn(null);
		when(req.getRequestURL()).thenReturn(new StringBuffer("http://localhost" + servletPath));
		when(req.isSecure()).thenReturn(false);
		// Spring's PathPatternRequestMatcher parses the path via ServletRequestPathUtils, which
		// dereferences request.getHttpServletMapping(); a bare mock returns null and NPEs. A DEFAULT
		// mapping match yields an empty servlet-path prefix, which is correct for these action paths.
		HttpServletMapping mapping = mock(HttpServletMapping.class);
		when(mapping.getMappingMatch()).thenReturn(MappingMatch.DEFAULT);
		when(req.getHttpServletMapping()).thenReturn(mapping);
		// The browser default-failure path saves the exception into the session before redirecting.
		when(req.getSession()).thenReturn(mock(HttpSession.class));
		when(req.getSession(anyBoolean())).thenReturn(mock(HttpSession.class));
		Map<String, Object> attrs = new HashMap<>();
		when(req.getAttribute(anyString())).thenAnswer(i -> attrs.get(i.getArgument(0)));
		doAnswer(i -> {
			attrs.put(i.getArgument(0), i.getArgument(1));
			return null;
		}).when(req).setAttribute(anyString(), any());
		return req;
	}

	/**
	 * Stubs a request to carry a JSON {@code Accept} header (so the handlers treat it as a REST call).
	 * @param req the mock request
	 */
	static void asRest(HttpServletRequest req) {
		when(req.getHeader("Accept")).thenReturn("application/json");
	}

	/**
	 * Stubs the request body returned by {@code getInputStream()} (used by the JWT POST handler).
	 * @param req the mock request
	 * @param body the raw request body
	 */
	static void withBody(HttpServletRequest req, String body) {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		try {
			when(req.getInputStream()).thenReturn(new CapturingServletInputStream(new ByteArrayInputStream(bytes)));
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * A captured response: its writer and output-stream sinks are recorded for assertions.
	 * The two sinks exist because {@code RestUtils} writes via {@code getOutputStream()} while the
	 * passwordless filter writes via {@code getWriter()}.
	 */
	static final class Resp {
		final HttpServletResponse res;
		final StringWriter writerSink = new StringWriter();
		final ByteArrayOutputStream streamSink = new ByteArrayOutputStream();

		Resp() {
			res = mock(HttpServletResponse.class);
			try {
				when(res.getWriter()).thenReturn(new PrintWriter(writerSink, true));
				when(res.getOutputStream()).thenReturn(new CapturingServletOutputStream(streamSink));
				when(res.encodeRedirectURL(anyString())).thenAnswer(i -> i.getArgument(0));
				when(res.isCommitted()).thenReturn(false);
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		}

		/** @return body written via getWriter() */
		String text() {
			return writerSink.toString();
		}

		/** @return body written via getOutputStream() (JSON responses) */
		String json() {
			return streamSink.toString(StandardCharsets.UTF_8);
		}
	}

	/**
	 * A {@link ServletOutputStream} that delegates to a {@link ByteArrayOutputStream}.
	 */
	static final class CapturingServletOutputStream extends ServletOutputStream {
		private final ByteArrayOutputStream target;

		CapturingServletOutputStream(ByteArrayOutputStream target) {
			this.target = target;
		}

		@Override
		public void write(int b) {
			target.write(b);
		}

		@Override
		public boolean isReady() {
			return true;
		}

		@Override
		public void setWriteListener(WriteListener listener) {
		}
	}

	/**
	 * A {@link ServletInputStream} that delegates to a {@link ByteArrayInputStream}.
	 */
	static final class CapturingServletInputStream extends ServletInputStream {
		private final ByteArrayInputStream source;

		CapturingServletInputStream(ByteArrayInputStream source) {
			this.source = source;
		}

		@Override
		public int read() {
			return source.read();
		}

		@Override
		public boolean isFinished() {
			return source.available() == 0;
		}

		@Override
		public boolean isReady() {
			return true;
		}

		@Override
		public void setReadListener(ReadListener listener) {
		}
	}
}
