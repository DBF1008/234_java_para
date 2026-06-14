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

import com.erudika.para.core.Sysprop;
import com.erudika.para.core.Webhook;
import com.erudika.para.core.cache.MockCache;
import com.erudika.para.core.persistence.MockDAO;
import com.erudika.para.core.queue.MockQueue;
import com.erudika.para.core.queue.WebhookDispatcher;
import com.erudika.para.core.search.Search;
import com.erudika.para.core.utils.CoreUtils;
import com.erudika.para.core.utils.Para;
import com.erudika.para.core.utils.ParaObjectUtils;
import com.erudika.para.core.utils.Utils;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.mock;

/**
 * Tests the webhook "worker" side: {@link WebhookDispatcher} delivers a payload pulled from the
 * queue to its target URL - exactly the requested number of times - and disables a webhook after
 * too many failed deliveries.
 * @author Alex Bogdanovski [alex@erudika.com]
 */
public class WebhookDeliveryTest {

	private String appid;
	private MockDAO dao;
	private MockCache cache;
	private WebhookTestServer server;

	@BeforeEach
	public void setUp() throws Exception {
		appid = "app_" + Utils.getNewId();
		dao = new MockDAO();
		cache = new MockCache();
		CoreUtils.getInstance().setDao(dao);
		CoreUtils.getInstance().setCache(cache);
		CoreUtils.getInstance().setSearch(mock(Search.class));
		CoreUtils.getInstance().setQueue(new MockQueue());
		server = new WebhookTestServer("/hook");
	}

	@AfterEach
	public void tearDown() {
		server.stop();
		System.clearProperty("para.max_failed_webhook_attempts");
	}

	private Webhook webhook(String secret, int repeat, boolean urlEncoded) {
		Webhook wh = new Webhook(server.url("/hook"));
		wh.setId("wh_" + Utils.getNewId());
		wh.setAppid(appid);
		wh.setSecret(secret);
		wh.setActive(true);
		wh.setUrlEncoded(urlEncoded);
		wh.setRepeatedDeliveryAttempts(repeat);
		return wh;
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> payloadFor(Webhook wh) throws Exception {
		Sysprop obj = new Sysprop("o");
		obj.setAppid(appid);
		return ParaObjectUtils.getJsonReader(Map.class).readValue(wh.buildPayloadAsJSON("create", obj));
	}

	@Test
	public void testDeliveredExactlyOnce() throws Exception {
		Webhook wh = webhook("topsecret", 1, false);
		Map<String, Object> parsed = payloadFor(wh);

		WebhookDispatcher.deliver(appid, wh.getId(), parsed);

		assertEquals(1, server.hitCount());
		WebhookTestServer.RequestRecord rec = server.requests().get(0);
		assertEquals(parsed.get("signature"), rec.getSignature());
		assertEquals("create", rec.getEvent());
		assertEquals("application/json", rec.getContentType());
		assertEquals(parsed.get("payload"), rec.getBody());
	}

	@Test
	public void testDeliveredExactlyNTimes() throws Exception {
		Webhook wh = webhook("s", 3, false);

		WebhookDispatcher.deliver(appid, wh.getId(), payloadFor(wh));

		assertEquals(3, server.hitCount(), "the payload should be delivered exactly repeatedDeliveryAttempts times");
	}

	@Test
	public void testUrlEncodedDelivery() throws Exception {
		Webhook wh = webhook("s", 1, true);

		WebhookDispatcher.deliver(appid, wh.getId(), payloadFor(wh));

		assertEquals(1, server.hitCount());
		WebhookTestServer.RequestRecord rec = server.requests().get(0);
		assertEquals("application/x-www-form-urlencoded", rec.getContentType());
		assertTrue(rec.getBody().startsWith("payload="), "url-encoded body must be form encoded");
	}

	@Test
	public void testFailureCountIncrementsOnNon2xx() throws Exception {
		System.setProperty("para.max_failed_webhook_attempts", "5");
		server.setResponseCode(500);
		Webhook wh = webhook("s", 1, false);
		dao.create(appid, wh);

		WebhookDispatcher.deliver(appid, wh.getId(), payloadFor(wh));

		assertEquals(1, server.hitCount());
		String countId = "failed_webhook_count" + Para.getConfig().separator() + wh.getId();
		Integer count = cache.get(appid, countId);
		assertEquals(Integer.valueOf(1), count);
		Webhook stored = dao.read(appid, wh.getId());
		assertTrue(stored.getActive(), "webhook should remain active below the threshold");
	}

	@Test
	public void testAutoDisableAfterMaxFailures() throws Exception {
		System.setProperty("para.max_failed_webhook_attempts", "2");
		server.setResponseCode(500);
		Webhook wh = webhook("s", 2, false); // two attempts -> two failures -> disabled
		dao.create(appid, wh);

		WebhookDispatcher.deliver(appid, wh.getId(), payloadFor(wh));

		assertEquals(2, server.hitCount());
		Webhook stored = dao.read(appid, wh.getId());
		assertFalse(stored.getActive(), "webhook should be disabled after reaching max failures");
		assertTrue(stored.getTooManyFailures());
		String countId = "failed_webhook_count" + Para.getConfig().separator() + wh.getId();
		assertNull(cache.get(appid, countId), "failure counter should be cleared once the webhook is disabled");
	}
}
