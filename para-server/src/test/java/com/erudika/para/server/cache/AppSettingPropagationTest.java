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
import com.erudika.para.core.listeners.AppSettingAddedListener;
import com.erudika.para.core.listeners.AppSettingRemovedListener;
import com.erudika.para.core.utils.CoreUtils;
import com.erudika.para.core.utils.Para;
import java.util.HashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for the app setting propagation chain:
 * verifies that setting changes correctly invalidate cache entries
 * and that multi-tenant isolation is maintained.
 *
 * @author Para Contributors
 */
public class AppSettingPropagationTest {

	private Cache originalCache;
	private AppCacheInvalidationListener cacheListener;
	private AppSettingAddedListener addListener;
	private AppSettingRemovedListener removeListener;

	@BeforeEach
	public void setUp() {
		// Save original cache and install CaffeineCache for testing
		originalCache = CoreUtils.getInstance().getCache();
		Cache caffeineCache = new CaffeineCache();
		CoreUtils.getInstance().setCache(caffeineCache);

		// Register the built-in cache invalidation listener
		cacheListener = new AppCacheInvalidationListener();
		App.addAppSettingAddedListener(cacheListener);
		App.addAppSettingRemovedListener(cacheListener);
	}

	@AfterEach
	public void tearDown() {
		// Clean up listeners
		App.removeAppSettingAddedListener(cacheListener);
		App.removeAppSettingRemovedListener(cacheListener);
		if (addListener != null) {
			App.removeAppSettingAddedListener(addListener);
		}
		if (removeListener != null) {
			App.removeAppSettingRemovedListener(removeListener);
		}
		// Restore original cache
		CoreUtils.getInstance().setCache(originalCache);
	}

	@Test
	public void testSettingAdded_evictsCachedApp() {
		App app = new App("app:propagation-test-1");
		app.addSetting("initial", "value");

		// Simulate caching the App object (as ManagedDAO would do)
		Para.getCache().put(app.getAppid(), app.getId(), app);
		assertNotNull(Para.getCache().get(app.getAppid(), app.getId()),
				"App should be in cache before setting change");

		// Add a setting — this should trigger the cache invalidation listener
		app.addSetting("newKey", "newValue");

		// The cached App should be evicted
		assertNull(Para.getCache().get(app.getAppid(), app.getId()),
				"Cached App should be evicted after addSetting()");
	}

	@Test
	public void testSettingRemoved_evictsCachedApp() {
		App app = new App("app:propagation-test-2");
		app.addSetting("key1", "value1");

		Para.getCache().put(app.getAppid(), app.getId(), app);
		assertNotNull(Para.getCache().get(app.getAppid(), app.getId()));

		app.removeSetting("key1");

		assertNull(Para.getCache().get(app.getAppid(), app.getId()),
				"Cached App should be evicted after removeSetting()");
	}

	@Test
	public void testSetSettings_evictsCachedApp() {
		App app = new App("app:propagation-test-3");
		app.addSetting("old", "data");

		Para.getCache().put(app.getAppid(), app.getId(), app);
		assertNotNull(Para.getCache().get(app.getAppid(), app.getId()));

		Map<String, Object> newSettings = new HashMap<>();
		newSettings.put("new", "data");
		app.setSettings(newSettings);

		assertNull(Para.getCache().get(app.getAppid(), app.getId()),
				"Cached App should be evicted after setSettings()");
	}

	@Test
	public void testMultiTenantIsolation_settingsDoNotLeak() {
		App app1 = new App("app:tenant-isolation-1");
		app1.addSetting("oauth_key", "key1_secret");

		App app2 = new App("app:tenant-isolation-2");
		app2.addSetting("oauth_key", "key2_secret");

		// Cache both apps
		Para.getCache().put(app1.getAppid(), app1.getId(), app1);
		Para.getCache().put(app2.getAppid(), app2.getId(), app2);

		// Verify both are cached and isolated
		App cached1 = Para.getCache().get(app1.getAppid(), app1.getId());
		App cached2 = Para.getCache().get(app2.getAppid(), app2.getId());
		assertNotNull(cached1);
		assertNotNull(cached2);
		assertEquals("key1_secret", cached1.getSetting("oauth_key"));
		assertEquals("key2_secret", cached2.getSetting("oauth_key"));

		// Change app1's setting — should evict only app1 from cache
		app1.addSetting("oauth_key", "key1_updated");

		assertNull(Para.getCache().get(app1.getAppid(), app1.getId()),
				"app1 should be evicted from cache");
		assertNotNull(Para.getCache().get(app2.getAppid(), app2.getId()),
				"app2 cache must NOT be affected by app1's setting change");
	}

	@Test
	public void testGetSettingForApp_tenantDoesNotFallbackToRootConfig() {
		// Tenant apps should only see their own settings, not fall back to global config
		App tenantApp = new App("app:tenant-fallback-test");
		tenantApp.addSetting("custom.setting", "tenant_value");

		// A setting that exists in tenant should return tenant value
		assertEquals("tenant_value",
				Para.getConfig().getSettingForApp(tenantApp, "custom.setting", "default"));

		// A setting that does NOT exist in tenant should return default (not root config)
		assertEquals("fallback_default",
				Para.getConfig().getSettingForApp(tenantApp, "nonexistent.setting", "fallback_default"));
	}

	@Test
	public void testClearSettings_evictsCachedApp() {
		App app = new App("app:clear-settings-test");
		app.addSetting("a", 1);
		app.addSetting("b", 2);
		app.addSetting("c", 3);

		Para.getCache().put(app.getAppid(), app.getId(), app);
		assertNotNull(Para.getCache().get(app.getAppid(), app.getId()));

		app.clearSettings();

		assertNull(Para.getCache().get(app.getAppid(), app.getId()),
				"Cached App should be evicted after clearSettings()");
	}
}
