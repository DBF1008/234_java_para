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
package com.erudika.para.server.webhooks;

import com.erudika.para.core.cache.Cache;
import com.erudika.para.core.persistence.DAO;
import com.erudika.para.core.queue.Queue;
import com.erudika.para.core.search.Search;
import com.erudika.para.core.utils.Config;
import com.erudika.para.core.utils.CoreUtils;
import com.erudika.para.core.utils.Para;
import com.erudika.para.core.utils.ParaObjectUtils;
import com.erudika.para.core.webhooks.WebhookDeliveryService;
import com.erudika.para.core.webhooks.WebhookDeliveryWorker;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link WebhookDeliveryWorker}.
 */
public class WebhookDeliveryWorkerTest {

	private static final String APPID = "test-app";
	private WebhookDeliveryService mockService;
	private Cache cache;
	private DAO dao;

	@BeforeEach
	public void setUp() {
		System.setProperty("para.webhooks_enabled", "true");
		System.setProperty("para.queue.polling_interval_seconds", "1");
		System.setProperty("para.queue.polling_sleep_seconds", "1");
		cache = mock(Cache.class);
		dao = mock(DAO.class);
		CoreUtils.getInstance().setCache(cache);
		CoreUtils.getInstance().setDao(dao);
		CoreUtils.getInstance().setSearch(mock(Search.class));
		CoreUtils.getInstance().setQueue(mock(Queue.class));
		mockService = mock(WebhookDeliveryService.class);
	}

	@AfterEach
	public void tearDown() {
		System.clearProperty("para.webhooks_enabled");
		System.clearProperty("para.queue.polling_interval_seconds");
		System.clearProperty("para.queue.polling_sleep_seconds");
	}

	private String buildWebhookPayload(String id, String appid) {
		Map<String, Object> data = new HashMap<>();
		data.put(Config._ID, id);
		data.put(Config._APPID, appid);
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

	private String buildNonWebhookPayload(String id, String appid) {
		Map<String, Object> data = new HashMap<>();
		data.put(Config._ID, id);
		data.put(Config._APPID, appid);
		data.put(Config._TYPE, "sysprop");
		data.put("_create", "true");
		try {
			return ParaObjectUtils.getJsonWriterNoIdent().writeValueAsString(data);
		} catch (Exception e) {
			return "";
		}
	}

	@Test
	public void testRun_processesWebhookPayloads() throws Exception {
		String webhookMsg = buildWebhookPayload("hook1", APPID);
		List<List<String>> pullBatches = new ArrayList<>();
		pullBatches.add(Arrays.asList(webhookMsg));
		pullBatches.add(Collections.emptyList()); // empty to trigger idle

		AtomicBoolean firstPull = new AtomicBoolean(true);
		CountDownLatch latch = new CountDownLatch(1);

		when(mockService.deliverPayload(eq(APPID), eq("hook1"), any(Map.class))).thenAnswer(inv -> {
			latch.countDown();
			return 1;
		});

		WebhookDeliveryWorker worker = new WebhookDeliveryWorker(mockService) {
			private int callCount = 0;
			public List<String> pullMessages() {
				if (callCount < pullBatches.size()) {
					List<String> batch = pullBatches.get(callCount++);
					if (!batch.isEmpty()) {
						return batch;
					}
				}
				Thread.currentThread().interrupt();
				return Collections.emptyList();
			}
		};

		Thread t = new Thread(worker);
		t.start();
		assertTrue(latch.await(5, TimeUnit.SECONDS), "Webhook delivery should have been called");
		t.interrupt();
		t.join(2000);

		verify(mockService, atLeastOnce()).deliverPayload(eq(APPID), eq("hook1"), any(Map.class));
	}

	@Test
	public void testRun_ignoresNonWebhookMessages() throws Exception {
		String nonWebhookMsg = buildNonWebhookPayload("obj1", APPID);
		CountDownLatch done = new CountDownLatch(1);

		WebhookDeliveryWorker worker = new WebhookDeliveryWorker(mockService) {
			private int callCount = 0;
			public List<String> pullMessages() {
				callCount++;
				if (callCount == 1) {
					return Arrays.asList(nonWebhookMsg);
				}
				done.countDown();
				Thread.currentThread().interrupt();
				return Collections.emptyList();
			}
		};

		Thread t = new Thread(worker);
		t.start();
		assertTrue(done.await(5, TimeUnit.SECONDS));
		t.interrupt();
		t.join(2000);

		// Should never call deliverPayload for non-webhook messages
		verify(mockService, never()).deliverPayload(anyString(), anyString(), any(Map.class));
	}
}
