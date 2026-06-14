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
package com.erudika.para.core.listeners;

import com.erudika.para.core.App;
import com.erudika.para.core.cache.Cache;
import com.erudika.para.core.cache.MockCache;
import com.erudika.para.core.utils.CoreUtils;
import com.erudika.para.core.utils.Para;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Verifies that the built-in cache-invalidation listener evicts an app's cached representation from
 * the root cache partition on settings changes.
 *
 * @author Alex Bogdanovski [alex@erudika.com]
 */
public class AppSettingsCacheInvalidationListenerTest {

	private Cache previous;

	@BeforeEach
	public void setUp() {
		previous = CoreUtils.getInstance().getCache();
		CoreUtils.getInstance().setCache(new MockCache());
	}

	@AfterEach
	public void tearDown() {
		CoreUtils.getInstance().setCache(previous);
	}

	@Test
	public void testInvalidateEvictsFromRootPartition() {
		String root = Para.getConfig().getRootAppIdentifier();
		App app = new App("invtest");
		Para.getCache().put(root, app.getId(), app);
		assertNotNull(Para.getCache().get(root, app.getId()));

		AppSettingsCacheInvalidationListener.invalidate(app);
		assertNull(Para.getCache().get(root, app.getId()));
	}

	@Test
	public void testOnSettingAddedAndRemovedEvict() {
		String root = Para.getConfig().getRootAppIdentifier();
		AppSettingsCacheInvalidationListener listener = new AppSettingsCacheInvalidationListener();
		App app = new App("invtest2");

		Para.getCache().put(root, app.getId(), app);
		listener.onSettingAdded(app, "k", "v");
		assertNull(Para.getCache().get(root, app.getId()));

		Para.getCache().put(root, app.getId(), app);
		listener.onSettingRemoved(app, "k");
		assertNull(Para.getCache().get(root, app.getId()));
	}

	@Test
	public void testNullSafety() {
		// must not throw
		AppSettingsCacheInvalidationListener.invalidate((App) null);
		AppSettingsCacheInvalidationListener.invalidate(new App()); // null id
		AppSettingsCacheInvalidationListener.invalidate((String) null);
	}

}
