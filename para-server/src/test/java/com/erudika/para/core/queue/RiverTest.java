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
package com.erudika.para.core.queue;

import com.erudika.para.core.ParaObject;
import com.erudika.para.core.Sysprop;
import com.erudika.para.core.cache.Cache;
import com.erudika.para.core.cache.MockCache;
import com.erudika.para.core.persistence.DAO;
import com.erudika.para.core.persistence.MockDAO;
import com.erudika.para.core.search.MockSearch;
import com.erudika.para.core.search.Search;
import com.erudika.para.core.utils.CoreUtils;
import com.erudika.para.core.utils.Para;
import com.erudika.para.server.persistence.ManagedDAO;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for the queue import and search-visibility chain in {@link River}.
 * Covers batch import (create/delete via the managed DAO), the {@code index_all_op} reindex path,
 * retry of objects missing from the database, idempotency under redelivery and per-app isolation.
 * @author Alex Bogdanovski [alex@erudika.com]
 */
public class RiverTest {

	/**
	 * Search test double that records how many times each object id was indexed/unindexed,
	 * so duplicate side effects can be asserted. All other operations inherit {@link MockSearch}.
	 */
	static final class RecordingSearch extends MockSearch {
		private final Map<String, Integer> indexCounts = new ConcurrentHashMap<>();
		private final Map<String, Integer> unindexCounts = new ConcurrentHashMap<>();

		@Override
		public <P extends ParaObject> void indexAll(String appid, List<P> objects) {
			if (objects != null) {
				objects.forEach(o -> {
					if (o != null) {
						indexCounts.merge(o.getId(), 1, Integer::sum);
					}
				});
			}
		}

		@Override
		public <P extends ParaObject> void unindexAll(String appid, List<P> objects) {
			if (objects != null) {
				objects.forEach(o -> {
					if (o != null) {
						unindexCounts.merge(o.getId(), 1, Integer::sum);
					}
				});
			}
		}

		int indexed(String id) {
			return indexCounts.getOrDefault(id, 0);
		}

		int unindexed(String id) {
			return unindexCounts.getOrDefault(id, 0);
		}
	}

	/**
	 * A concrete {@link River} whose message source is driven directly by the test via the
	 * package-private import seams; {@code pullMessages()} is unused.
	 */
	static final class TestRiver extends River {
		@Override
		public List<String> pullMessages() {
			return Collections.emptyList();
		}
	}

	private DAO prevDao;
	private Search prevSearch;
	private Cache prevCache;
	private String prevSearchEnabled;
	private String prevCacheEnabled;

	private MockDAO db;
	private MockCache cache;
	private RecordingSearch search;
	private TestRiver river;
	private String root;

	@BeforeEach
	void setUp() {
		prevDao = CoreUtils.getInstance().getDao();
		prevSearch = CoreUtils.getInstance().getSearch();
		prevCache = CoreUtils.getInstance().getCache();
		prevSearchEnabled = System.getProperty("para.search_enabled");
		prevCacheEnabled = System.getProperty("para.cache_enabled");
		System.setProperty("para.search_enabled", "true");
		System.setProperty("para.cache_enabled", "true");

		db = new MockDAO();
		cache = new MockCache();
		search = new RecordingSearch();
		// the managed DAO is the real coordinator: it keeps DB, index and cache consistent on writes
		CoreUtils.getInstance().setDao(new ManagedDAO(db));
		CoreUtils.getInstance().setSearch(search);
		CoreUtils.getInstance().setCache(cache);
		river = new TestRiver();
		root = Para.getConfig().getRootAppIdentifier();
	}

	@AfterEach
	void tearDown() {
		CoreUtils.getInstance().setDao(prevDao);
		CoreUtils.getInstance().setSearch(prevSearch);
		CoreUtils.getInstance().setCache(prevCache);
		restore("para.search_enabled", prevSearchEnabled);
		restore("para.cache_enabled", prevCacheEnabled);
	}

	private static void restore(String key, String value) {
		if (value == null) {
			System.clearProperty(key);
		} else {
			System.setProperty(key, value);
		}
	}

	private static Sysprop sysprop(String id) {
		Sysprop s = new Sysprop(id);
		s.setName(id);
		return s;
	}

	// T1 - a batch import writes to the DB and makes objects visible in search and cache in one step.
	@Test
	void batchImportMakesObjectsVisibleAndConsistent() {
		List<ParaObject> create = new LinkedList<>();
		create.add(sysprop("river_t1_a"));
		create.add(sysprop("river_t1_b"));
		river.persistChanges(create, new LinkedList<>(), new LinkedList<>());

		// written to the database of record
		assertNotNull(db.read(root, "river_t1_a"));
		assertNotNull(db.read(root, "river_t1_b"));
		// visible in the search index (indexed exactly once each)
		assertEquals(1, search.indexed("river_t1_a"));
		assertEquals(1, search.indexed("river_t1_b"));
		// reconciled into the cache
		assertNotNull(cache.get(root, "river_t1_a"));
		assertNotNull(cache.get(root, "river_t1_b"));
	}

	// T2 - index_all_op indexes present objects and retries the ones still missing from the DB.
	@Test
	void indexAllRetriesObjectsMissingFromDatabase() {
		String app = "river_t2";
		db.create(app, sysprop("t2_present")); // in DB but not yet indexed

		river.indexAll(app, Arrays.asList("t2_present", "t2_missing"));
		assertEquals(1, search.indexed("t2_present"));
		assertEquals(0, search.indexed("t2_missing"));
		assertTrue(river.hasPending(app));
		assertEquals(1, river.pendingCount(app));

		// the object becomes available, a single retry round drains the pending set
		db.create(app, sysprop("t2_missing"));
		assertTrue(river.runIndexRetryRound(app));
		assertEquals(1, search.indexed("t2_missing"));
		assertEquals(0, river.pendingCount(app));
	}

	// T3 - redelivered messages and repeated retry rounds never produce duplicate index side effects.
	@Test
	void retryIsIdempotentAcrossRedeliveryAndRounds() {
		String app = "river_t3";

		// redelivered index_all_op while the object is still missing -> no duplicate pending entry, nothing indexed
		river.indexAll(app, Arrays.asList("t3_missing"));
		river.indexAll(app, Arrays.asList("t3_missing"));
		assertEquals(0, search.indexed("t3_missing"));
		assertEquals(1, river.pendingCount(app));

		db.create(app, sysprop("t3_missing"));
		assertTrue(river.runIndexRetryRound(app));
		assertEquals(1, search.indexed("t3_missing"));

		// further rounds must be no-ops (the object was already drained)
		river.runIndexRetryRound(app);
		river.runIndexRetryRound(app);
		assertEquals(1, search.indexed("t3_missing"));
	}

	// T4 - pending/retry state is isolated per app; draining one app never touches another.
	@Test
	void retryStateIsIsolatedPerApp() {
		String appA = "river_t4a";
		String appB = "river_t4b";
		river.indexAll(appA, Arrays.asList("t4_a")); // missing in A
		river.indexAll(appB, Arrays.asList("t4_b")); // missing in B
		assertEquals(1, river.pendingCount(appA));
		assertEquals(1, river.pendingCount(appB));

		// make B's object available and drain B only
		db.create(appB, sysprop("t4_b"));
		assertTrue(river.runIndexRetryRound(appB));

		assertEquals(1, search.indexed("t4_b"));
		assertEquals(0, search.indexed("t4_a"));
		assertEquals(0, river.pendingCount(appB));
		assertEquals(1, river.pendingCount(appA)); // untouched by B's retry
	}

	// T5 - only one retry worker is scheduled per app; redelivery cannot spawn duplicates.
	@Test
	void retrySlotCoalescesConcurrentWorkers() {
		String app = "river_t5";
		assertTrue(river.acquireRetrySlot(app));
		assertFalse(river.acquireRetrySlot(app)); // already held -> no second worker
		river.releaseRetrySlot(app);
		assertTrue(river.acquireRetrySlot(app)); // released -> can acquire again
		river.releaseRetrySlot(app);
	}

	// T6 - a delete message removes the object from the DB, the index and the cache together.
	@Test
	void deleteMessageRemovesFromDbIndexAndCache() {
		Para.getDAO().createAll(new LinkedList<>(Arrays.asList(sysprop("river_t6"))));
		assertNotNull(db.read(root, "river_t6"));
		assertEquals(1, search.indexed("river_t6"));
		assertNotNull(cache.get(root, "river_t6"));

		String msg = "{\"appid\":\"" + root + "\",\"type\":\"sysprop\",\"id\":\"river_t6\",\"_delete\":\"true\"}";
		assertEquals(1, river.processMessages(Arrays.asList(msg)));

		assertNull(db.read(root, "river_t6"));
		assertEquals(1, search.unindexed("river_t6"));
		assertNull(cache.get(root, "river_t6"));
	}

	// T7 - the search and cache feature flags are respected.
	@Test
	void searchAndCacheGatesAreRespected() {
		// search disabled: index_all_op is a no-op at the entry point
		System.setProperty("para.search_enabled", "false");
		db.create("river_t7", sysprop("t7_present"));
		Map<String, Object> payload = new HashMap<>();
		payload.put("payload", Arrays.asList("t7_present"));
		assertEquals(0, river.processIndexPayload("river_t7", "index_all_op", payload));
		assertEquals(0, search.indexed("t7_present"));
		System.setProperty("para.search_enabled", "true");

		// cache disabled: a created object lands in the DB and index but is not cached
		System.setProperty("para.cache_enabled", "false");
		Para.getDAO().createAll(new LinkedList<>(Arrays.asList(sysprop("t7_uncached"))));
		assertNotNull(db.read(root, "t7_uncached"));
		assertEquals(1, search.indexed("t7_uncached"));
		assertNull(cache.get(root, "t7_uncached"));
	}

}
