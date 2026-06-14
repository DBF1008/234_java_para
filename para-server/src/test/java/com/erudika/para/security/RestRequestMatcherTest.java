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

import com.erudika.para.core.rest.CustomResourceHandler;
import com.erudika.para.server.security.RestRequestMatcher;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestMapping;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link RestRequestMatcher#resolveHandlerPaths(CustomResourceHandler)} which resolves
 * custom resource handler paths from both the programmatic interface method and annotations.
 *
 * @author Para Team
 */
public class RestRequestMatcherTest {

	/**
	 * A handler that declares paths programmatically via getPaths().
	 */
	@RequestMapping("/v1/annotated")
	static class ProgrammaticHandler implements CustomResourceHandler {
		@Override
		public List<String> getPaths() {
			return List.of("/v1/custom", "/v1/custom/sub");
		}
	}

	/**
	 * A handler that relies on @RequestMapping annotation (default getPaths() returns empty).
	 */
	@RequestMapping(value = {"/v1/annotated1", "/v1/annotated2"})
	static class AnnotationOnlyHandler implements CustomResourceHandler {
	}

	/**
	 * A handler using @RequestMapping with the path() attribute instead of value().
	 */
	@RequestMapping(path = {"/v1/path1", "/v1/path2"})
	static class PathAttributeHandler implements CustomResourceHandler {
	}

	/**
	 * A handler with no paths at all (no annotation, no getPaths() override).
	 */
	static class EmptyHandler implements CustomResourceHandler {
	}

	/**
	 * A handler where getPaths() returns an explicit empty list, falling back to annotation.
	 */
	@RequestMapping("/v1/fallback")
	static class FallbackHandler implements CustomResourceHandler {
		@Override
		public List<String> getPaths() {
			return Collections.emptyList();
		}
	}

	@Test
	public void testResolveHandlerPaths_programmatic() {
		List<String> paths = RestRequestMatcher.resolveHandlerPaths(new ProgrammaticHandler());
		assertNotNull(paths);
		assertEquals(2, paths.size());
		assertTrue(paths.contains("/v1/custom"));
		assertTrue(paths.contains("/v1/custom/sub"));
	}

	@Test
	public void testResolveHandlerPaths_annotationValue() {
		List<String> paths = RestRequestMatcher.resolveHandlerPaths(new AnnotationOnlyHandler());
		assertNotNull(paths);
		assertEquals(2, paths.size());
		assertTrue(paths.contains("/v1/annotated1"));
		assertTrue(paths.contains("/v1/annotated2"));
	}

	@Test
	public void testResolveHandlerPaths_annotationPath() {
		List<String> paths = RestRequestMatcher.resolveHandlerPaths(new PathAttributeHandler());
		assertNotNull(paths);
		assertEquals(2, paths.size());
		assertTrue(paths.contains("/v1/path1"));
		assertTrue(paths.contains("/v1/path2"));
	}

	@Test
	public void testResolveHandlerPaths_emptyHandler() {
		List<String> paths = RestRequestMatcher.resolveHandlerPaths(new EmptyHandler());
		assertNotNull(paths);
		assertTrue(paths.isEmpty());
	}

	@Test
	public void testResolveHandlerPaths_fallbackToAnnotation() {
		List<String> paths = RestRequestMatcher.resolveHandlerPaths(new FallbackHandler());
		assertNotNull(paths);
		assertEquals(1, paths.size());
		assertTrue(paths.contains("/v1/fallback"));
	}

	@Test
	public void testResolveHandlerPaths_nullHandler() {
		List<String> paths = RestRequestMatcher.resolveHandlerPaths(null);
		assertNotNull(paths);
		assertTrue(paths.isEmpty());
	}
}
