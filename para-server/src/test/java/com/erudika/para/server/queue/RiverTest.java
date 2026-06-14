/*
 * Copyright 2013-2026 Erudika. http://erudika.com
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

import com.erudika.para.core.Sysprop;
import com.erudika.para.core.cache.Cache;
import com.erudika.para.core.persistence.DAO;
import com.erudika.para.core.queue.Queue;
import com.erudika.para.core.queue.River;
import com.erudika.para.core.search.Search;
import com.erudika.para.core.utils.Config;
import com.erudika.para.core.utils.CoreUtils;
import com.erudika.para.core.utils.ParaObjectUtils;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Regression tests for {@link River} after webhook extraction.
 * Verifies that River no longer processes webhook payloads.
 */
public class RiverTest {

	private static final String APPID = "test-app";
	private DAO dao;
	private Cache cache;
	private Search search;

	@BeforeEach
	public void setUp() {
		System.setProperty("para.queue.polling_interval_seconds", "1");
		System.setProperty("para.queue.polling_sleep_seconds", "1");
		dao = mock(DAO.class);
		cache = mock(Cache.class);
		search = mock(Search.class);
		CoreUtils.getInstance().setDao(dao);
		CoreUtils.getInstance().setCache(cache);
		CoreUtils.getInstance().setSearch(search);
		CoreUtils.getInstance().setQueue(mock(Queue.class));
	}

	@AfterEach
	public void tearDown() {
		System.clearProperty("para.queue.polling_interval_seconds");
		System.clearProperty("para.queue.polling_sleep_seconds");
	}

	private String buildWebhookPayload(String id) {
		Map<String, Object> data = new HashMap<>();
		data.put(Config._ID, id);
		data.put(Config._APPID, APPID);
		data.put(Config._TYPE, "webhookpayload");
		data.put("targetUrl", "https://example.com/hook");
		data.put("urlEncoded", false);
		data.put("repeatedDeliveryAttempts", 1);
		data.put("event", "create");
		data.put("payload", "{\"event\":\"create\",\"items\":[]}");
		data.put("signature", "test-sig");
		try {
			return ParaObjectUtils.getJsonWriterNoIdent().writeValueAsString(data);
		} catch (Exception e) {
			return "";
		}
	}

	private String buildSyspropPayload(String id) {
		Map<String, Object> data = new HashMap<>();
		data.put(Config._ID, id);
		data.put(Config._APPID, APPID);
		data.put(Config._TYPE, "sysprop");
		data.put("_create", "true");
		data.put("name", "test");
		try {
			return ParaObjectUtils.getJsonWriterNoIdent().writeValueAsString(data);
		} catch (Exception e) {
			return "";
		}
	}

	@Test
	public void testRun_ignoresWebhookPayloads() throws Exception {
		String webhookMsg = buildWebhookPayload("hook1");
		CountDownLatch done = new CountDownLatch(1);
		AtomicInteger processCount = new AtomicInteger(0);

		River river = new River() {
			private int callCount = 0;
			public List<String> pullMessages() {
				callCount++;
				if (callCount == 1) {
					processCount.incrementAndGet();
					return Arrays.asList(webhookMsg);
				}
				done.countDown();
				Thread.currentThread().interrupt();
				return Collections.emptyList();
			}
		};

		Thread t = new Thread(river);
		t.start();
		assertTrue(done.await(5, TimeUnit.SECONDS));
		t.interrupt();
		t.join(2000);

		// Key regression: River should NOT attempt to read or update any webhook objects
		verify(dao, never()).read(eq(APPID), eq("hook1"));
		verify(cache, never()).get(eq(APPID), anyString());
		verify(cache, never()).put(eq(APPID), anyString(), any());
	}

	@Test
	public void testRun_persistsCrudChanges() throws Exception {
		String syspropMsg = buildSyspropPayload("obj1");
		CountDownLatch done = new CountDownLatch(1);

		River river = new River() {
			private int callCount = 0;
			public List<String> pullMessages() {
				callCount++;
				if (callCount == 1) {
					return Arrays.asList(syspropMsg);
				}
				done.countDown();
				Thread.currentThread().interrupt();
				return Collections.emptyList();
			}
		};

		Thread t = new Thread(river);
		t.start();
		assertTrue(done.await(5, TimeUnit.SECONDS));
		t.interrupt();
		t.join(2000);

		// River should still persist CRUD changes via DAO
		verify(dao).createAll(any(List.class));
	}
}
