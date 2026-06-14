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
import com.erudika.para.core.Webhook;
import com.erudika.para.core.cache.MockCache;
import com.erudika.para.core.persistence.DAO;
import com.erudika.para.core.persistence.MockDAO;
import com.erudika.para.core.queue.MockQueue;
import com.erudika.para.core.queue.Queue;
import com.erudika.para.core.search.Search;
import com.erudika.para.core.utils.CoreUtils;
import com.erudika.para.core.utils.Pager;
import com.erudika.para.core.utils.Para;
import com.erudika.para.core.utils.ParaObjectUtils;
import com.erudika.para.core.utils.Utils;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Tests the webhook "producer" side: an API node detects writes and enqueues webhook payloads,
 * without delivering them. Exercises {@link Webhook#sendEventPayloadToQueue} and the
 * {@link com.erudika.para.core.listeners.WebhookIOListener} entry point.
 * @author Alex Bogdanovski [alex@erudika.com]
 */
public class WebhookProducerTest {

	private String appid;
	private Queue queue;
	private Search search;

	@BeforeEach
	public void setUp() {
		System.setProperty("para.executor_service_enabled", "false"); // run the listener inline
		System.setProperty("para.webhooks_enabled", "true");
		appid = "app_" + Utils.getNewId();
		queue = new MockQueue();
		search = mock(Search.class);
		CoreUtils.getInstance().setQueue(queue);
		CoreUtils.getInstance().setSearch(search);
		CoreUtils.getInstance().setDao(new MockDAO());
		CoreUtils.getInstance().setCache(new MockCache());
	}

	@AfterEach
	public void tearDown() {
		System.clearProperty("para.executor_service_enabled");
		System.clearProperty("para.webhooks_enabled");
	}

	private Webhook matchingWebhook(String secret) {
		Webhook wh = new Webhook("https://example.com/hook");
		wh.setId("wh_" + Utils.getNewId());
		wh.setAppid(appid);
		wh.setSecret(secret);
		wh.setActive(true);
		wh.setCreate(true);
		return wh;
	}

	// return the webhook once, then an empty page, so the producer's do/while loop terminates
	private void stubSearchReturns(Webhook wh) {
		when(search.<Webhook>findTerms(anyString(), anyString(), any(), anyBoolean(), any(Pager.class))).
				thenReturn(Arrays.asList(wh)).thenReturn(Collections.emptyList());
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> pullParsed() throws Exception {
		String msg = queue.pull();
		assertFalse(msg.isEmpty(), "expected a payload to be enqueued");
		return ParaObjectUtils.getJsonReader(Map.class).readValue(msg);
	}

	@Test
	public void testEnqueuesWellFormedPayloadForMatchingWebhook() throws Exception {
		Webhook wh = matchingWebhook("topsecret");
		stubSearchReturns(wh);
		Sysprop obj = new Sysprop("obj1");
		obj.setAppid(appid);
		obj.setName("hello");

		Webhook.sendEventPayloadToQueue(appid, "create", true, obj);

		Map<String, Object> parsed = pullParsed();
		assertEquals("webhookpayload", parsed.get("type"));
		assertEquals(appid, parsed.get("appid"));
		assertEquals(wh.getTargetUrl(), parsed.get("targetUrl"));
		assertEquals("create", parsed.get("event"));
		String payload = (String) parsed.get("payload");
		assertEquals(Utils.hmacSHA256(payload, "topsecret"), parsed.get("signature"));
		assertTrue(queue.pull().isEmpty(), "exactly one payload should have been enqueued");
	}

	@Test
	public void testListenerEnqueuesOnWrite() throws Exception {
		stubSearchReturns(matchingWebhook("s"));
		Sysprop obj = new Sysprop("obj2");
		obj.setAppid(appid);
		Method create = DAO.class.getMethod("create", String.class, ParaObject.class);

		new com.erudika.para.core.listeners.WebhookIOListener().
				onPostInvoke(create, new Object[]{appid, obj}, "obj2");

		Map<String, Object> parsed = pullParsed();
		assertEquals("webhookpayload", parsed.get("type"));
		assertEquals("create", parsed.get("event"));
		assertTrue(queue.pull().isEmpty());
	}

	@Test
	public void testTypeFilterExcludesNonMatching() {
		Webhook wh = matchingWebhook("s");
		wh.setTypeFilter("user"); // payload is a sysprop -> excluded
		stubSearchReturns(wh);

		Webhook.sendEventPayloadToQueue(appid, "create", true, new Sysprop("o"));

		assertTrue(queue.pull().isEmpty(), "type filter should have excluded the payload");
	}

	@Test
	public void testPropertyFilterIncludesMatching() {
		Webhook wh = matchingWebhook("s");
		wh.setPropertyFilter("name:John");
		stubSearchReturns(wh);
		Sysprop obj = new Sysprop("o");
		obj.setAppid(appid);
		obj.setName("John");

		Webhook.sendEventPayloadToQueue(appid, "create", true, obj);

		assertFalse(queue.pull().isEmpty(), "matching property filter should enqueue the payload");
	}

	@Test
	public void testPropertyFilterExcludesNonMatching() {
		Webhook wh = matchingWebhook("s");
		wh.setPropertyFilter("name:John");
		stubSearchReturns(wh);
		Sysprop obj = new Sysprop("o");
		obj.setAppid(appid);
		obj.setName("Jane");

		Webhook.sendEventPayloadToQueue(appid, "create", true, obj);

		assertTrue(queue.pull().isEmpty(), "non-matching property filter should exclude the payload");
	}

	@Test
	public void testWebhookObjectOperationsAreSkipped() throws Exception {
		Method create = DAO.class.getMethod("create", String.class, ParaObject.class);

		new com.erudika.para.core.listeners.WebhookIOListener().
				onPostInvoke(create, new Object[]{appid, new Webhook("https://x.com")}, "id");

		assertTrue(queue.pull().isEmpty(), "operations on Webhook objects must not trigger webhooks");
		verifyNoInteractions(search);
	}

	@Test
	public void testReadOperationsAreSkipped() throws Exception {
		Method read = DAO.class.getMethod("read", String.class, String.class);

		new com.erudika.para.core.listeners.WebhookIOListener().
				onPostInvoke(read, new Object[]{appid, "someId"}, new Sysprop("someId"));

		assertTrue(queue.pull().isEmpty(), "read operations must not trigger webhooks");
		verifyNoInteractions(search);
	}
}
