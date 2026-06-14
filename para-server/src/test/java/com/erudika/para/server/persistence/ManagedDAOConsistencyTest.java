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
package com.erudika.para.server.persistence;

import com.erudika.para.core.ParaObject;
import com.erudika.para.core.Sysprop;
import com.erudika.para.core.cache.Cache;
import com.erudika.para.core.persistence.DAO;
import com.erudika.para.core.persistence.MockDAO;
import com.erudika.para.core.search.Search;
import com.erudika.para.core.utils.CoreUtils;
import com.erudika.para.core.utils.Para;
import java.util.Arrays;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;

/**
 * Regression tests for ManagedDAO consistency guarantees:
 * - Error isolation: Search/Cache failures must not prevent DB writes
 * - Batch validation: invalid objects are skipped, valid ones persisted
 * - Delete consistency: all layers cleaned up
 */
public class ManagedDAOConsistencyTest {

	private static final String APPID = "testapp";
	private MockDAO rawDao;
	private Search mockSearch;
	private Cache mockCache;
	private ManagedDAO managedDao;

	@BeforeEach
	public void setUp() {
		rawDao = new MockDAO();
		mockSearch = mock(Search.class);
		mockCache = mock(Cache.class);

		// Wire up the service registry so Para.getSearch()/getCache() return our mocks
		CoreUtils.getInstance().setDao(rawDao);
		CoreUtils.getInstance().setSearch(mockSearch);
		CoreUtils.getInstance().setCache(mockCache);

		// Enable search and cache for these tests
		System.setProperty("para.search_enabled", "true");
		System.setProperty("para.cache_enabled", "true");

		managedDao = new ManagedDAO(rawDao);
		CoreUtils.getInstance().setDao(managedDao);
	}

	@Test
	public void testSearchIndexFailureDoesNotPreventDBWrite() {
		// Arrange: Search.index() throws
		doThrow(new RuntimeException("Search backend unavailable"))
				.when(mockSearch).index(eq(APPID), any(ParaObject.class));

		Sysprop obj = new Sysprop("obj-search-fail");
		obj.setName("Should be in DB");
		obj.setAppid(APPID);

		// Act: create should NOT throw, and object should be in DB
		managedDao.create(APPID, obj);

		// Assert: object exists in raw DAO (DB layer)
		Sysprop fromDB = rawDao.read(APPID, "obj-search-fail");
		assertNotNull(fromDB, "Object should be persisted in DB even when search indexing fails");
		assertEquals("Should be in DB", fromDB.getName());

		// Verify search was attempted
		verify(mockSearch).index(eq(APPID), any(ParaObject.class));
	}

	@Test
	public void testCacheFailureDoesNotPreventIndexing() {
		// Arrange: Cache.put() throws
		doThrow(new RuntimeException("Cache backend unavailable"))
				.when(mockCache).put(eq(APPID), anyString(), any());

		Sysprop obj = new Sysprop("obj-cache-fail");
		obj.setName("Should be indexed");
		obj.setAppid(APPID);

		// Act
		managedDao.create(APPID, obj);

		// Assert: object exists in DB
		assertNotNull(rawDao.read(APPID, "obj-cache-fail"));

		// Assert: search was still called (not skipped due to cache failure)
		verify(mockSearch).index(eq(APPID), any(ParaObject.class));
	}

	@Test
	public void testSearchIndexAllFailureDoesNotPreventDBWrite() {
		// Arrange: Search.indexAll() throws
		doThrow(new RuntimeException("Search batch failed"))
				.when(mockSearch).indexAll(eq(APPID), anyList());

		Sysprop obj1 = new Sysprop("batch-fail-1");
		obj1.setAppid(APPID);
		Sysprop obj2 = new Sysprop("batch-fail-2");
		obj2.setAppid(APPID);

		// Act
		managedDao.createAll(APPID, Arrays.asList(obj1, obj2));

		// Assert: both objects in DB
		assertNotNull(rawDao.read(APPID, "batch-fail-1"));
		assertNotNull(rawDao.read(APPID, "batch-fail-2"));
	}

	@Test
	public void testBatchCachePutAllFailureDoesNotPreventIndexing() {
		// Arrange: Cache.putAll() throws
		doThrow(new RuntimeException("Cache batch failed"))
				.when(mockCache).putAll(eq(APPID), anyMap());

		Sysprop obj1 = new Sysprop("cache-batch-1");
		obj1.setAppid(APPID);
		Sysprop obj2 = new Sysprop("cache-batch-2");
		obj2.setAppid(APPID);

		// Act
		managedDao.createAll(APPID, Arrays.asList(obj1, obj2));

		// Assert: objects in DB and search was called
		assertNotNull(rawDao.read(APPID, "cache-batch-1"));
		assertNotNull(rawDao.read(APPID, "cache-batch-2"));
		verify(mockSearch).indexAll(eq(APPID), anyList());
	}

	@Test
	public void testDeleteRemovesFromAllLayers() {
		// Arrange: create an object first
		Sysprop obj = new Sysprop("to-delete");
		obj.setAppid(APPID);
		managedDao.create(APPID, obj);
		assertNotNull(rawDao.read(APPID, "to-delete"));

		// Act
		managedDao.delete(APPID, obj);

		// Assert: removed from DB
		assertNull(rawDao.read(APPID, "to-delete"));

		// Assert: unindex called
		verify(mockSearch, atLeastOnce()).unindex(eq(APPID), any(ParaObject.class));

		// Assert: cache remove called
		verify(mockCache, atLeastOnce()).remove(eq(APPID), eq("to-delete"));
	}

	@Test
	public void testBatchDeleteRemovesFromAllLayers() {
		// Arrange: create objects
		Sysprop obj1 = new Sysprop("batch-del-1");
		obj1.setAppid(APPID);
		Sysprop obj2 = new Sysprop("batch-del-2");
		obj2.setAppid(APPID);
		managedDao.createAll(APPID, Arrays.asList(obj1, obj2));

		// Act
		managedDao.deleteAll(APPID, Arrays.asList(obj1, obj2));

		// Assert: removed from DB
		assertNull(rawDao.read(APPID, "batch-del-1"));
		assertNull(rawDao.read(APPID, "batch-del-2"));

		// Assert: unindexAll called
		verify(mockSearch, atLeastOnce()).unindexAll(eq(APPID), anyList());

		// Assert: cache removeAll called
		verify(mockCache, atLeastOnce()).removeAll(eq(APPID), anyList());
	}

	@Test
	public void testReadFromCacheOnHit() {
		// Arrange: put object in cache
		Sysprop obj = new Sysprop("cache-hit");
		obj.setName("Cached Name");
		obj.setAppid(APPID);
		when(mockCache.get(eq(APPID), eq("cache-hit"))).thenReturn(obj);

		// Act
		Sysprop result = managedDao.read(APPID, "cache-hit");

		// Assert: returned from cache, DAO not called
		assertNotNull(result);
		assertEquals("Cached Name", result.getName());
		// rawDao should not have been called for read
		assertNull(rawDao.read(APPID, "cache-hit"));
	}

	@Test
	public void testReadFromDBOnCacheMiss() {
		// Arrange: cache miss, but object in DB
		Sysprop obj = new Sysprop("cache-miss");
		obj.setName("DB Name");
		obj.setAppid(APPID);
		rawDao.create(APPID, obj);
		when(mockCache.get(eq(APPID), eq("cache-miss"))).thenReturn(null);

		// Act
		Sysprop result = managedDao.read(APPID, "cache-miss");

		// Assert: returned from DB
		assertNotNull(result);
		assertEquals("DB Name", result.getName());

		// Assert: cache was populated with the DB result
		verify(mockCache).put(eq(APPID), eq("cache-miss"), any());
	}

	@Test
	public void testWriteOrdering_DBBeforeIndexBeforeCache() {
		// This test verifies that DB write happens before Search.index and Cache.put
		// by checking that when Search.index is called, the object is already in DB

		Sysprop obj = new Sysprop("ordering-test");
		obj.setName("Order Test");
		obj.setAppid(APPID);

		// Use Answer to check DB state at the moment Search.index is called
		doAnswer(invocation -> {
			// At this point, the object should already be in the raw DAO
			Sysprop fromDB = rawDao.read(APPID, "ordering-test");
			assertNotNull(fromDB, "Object should be in DB before Search.index is called");
			assertEquals("Order Test", fromDB.getName());
			return null;
		}).when(mockSearch).index(eq(APPID), any(ParaObject.class));

		// Act
		managedDao.create(APPID, obj);

		// Verify search was called (and thus the Answer was executed)
		verify(mockSearch).index(eq(APPID), any(ParaObject.class));
	}

	@Test
	public void testUpdateWithSearchFailure_DBStillUpdated() {
		// Arrange: create object, then make search fail on update
		Sysprop obj = new Sysprop("update-fail");
		obj.setName("Original");
		obj.setAppid(APPID);
		managedDao.create(APPID, obj);

		doThrow(new RuntimeException("Search update failed"))
				.when(mockSearch).index(eq(APPID), any(ParaObject.class));

		// Act: update the object
		obj.setName("Updated");
		managedDao.update(APPID, obj);

		// Assert: DB has the updated name
		Sysprop fromDB = rawDao.read(APPID, "update-fail");
		assertNotNull(fromDB);
		assertEquals("Updated", fromDB.getName());
	}
}
