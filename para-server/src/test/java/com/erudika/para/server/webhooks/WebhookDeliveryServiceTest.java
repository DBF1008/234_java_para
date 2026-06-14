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

import com.erudika.para.core.Webhook;
import com.erudika.para.core.cache.Cache;
import com.erudika.para.core.persistence.DAO;
import com.erudika.para.core.search.Search;
import com.erudika.para.core.utils.CoreUtils;
import com.erudika.para.core.utils.Para;
import com.erudika.para.core.webhooks.WebhookDeliveryService;
import java.util.HashMap;
import java.util.Map;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link WebhookDeliveryService}.
 */
public class WebhookDeliveryServiceTest {

	private static final String APPID = "test-app";
	private Cache cache;
	private DAO dao;
	private CloseableHttpClient mockHttpClient;
	private WebhookDeliveryService service;

	@BeforeEach
	public void setUp() {
		System.setProperty("para.webhooks_enabled", "true");
		System.setProperty("para.max_failed_webhook_attempts", "3");
		cache = mock(Cache.class);
		dao = mock(DAO.class);
		CoreUtils.getInstance().setCache(cache);
		CoreUtils.getInstance().setDao(dao);
		mockHttpClient = mock(CloseableHttpClient.class);
		service = new WebhookDeliveryService(() -> mockHttpClient);
	}

	@AfterEach
	public void tearDown() {
		System.clearProperty("para.webhooks_enabled");
		System.clearProperty("para.max_failed_webhook_attempts");
		CoreUtils.getInstance().setCache(mock(Cache.class));
		CoreUtils.getInstance().setDao(mock(DAO.class));
	}

	private Map<String, Object> createPayloadMap(String id, String targetUrl) {
		Map<String, Object> parsed = new HashMap<>();
		parsed.put("id", id);
		parsed.put("appid", APPID);
		parsed.put("type", "webhookpayload");
		parsed.put("targetUrl", targetUrl);
		parsed.put("urlEncoded", false);
		parsed.put("repeatedDeliveryAttempts", 1);
		parsed.put("event", "create");
		parsed.put("payload", "{\"event\":\"create\",\"items\":[]}");
		parsed.put("signature", "test-sig");
		return parsed;
	}

	@Test
	public void testDeliverPayload_skipsWhenWebhooksDisabled() {
		System.setProperty("para.webhooks_enabled", "false");
		Map<String, Object> parsed = createPayloadMap("hook1", "https://example.com/hook");

		int result = service.deliverPayload(APPID, "hook1", parsed);

		assertEquals(0, result);
	}

	@Test
	public void testDeliverPayload_skipsWhenNoTargetUrl() {
		Map<String, Object> parsed = createPayloadMap("hook1", "https://example.com/hook");
		parsed.remove("targetUrl");

		int result = service.deliverPayload(APPID, "hook1", parsed);

		assertEquals(0, result);
	}

	@Test
	public void testDeliverPayload_skipsWhenIdBlank() {
		Map<String, Object> parsed = createPayloadMap("hook1", "https://example.com/hook");

		int result = service.deliverPayload(APPID, "", parsed);

		assertEquals(0, result);
	}

	@Test
	public void testUpdateFailureCount_incrementsCache() {
		when(cache.get(eq(APPID), anyString())).thenReturn(0);

		service.updateFailureCount(APPID, "hook1");

		verify(cache).put(eq(APPID), anyString(), eq(1));
	}

	@Test
	public void testUpdateFailureCount_disablesWebhookAtThreshold() {
		// max_failed_webhook_attempts = 3, so threshold is count >= 2
		when(cache.get(eq(APPID), anyString())).thenReturn(2);

		Webhook hook = new Webhook("https://example.com/hook");
		hook.setId("hook1");
		hook.setActive(true);
		when(dao.read(eq(APPID), eq("hook1"))).thenReturn(hook);

		service.updateFailureCount(APPID, "hook1");

		verify(dao).update(eq(APPID), eq(hook));
		assertEquals(false, hook.getActive());
		assertEquals(true, hook.getTooManyFailures());
		verify(cache).remove(eq(APPID), anyString());
	}

	@Test
	public void testUpdateFailureCount_doesNothingWhenHookNotFound() {
		when(cache.get(eq(APPID), anyString())).thenReturn(10);
		when(dao.read(eq(APPID), eq("missing"))).thenReturn(null);

		service.updateFailureCount(APPID, "missing");

		verify(dao, never()).update(anyString(), any());
	}

	@Test
	public void testUpdateFailureCount_handlesNullCount() {
		when(cache.get(eq(APPID), anyString())).thenReturn(null);

		service.updateFailureCount(APPID, "hook1");

		verify(cache).put(eq(APPID), anyString(), eq(1));
	}

	@Test
	public void testCreateDefaultHttpClient() {
		CloseableHttpClient client = WebhookDeliveryService.createDefaultHttpClient();
		assertNotNull(client);
	}

	@Test
	public void testGetHttpClient() {
		assertNotNull(service.getHttpClient());
		assertEquals(mockHttpClient, service.getHttpClient());
	}
}
