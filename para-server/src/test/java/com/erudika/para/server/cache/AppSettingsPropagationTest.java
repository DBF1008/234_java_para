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
package com.erudika.para.server.cache;

import com.erudika.para.core.App;
import com.erudika.para.core.cache.Cache;
import com.erudika.para.core.utils.Para;
import java.util.HashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import org.junit.jupiter.api.Test;

/**
 * Verifies that app-setting changes propagate through the cache with the correct multi-tenant
 * boundaries: an {@link App} is cached only in the root partition, invalidation on the acting node
 * forces a fresh reload, and other nodes converge once their own entries are evicted.
 *
 * @author Alex Bogdanovski [alex@erudika.com]
 */
public class AppSettingsPropagationTest {

	private final String root = Para.getConfig().getRootAppIdentifier();

	// models a node reading an App: serve from this node's cache, else load from the shared "DB" and cache it
	private App readApp(Cache cache, Map<String, App> db, String id) {
		App cached = cache.get(root, id);
		if (cached != null) {
			return cached;
		}
		App fromDb = db.get(id);
		if (fromDb != null) {
			cache.put(root, id, fromDb);
		}
		return fromDb;
	}

	@Test
	public void testInvalidationRefreshesActingNodeOtherNodesBoundByTtl() {
		Cache nodeA = new CaffeineCache();
		Cache nodeB = new CaffeineCache();
		Map<String, App> db = new HashMap<>();

		App v1 = new App("proptest");
		v1.addSetting("greeting", "hello");
		db.put(v1.getId(), v1);

		// both nodes read and cache the App (it lives in the root partition keyed by its id)
		assertEquals("hello", readApp(nodeA, db, v1.getId()).getSetting("greeting"));
		assertEquals("hello", readApp(nodeB, db, v1.getId()).getSetting("greeting"));

		// a settings change is persisted to the DB; the acting node (A) is invalidated on the correct partition
		App v2 = new App("proptest");
		v2.addSetting("greeting", "bonjour");
		db.put(v2.getId(), v2); // same id -> simulates an update
		nodeA.remove(root, v2.getId());

		// node A reloads fresh; node B still serves the cached value until its own entry is evicted
		assertEquals("bonjour", readApp(nodeA, db, v2.getId()).getSetting("greeting"));
		assertEquals("hello", readApp(nodeB, db, v2.getId()).getSetting("greeting"));

		// once node B is invalidated too, it also reloads fresh
		nodeB.remove(root, v2.getId());
		assertEquals("bonjour", readApp(nodeB, db, v2.getId()).getSetting("greeting"));
	}

	@Test
	public void testAppCachedOnlyInRootPartition() {
		Cache cache = new CaffeineCache();
		App app = new App("parttest");

		// the OLD MCP path cached the App under the TENANT partition -> invisible to canonical (root) reads
		cache.put(app.getAppIdentifier(), app.getId(), app);
		assertNull(cache.get(root, app.getId()));

		// the REST path and the fixed MCP path both cache under the ROOT partition -> visible to reads
		cache.remove(app.getAppIdentifier(), app.getId());
		cache.put(root, app.getId(), app);
		assertNotNull(cache.get(root, app.getId()));
	}

}
