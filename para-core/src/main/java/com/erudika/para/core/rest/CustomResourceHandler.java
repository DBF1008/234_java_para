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

package com.erudika.para.core.rest;

import java.util.Collections;
import java.util.List;

/**
 * A custom API resource handler. Handles custom resources.
 * Usually, the implementation of this interface would be a custom @RestController in Spring.
 *
 * <p>Implementations may override {@link #getPaths()} to programmatically declare the URL paths
 * they handle. These paths are registered with the REST request matcher so that incoming requests
 * to custom resources go through the same authentication and signature verification chain as the
 * built-in API endpoints.</p>
 *
 * <p>Paths returned by {@link #getPaths()} are relative to the servlet context (i.e., they should
 * start with "/" but must NOT include the context path). For example, if the server runs with
 * context path "/myapp", a handler for "/myapp/v1/custom" should return "/v1/custom".</p>
 *
 * @author Alex Bogdanovski [alex@erudika.com]
 */
public interface CustomResourceHandler {

	/**
	 * Returns the URL paths that this handler serves, relative to the servlet context root.
	 * Paths should start with "/" and must NOT include the server's context path.
	 *
	 * <p>The default implementation returns an empty list, which means paths will be inferred
	 * from the {@code @RequestMapping} annotation on the handler class.</p>
	 *
	 * <p>Example: a handler mapped to "/v1/widgets" should return {@code List.of("/v1/widgets")}.</p>
	 *
	 * @return a list of URL path patterns, or empty list to use annotation-based discovery
	 */
	default List<String> getPaths() {
		return Collections.emptyList();
	}
}
