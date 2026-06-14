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

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import org.junit.jupiter.api.Test;

/**
 *
 * @author Alex Bogdanovski [alex@erudika.com]
 */
public class CaffeineCacheTest extends CacheTest {

	public CaffeineCacheTest() {
		super(new CaffeineCache());
	}

	@Test
	public void testRemoveAll_evictsAllEntriesForApp() {
		CaffeineCache cache = new CaffeineCache();
		// Populate multiple entries for app1
		cache.put("app1", "obj1", "value1");
		cache.put("app1", "obj2", "value2");
		cache.put("app1", "obj3", "value3");
		// Populate an entry for app2
		cache.put("app2", "obj1", "valueA");

		// Verify all entries exist
		assertNotNull(cache.get("app1", "obj1"));
		assertNotNull(cache.get("app1", "obj2"));
		assertNotNull(cache.get("app1", "obj3"));
		assertNotNull(cache.get("app2", "obj1"));

		// Clear all entries for app1
		cache.removeAll("app1");

		// All app1 entries must be gone
		assertNull(cache.get("app1", "obj1"), "app1:obj1 should be evicted after removeAll");
		assertNull(cache.get("app1", "obj2"), "app1:obj2 should be evicted after removeAll");
		assertNull(cache.get("app1", "obj3"), "app1:obj3 should be evicted after removeAll");
		// app2 must be unaffected
		assertNotNull(cache.get("app2", "obj1"), "app2:obj1 must survive app1's removeAll");
	}

	@Test
	public void testRemoveAll_doesNotAffectOtherApps() {
		CaffeineCache cache = new CaffeineCache();
		cache.put("tenant1", "user1", "data1");
		cache.put("tenant2", "user1", "data2");
		cache.put("tenant3", "user1", "data3");

		cache.removeAll("tenant2");

		assertNotNull(cache.get("tenant1", "user1"), "tenant1 cache must survive");
		assertNull(cache.get("tenant2", "user1"), "tenant2 cache must be evicted");
		assertNotNull(cache.get("tenant3", "user1"), "tenant3 cache must survive");
	}

	@Test
	public void testRemoveAll_emptyApp_doesNotCreatePrefix() {
		CaffeineCache cache = new CaffeineCache();
		// removeAll on a non-existent app should not throw or create state
		cache.removeAll("nonexistent");
		// A subsequent put/get should work normally
		cache.put("nonexistent", "key1", "val1");
		assertNotNull(cache.get("nonexistent", "key1"));
	}

	@Test
	public void testRemoveAll_thenNewPutUsesSamePrefix() {
		CaffeineCache cache = new CaffeineCache();
		cache.put("app1", "obj1", "v1");
		cache.removeAll("app1");
		// After removeAll, new puts should work correctly
		cache.put("app1", "obj1", "v2");
		assertNotNull(cache.get("app1", "obj1"));
		// And removeAll again should clear the new entries
		cache.removeAll("app1");
		assertNull(cache.get("app1", "obj1"));
	}

	@Test
	public void testVariableExpiration() {
		FakeTicker ticker = new FakeTicker();
		com.github.benmanes.caffeine.cache.Cache<String, Object> caffeine = Caffeine.newBuilder()
				.expireAfter(new Expiry<String, Object>() {
					public long expireAfterCreate(String key, Object value, long currentTime) {
						return TimeUnit.MINUTES.toNanos(10);
					}
					public long expireAfterUpdate(String key, Object value, long currentTime, long currentDuration) {
						return currentDuration;
					}
					public long expireAfterRead(String key, Object value, long currentTime, long currentDuration) {
						return currentDuration;
					}
				}) // default expiration
				.executor(Runnable::run)
				.ticker(ticker::read)
				.maximumSize(10)
				.build();

		CaffeineCache cache = new CaffeineCache(caffeine);
		cache.put("app", "exp1", "w", 10L);
		cache.put("app", "exp2", "x", 20L);
		cache.put("app", "exp3", "y", 30L);
		cache.put("app", "exp4", "z"); // default expiration

		assertNotNull(cache.get("app", "exp1"));
		assertNotNull(cache.get("app", "exp2"));
		assertNotNull(cache.get("app", "exp3"));
		assertNotNull(cache.get("app", "exp4"));

		ticker.advance(5, TimeUnit.SECONDS);
		assertNotNull(cache.get("app", "exp1"));
		assertNotNull(cache.get("app", "exp2"));
		assertNotNull(cache.get("app", "exp3"));
		assertNotNull(cache.get("app", "exp4"));

		ticker.advance(5, TimeUnit.SECONDS);
		assertNull(cache.get("app", "exp1"));
		assertNotNull(cache.get("app", "exp2"));
		assertNotNull(cache.get("app", "exp3"));
		assertNotNull(cache.get("app", "exp4"));

		ticker.advance(10, TimeUnit.SECONDS);
		assertNull(cache.get("app", "exp1"));
		assertNull(cache.get("app", "exp2"));
		assertNotNull(cache.get("app", "exp3"));
		assertNotNull(cache.get("app", "exp4"));

		ticker.advance(10, TimeUnit.SECONDS);
		assertNull(cache.get("app", "exp1"));
		assertNull(cache.get("app", "exp2"));
		assertNull(cache.get("app", "exp3"));
		assertNotNull(cache.get("app", "exp4"));

		ticker.advance(10, TimeUnit.MINUTES);
		assertNull(cache.get("app", "exp1"));
		assertNull(cache.get("app", "exp2"));
		assertNull(cache.get("app", "exp3"));
		assertNull(cache.get("app", "exp4"));
	}

}
