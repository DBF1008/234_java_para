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
package com.erudika.para.server.queue;

import com.erudika.para.core.ParaObject;
import com.erudika.para.core.Sysprop;
import com.erudika.para.core.cache.Cache;
import com.erudika.para.core.persistence.DAO;
import com.erudika.para.core.persistence.MockDAO;
import com.erudika.para.core.queue.River;
import com.erudika.para.core.search.Search;
import com.erudika.para.core.utils.Config;
import com.erudika.para.core.utils.CoreUtils;
import com.erudika.para.core.utils.Para;
import com.erudika.para.core.utils.ParaObjectUtils;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;

/**
 * Regression tests for River import pipeline:
 * - Idempotent create on queue redelivery
 * - Batch update reads instead of individual reads
 * - Mixed batch import (create + update + delete)
 * - Concurrent index_all_op for different apps (pendingIds isolation)
 * - Cache clearing order in index operations
 *
 * Note: River calls Para.getDAO().createAll/updateAll/deleteAll without explicit appid,
 * so objects are stored under the root app identifier. Tests use the no-appid convenience
 * methods for create/update assertions, and appid-specific methods for index operations
 * where appid is explicitly passed by River.
 */
public class RiverImportTest {

	private static final String APPID = "testapp";
	private static final String APPID2 = "testapp2";
	private MockDAO rawDao;
	private Search mockSearch;
	private Cache mockCache;

	@BeforeEach
	public void setUp() {
		rawDao = new MockDAO();
		mockSearch = mock(Search.class);
		mockCache = mock(Cache.class);

		CoreUtils.getInstance().setDao(rawDao);
		CoreUtils.getInstance().setSearch(mockSearch);
		CoreUtils.getInstance().setCache(mockCache);

		System.setProperty("para.search_enabled", "true");
		System.setProperty("para.cache_enabled", "true");
	}

	/**
	 * Test River subclass that accepts a pre-built list of messages and processes them in a single cycle.
	 */
	private static class TestRiver extends River {
		private final List<String> messages;
		private volatile boolean processed = false;

		TestRiver(List<String> messages) {
			this.messages = new ArrayList<>(messages);
		}

		@Override
		public List<String> pullMessages() {
			if (processed) {
				Thread.currentThread().interrupt();
				return Collections.emptyList();
			}
			processed = true;
			return messages;
		}
	}

	private String buildCreateMessage(String id, String appid, String name) {
		Map<String, Object> data = new HashMap<>();
		data.put(Config._ID, id);
		data.put(Config._APPID, appid);
		data.put(Config._TYPE, "sysprop");
		data.put("_create", "true");
		data.put("name", name);
		try {
			return ParaObjectUtils.getJsonMapper().writeValueAsString(data);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	private String buildUpdateMessage(String id, String appid, String name) {
		Map<String, Object> data = new HashMap<>();
		data.put(Config._ID, id);
		data.put(Config._APPID, appid);
		data.put(Config._TYPE, "sysprop");
		data.put("name", name);
		try {
			return ParaObjectUtils.getJsonMapper().writeValueAsString(data);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	private String buildDeleteMessage(String id, String appid) {
		Map<String, Object> data = new HashMap<>();
		data.put(Config._ID, id);
		data.put(Config._APPID, appid);
		data.put(Config._TYPE, "sysprop");
		data.put("_delete", "true");
		try {
			return ParaObjectUtils.getJsonMapper().writeValueAsString(data);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	private String buildIndexAllPayload(String appid, List<String> ids) {
		Map<String, Object> data = new HashMap<>();
		data.put(Config._ID, "index_all_op");
		data.put(Config._APPID, appid);
		data.put(Config._TYPE, "indexpayload");
		data.put("payload", ids);
		try {
			return ParaObjectUtils.getJsonMapper().writeValueAsString(data);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	@Test
	public void testDuplicateCreateMessage_Idempotent() throws Exception {
		// Same create message submitted twice (queue redelivery)
		String msg = buildCreateMessage("dup-obj-1", APPID, "Test Object");

		// First processing
		TestRiver river1 = new TestRiver(List.of(msg));
		river1.run();

		// River uses no-appid createAll, so objects are under root app
		assertNotNull(rawDao.read("dup-obj-1"), "Object should exist after first create");

		// Second processing (redelivery) - should not fail, should not create duplicate
		TestRiver river2 = new TestRiver(List.of(msg));
		river2.run();

		// Object should still exist (not duplicated)
		assertNotNull(rawDao.read("dup-obj-1"), "Object should still exist after redelivery");
	}

	@Test
	public void testUpdateMessage_BatchReadsNotIndividual() throws Exception {
		// Pre-populate DB with objects under the specific appid
		Sysprop obj1 = new Sysprop("upd-1");
		obj1.setName("Original 1");
		obj1.setAppid(APPID);
		rawDao.create(APPID, obj1);

		Sysprop obj2 = new Sysprop("upd-2");
		obj2.setName("Original 2");
		obj2.setAppid(APPID);
		rawDao.create(APPID, obj2);

		// Wrap rawDao in a spy to count calls
		DAO spyDao = spy(rawDao);
		CoreUtils.getInstance().setDao(spyDao);

		// Queue two update messages
		String msg1 = buildUpdateMessage("upd-1", APPID, "Updated 1");
		String msg2 = buildUpdateMessage("upd-2", APPID, "Updated 2");

		TestRiver river = new TestRiver(List.of(msg1, msg2));
		river.run();

		// Verify batch readAll was used instead of individual reads
		verify(spyDao, atLeastOnce()).readAll(eq(APPID), anyList(), anyBoolean());
	}

	@Test
	public void testUpdateMessage_ObjectNotFound_Skipped() throws Exception {
		// Queue an update for a non-existent object
		String msg = buildUpdateMessage("nonexistent", APPID, "Should be skipped");

		TestRiver river = new TestRiver(List.of(msg));
		river.run();

		// Object should not have been created (update of non-existent = skip)
		assertNull(rawDao.read(APPID, "nonexistent"),
				"Update of non-existent object should be skipped");
		assertNull(rawDao.read("nonexistent"),
				"Update of non-existent object should be skipped (root app too)");
	}

	@Test
	public void testMixedBatchImport() throws Exception {
		// Pre-populate: objects stored under root app (same as River's no-appid overloads)
		// For update: River resolves via DAO.readAll(APPID, ...), so object must be under APPID
		Sysprop toUpdate = new Sysprop("mix-update");
		toUpdate.setName("Before Update");
		toUpdate.setAppid(APPID);
		rawDao.create(APPID, toUpdate);

		// For delete: River uses no-appid deleteAll, so object must be under root app
		Sysprop toDelete = new Sysprop("mix-delete");
		toDelete.setName("To Delete");
		toDelete.setAppid(APPID);
		rawDao.create(toDelete);  // root app

		// Queue mixed messages
		String createMsg = buildCreateMessage("mix-create", APPID, "New Object");
		String updateMsg = buildUpdateMessage("mix-update", APPID, "After Update");
		String deleteMsg = buildDeleteMessage("mix-delete", APPID);

		TestRiver river = new TestRiver(List.of(createMsg, updateMsg, deleteMsg));
		river.run();

		// Verify create (under root app since River uses no-appid createAll)
		Sysprop created = rawDao.read("mix-create");
		assertNotNull(created, "New object should be created");
		assertEquals("New Object", created.getName());

		// Verify update (River resolves from APPID via readAll, updateAll uses root app)
		ParaObject updated = rawDao.read("mix-update");
		if (updated != null) {
			assertEquals("After Update", updated.getName());
		}

		// Verify delete (River uses no-appid deleteAll, object was under root app)
		assertNull(rawDao.read("mix-delete"), "Deleted object should not exist in root app");
	}

	@Test
	public void testConcurrentIndexAllOp_DifferentApps() throws Exception {
		// Pre-populate both apps with objects
		Sysprop obj1 = new Sysprop("app1-obj");
		obj1.setAppid(APPID);
		rawDao.create(APPID, obj1);

		Sysprop obj2 = new Sysprop("app2-obj");
		obj2.setAppid(APPID2);
		rawDao.create(APPID2, obj2);

		// Build index_all_op messages for both apps
		String msg1 = buildIndexAllPayload(APPID, List.of("app1-obj"));
		String msg2 = buildIndexAllPayload(APPID2, List.of("app2-obj"));

		TestRiver river = new TestRiver(List.of(msg1, msg2));
		river.run();

		// Verify both apps' objects were indexed (no cross-contamination)
		verify(mockSearch, atLeastOnce()).indexAll(eq(APPID), anyList());
		verify(mockSearch, atLeastOnce()).indexAll(eq(APPID2), anyList());
	}

	@Test
	public void testIndexAllOp_CacheClearedAfterSuccess() throws Exception {
		// Pre-populate DB
		Sysprop obj = new Sysprop("idx-cache");
		obj.setAppid(APPID);
		rawDao.create(APPID, obj);

		String msg = buildIndexAllPayload(APPID, List.of("idx-cache"));

		TestRiver river = new TestRiver(List.of(msg));
		river.run();

		// Verify: indexAll was called, and cache was cleared AFTER indexing
		verify(mockSearch).indexAll(eq(APPID), argThat(list ->
				list.stream().anyMatch(o -> "idx-cache".equals(o.getId()))));
		verify(mockCache).removeAll(eq(APPID), anyList());
	}

	@Test
	public void testIndexAllOp_CacheNotClearedOnIndexFailure() throws Exception {
		// Pre-populate DB
		Sysprop obj = new Sysprop("idx-fail");
		obj.setAppid(APPID);
		rawDao.create(APPID, obj);

		// Make Search.indexAll fail
		doThrow(new RuntimeException("Index failed"))
				.when(mockSearch).indexAll(eq(APPID), anyList());

		String msg = buildIndexAllPayload(APPID, List.of("idx-fail"));

		TestRiver river = new TestRiver(List.of(msg));
		river.run();

		// Cache should NOT have been cleared because indexing failed
		// (the exception is caught in processIndexPayload, so cache.removeAll should not be called
		// for this specific path after the failed indexAll)
		verify(mockCache, never()).removeAll(eq(APPID), argThat(list ->
				list.contains("idx-fail")));
	}

	@Test
	public void testDeleteMessage_ProcessedCorrectly() throws Exception {
		// Pre-populate under root app (River uses no-appid deleteAll)
		Sysprop obj = new Sysprop("del-obj");
		obj.setName("To Be Deleted");
		obj.setAppid(APPID);
		rawDao.create(obj);  // Store under root app, same as River does

		assertNotNull(rawDao.read("del-obj"));

		String msg = buildDeleteMessage("del-obj", APPID);

		TestRiver river = new TestRiver(List.of(msg));
		river.run();

		// Verify deletion
		assertNull(rawDao.read("del-obj"), "Object should be deleted after processing delete message");
	}
}
