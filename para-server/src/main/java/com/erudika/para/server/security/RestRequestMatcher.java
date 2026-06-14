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

import com.erudika.para.core.rest.CustomResourceHandler;
import com.erudika.para.core.utils.Para;
import static com.erudika.para.server.security.SecurityConfig.DEFAULT_ROLES;
import com.typesafe.config.ConfigList;
import com.typesafe.config.ConfigObject;
import com.typesafe.config.ConfigValue;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RegexRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Rest request matcher - returns true if the request is RESTful.
 * True if the URI starts with /vX.Y.Z (.Y.Z are optional).
 * Also matches custom resource handler paths so they go through the REST auth filter.
 * @author Alex Bogdanovski [alex@erudika.com]
 */
public final class RestRequestMatcher implements RequestMatcher {

	private static final Logger logger = LoggerFactory.getLogger(RestRequestMatcher.class);
	private static final List<ProtectedPath> PROTECTED_PATHS = new LinkedList<>();
	private static final List<RequestMatcher> REST_MATCHERS = new LinkedList<>();
	private static final RegexRequestMatcher REGEX = new RegexRequestMatcher("^/v\\d[\\.\\d]*/(?!(_me)).*", null, true);
	private static final RegexRequestMatcher REGEX_STRICT = new RegexRequestMatcher("^/v\\d[\\.\\d]*/.*", null, true);
	private OrRequestMatcher orMatcher = null;
	private final RegexRequestMatcher regex;
	private final boolean strict;

	static {
		ConfigObject protectedResources = Para.getConfig().protectedPaths();
		if (protectedResources != null && !protectedResources.isEmpty()) {
			for (Entry<String, ConfigValue> es : protectedResources.entrySet()) {
				ConfigValue cv = es.getValue();
				List<String> patterns = new LinkedList<>();
				List<String> roles = new LinkedList<>();
				Set<HttpMethod> methods = new HashSet<>();
				for (ConfigValue configValue : (ConfigList) cv) {
					try {
						if (configValue instanceof ConfigList) {
							for (ConfigValue role : (ConfigList) configValue) {
								String r = ((String) role.unwrapped()).toUpperCase().trim();
								// check if any HTTP methods appear here
								if (Arrays.stream(HttpMethod.values()).anyMatch(m -> m.matches(r))) {
									methods.add(HttpMethod.valueOf(r));
								} else {
									roles.add(r);
								}
							}
						} else if (configValue != null) {
							patterns.add((String) configValue.unwrapped());
						}
					} catch (Exception e) {
						logger.error("Invalid syntax for protected resource: 'security.protected.{}'.", es.getKey(), e);
					}
				}
				if (!patterns.isEmpty()) {
					boolean isRest = roles.contains("APP");
					if (methods.isEmpty()) {
						methods.addAll(Set.of(HttpMethod.values()));
					}
					if (roles.isEmpty()) {
						roles.addAll(List.of(DEFAULT_ROLES));
					}
					PROTECTED_PATHS.add(new ProtectedPath(patterns, roles, methods, isRest));
				}
			}
		}
		// Register custom resource handler paths so they go through REST authentication
		registerCustomHandlerPaths();
	}

	/**
	 * An instance of this class. Does not match /v1/_me.
	 */
	public static final RestRequestMatcher INSTANCE = new RestRequestMatcher();
	/**
	 * An instance of this class. Matches /v1/_me.
	 */
	public static final RequestMatcher INSTANCE_STRICT = new RestRequestMatcher(true);

	private RestRequestMatcher() {
		this(false);
	}

	private RestRequestMatcher(boolean strict) {
		this.strict = strict;
		this.regex = strict ? REGEX_STRICT : REGEX;
		PROTECTED_PATHS.stream().filter(p -> p.isRest()).forEach(path -> {
			path.getPatterns().forEach(pattern
					-> path.getMethods().forEach(method
							-> REST_MATCHERS.add(PathPatternRequestMatcher.withDefaults().matcher(method, pattern))));
		});
		if (!REST_MATCHERS.isEmpty()) {
			this.orMatcher = new OrRequestMatcher(REST_MATCHERS);
		}
	}

	/**
	 * Discovers custom resource handler paths and registers them as protected REST paths.
	 * Reads paths from both the {@link CustomResourceHandler#getPaths()} interface method and
	 * the {@code @RequestMapping} annotation on the handler class.
	 *
	 * <p>Paths are registered with the "APP" role (REST authentication) and all HTTP methods,
	 * ensuring that custom resource requests go through the same signature verification chain
	 * as the built-in API endpoints.</p>
	 */
	static void registerCustomHandlerPaths() {
		try {
			List<CustomResourceHandler> handlers = Para.getCustomResourceHandlers();
			for (CustomResourceHandler handler : handlers) {
				List<String> paths = resolveHandlerPaths(handler);
				if (!paths.isEmpty()) {
					PROTECTED_PATHS.add(new ProtectedPath(
							new LinkedList<>(paths),
							new LinkedList<>(List.of(DEFAULT_ROLES)),
							new HashSet<>(Set.of(HttpMethod.values())),
							true));
					logger.info("Registered custom handler paths for REST auth: {} -> {}",
							handler.getClass().getSimpleName(), paths);
				}
			}
		} catch (Exception e) {
			logger.warn("Failed to register custom resource handler paths for REST authentication.", e);
		}
	}

	/**
	 * Resolves the URL paths for a custom resource handler. Checks the programmatic
	 * {@link CustomResourceHandler#getPaths()} method first; if empty, falls back to reading
	 * the {@code @RequestMapping} annotation on the handler class.
	 *
	 * @param handler the custom resource handler
	 * @return a list of URL path patterns (servlet-relative, starting with "/")
	 */
	public static List<String> resolveHandlerPaths(CustomResourceHandler handler) {
		if (handler == null) {
			return Collections.emptyList();
		}
		// Prefer the programmatic getPaths() method
		List<String> paths = handler.getPaths();
		if (paths != null && !paths.isEmpty()) {
			return paths;
		}
		// Fall back to @RequestMapping annotation
		RequestMapping[] annos = handler.getClass().getAnnotationsByType(RequestMapping.class);
		if (annos != null && annos.length > 0 && annos[0] != null) {
			RequestMapping ann = annos[0];
			String[] values = (ann.path().length == 0) ? ann.value() : ann.path();
			if (values != null && values.length > 0) {
				return Arrays.asList(values);
			}
		}
		return Collections.emptyList();
	}

	/**
	 * Returns a list of custom protected paths.
	 * @return a list of paths
	 */
	public static List<ProtectedPath> getCustomProtectedPaths() {
		return Collections.unmodifiableList(PROTECTED_PATHS);
	}

	/**
	 * Matches a REST request path.
	 * @param request a request
	 * @return true if path is /v1/... or matches a registered custom handler path
	 */
	@Override
	public boolean matches(HttpServletRequest request) {
		// Determine if the request is RESTful.
		return !IgnoredRequestMatcher.INSTANCE.matches(request) &&
				(regex.matches(request) || (orMatcher != null && orMatcher.matches(request)));
	}

}
