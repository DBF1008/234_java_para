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

import com.erudika.para.core.App;
import com.erudika.para.core.ParaObject;
import com.erudika.para.core.Sysprop;
import com.erudika.para.core.Webhook;
import com.erudika.para.core.cache.Cache;
import com.erudika.para.core.persistence.DAO;
import com.erudika.para.core.queue.Queue;
import com.erudika.para.core.search.Search;
import com.erudika.para.core.utils.Config;
import com.erudika.para.core.utils.CoreUtils;
import com.erudika.para.core.utils.Pager;
import com.erudika.para.core.utils.Para;
import com.erudika.para.core.utils.ParaObjectUtils;
import com.erudika.para.core.webhooks.WebhookEventDispatcher;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link WebhookEventDispatcher}.
 */
public class WebhookEventDispatcherTest {

	private static final String APPID = "test-app";
	private Queue queue;
	private Search search;
	private DAO dao;

	@BeforeEach
	public void setUp() {
		queue = mock(Queue.class);
		search = mock(Search.class);
		dao = mock(DAO.class);
		CoreUtils.getInstance().setQueue(queue);
		CoreUtils.getInstance().setSearch(search);
		CoreUtils.getInstance().setDao(dao);
	}

	@AfterEach
	public void tearDown() {
		CoreUtils.getInstance().setQueue(new com.erudika.para.core.queue.MockQueue());
		CoreUtils.getInstance().setSearch(mock(Search.class));
		CoreUtils.getInstance().setDao(mock(DAO.class));
	}

	private Webhook createWebhook(String id, String targetUrl, String secret) {
		Webhook w = new Webhook(targetUrl);
		w.setId(id);
		w.setAppid(APPID);
		w.setSecret(secret);
		w.setActive(true);
		return w;
	}

	// --- buildPayload tests ---

	@Test
	public void testBuildPayload_producesValidJson() throws Exception {
		Webhook w = createWebhook("hook1", "https://example.com/hook", "mysecret");
		w.setUrlEncoded(false);
		w.setRepeatedDeliveryAttempts(1);

		WebhookEventDispatcher dispatcher = new WebhookEventDispatcher();
		Sysprop payload = new Sysprop("obj1");
		payload.setAppid(APPID);

		String json = dispatcher.buildPayload(w, "create", payload);
		assertNotNull(json);
		assertFalse(json.isEmpty());

		JsonNode root = ParaObjectUtils.getJsonMapper().readTree(json);
		assertEquals("hook1", root.get(Config._ID).asText());
		assertEquals(APPID, root.get(Config._APPID).asText());
		assertEquals("webhookpayload", root.get(Config._TYPE).asText());
		assertEquals("https://example.com/hook", root.get("targetUrl").asText());
		assertFalse(root.get("urlEncoded").asBoolean());
		assertEquals("create", root.get("event").asText());
		assertNotNull(root.get("payload"));
		assertNotNull(root.get("signature"));
	}

	@Test
	public void testBuildPayload_includesHmacSignature() throws Exception {
		Webhook w = createWebhook("hook1", "https://example.com/hook", "test-secret-key");
		WebhookEventDispatcher dispatcher = new WebhookEventDispatcher();
		Sysprop payload = new Sysprop("obj1");

		String json = dispatcher.buildPayload(w, "create", payload);
		JsonNode root = ParaObjectUtils.getJsonMapper().readTree(json);
		String signature = root.get("signature").asText();
		String payloadStr = root.get("payload").asText();

		assertNotNull(signature);
		assertFalse(signature.isEmpty());
		// Verify signature matches HMAC-SHA256 of payload using secret
		String expected = com.erudika.para.core.utils.Utils.hmacSHA256(payloadStr, "test-secret-key");
		assertEquals(expected, signature);
	}

	@Test
	public void testBuildPayload_withSecretKeyPlaceholder() throws Exception {
		Webhook w = createWebhook("hook1", "https://example.com/hook", "{{secretKey}}");
		WebhookEventDispatcher dispatcher = new WebhookEventDispatcher();
		Sysprop payload = new Sysprop("obj1");

		App app = new App(APPID);
		app.setSecret("app-secret-value");
		when(dao.read(eq(App.id(APPID)))).thenReturn(app);

		String json = dispatcher.buildPayload(w, "update", payload);
		JsonNode root = ParaObjectUtils.getJsonMapper().readTree(json);
		String signature = root.get("signature").asText();
		String payloadStr = root.get("payload").asText();

		String expected = com.erudika.para.core.utils.Utils.hmacSHA256(payloadStr, "app-secret-value");
		assertEquals(expected, signature);
	}

	@Test
	public void testBuildPayload_withListPayload() throws Exception {
		Webhook w = createWebhook("hook1", "https://example.com/hook", "secret");
		WebhookEventDispatcher dispatcher = new WebhookEventDispatcher();

		List<Sysprop> items = new ArrayList<>();
		items.add(new Sysprop("obj1"));
		items.add(new Sysprop("obj2"));

		String json = dispatcher.buildPayload(w, "createAll", items);
		JsonNode root = ParaObjectUtils.getJsonMapper().readTree(json);
		String innerPayload = root.get("payload").asText();
		JsonNode innerNode = ParaObjectUtils.getJsonMapper().readTree(innerPayload);

		assertEquals(2, innerNode.get("items").size());
	}

	// --- dispatchEvent tests ---

	@Test
	public void testDispatchEvent_findsMatchingWebhooks() {
		Webhook w1 = createWebhook("hook1", "https://example.com/1", "s1");
		w1.setCreate(true);
		Webhook w2 = createWebhook("hook2", "https://example.com/2", "s2");
		w2.setCreate(true);

		when(search.findTerms(eq(APPID), eq("webhook"), any(Map.class), eq(true), any(Pager.class)))
				.thenReturn(Arrays.asList(w1, w2))
				.thenReturn(Collections.emptyList());

		WebhookEventDispatcher dispatcher = new WebhookEventDispatcher();
		Sysprop payload = new Sysprop("obj1");
		payload.setType("user");
		dispatcher.dispatchEvent(APPID, "create", true, payload);

		verify(queue, org.mockito.Mockito.times(2)).push(anyString());
	}

	@Test
	public void testDispatchEvent_appliesTypeFilter() {
		Webhook w1 = createWebhook("hook1", "https://example.com/1", "s1");
		w1.setTypeFilter("user");
		Webhook w2 = createWebhook("hook2", "https://example.com/2", "s2");
		w2.setTypeFilter("tag");

		when(search.findTerms(eq(APPID), eq("webhook"), any(Map.class), eq(true), any(Pager.class)))
				.thenReturn(Arrays.asList(w1, w2))
				.thenReturn(Collections.emptyList());

		WebhookEventDispatcher dispatcher = new WebhookEventDispatcher();
		Sysprop payload = new Sysprop("obj1");
		payload.setType("user");
		dispatcher.dispatchEvent(APPID, "create", true, payload);

		// Only w1 should match (type "user"), w2 filtered out (type "tag")
		verify(queue, org.mockito.Mockito.times(1)).push(anyString());
	}

	@Test
	public void testDispatchEvent_skipsWhenAppidBlank() {
		WebhookEventDispatcher dispatcher = new WebhookEventDispatcher();
		dispatcher.dispatchEvent("", "create", true, new Sysprop());
		dispatcher.dispatchEvent(null, "create", true, new Sysprop());

		verify(search, never()).findTerms(anyString(), anyString(), any(), anyBoolean(), any(Pager.class));
		verify(queue, never()).push(anyString());
	}

	// --- typeFilterMatches tests ---

	@Test
	public void testTypeFilterMatches_blankFilter() {
		Webhook w = createWebhook("h1", "https://ex.com", "s");
		w.setTypeFilter(null);
		assertTrue(WebhookEventDispatcher.typeFilterMatches(w, new Sysprop()));

		w.setTypeFilter("");
		assertTrue(WebhookEventDispatcher.typeFilterMatches(w, new Sysprop()));
	}

	@Test
	public void testTypeFilterMatches_allowAll() {
		Webhook w = createWebhook("h1", "https://ex.com", "s");
		w.setTypeFilter(App.ALLOW_ALL);
		assertTrue(WebhookEventDispatcher.typeFilterMatches(w, new Sysprop()));
	}

	@Test
	public void testTypeFilterMatches_caseInsensitive() {
		Webhook w = createWebhook("h1", "https://ex.com", "s");
		w.setTypeFilter("USER");
		Sysprop obj = new Sysprop();
		obj.setType("user");
		assertTrue(WebhookEventDispatcher.typeFilterMatches(w, obj));
	}

	@Test
	public void testTypeFilterMatches_noMatch() {
		Webhook w = createWebhook("h1", "https://ex.com", "s");
		w.setTypeFilter("tag");
		Sysprop obj = new Sysprop();
		obj.setType("user");
		assertFalse(WebhookEventDispatcher.typeFilterMatches(w, obj));
	}

	@Test
	public void testTypeFilterMatches_listPayload() {
		Webhook w = createWebhook("h1", "https://ex.com", "s");
		w.setTypeFilter("user");
		List<Sysprop> list = new ArrayList<>();
		Sysprop obj = new Sysprop();
		obj.setType("user");
		list.add(obj);
		assertTrue(WebhookEventDispatcher.typeFilterMatches(w, list));
	}

	// --- propertyFilterMatches tests ---

	@Test
	public void testPropertyFilterMatches_blankFilter() {
		Webhook w = createWebhook("h1", "https://ex.com", "s");
		w.setPropertyFilter(null);
		assertTrue(WebhookEventDispatcher.propertyFilterMatches(w, new Sysprop()));

		w.setPropertyFilter("");
		assertTrue(WebhookEventDispatcher.propertyFilterMatches(w, new Sysprop()));
	}

	@Test
	public void testPropertyFilterMatches_exactMatch() {
		Webhook w = createWebhook("h1", "https://ex.com", "s");
		w.setPropertyFilter("status:active");

		Map<String, Object> props = new HashMap<>();
		props.put("status", "active");
		assertTrue(WebhookEventDispatcher.propertyFilterMatches(w, props));
	}

	@Test
	public void testPropertyFilterMatches_noMatch() {
		Webhook w = createWebhook("h1", "https://ex.com", "s");
		w.setPropertyFilter("status:active");

		Map<String, Object> props = new HashMap<>();
		props.put("status", "inactive");
		assertFalse(WebhookEventDispatcher.propertyFilterMatches(w, props));
	}

	@Test
	public void testPropertyFilterMatches_dashMatchesNull() {
		Webhook w = createWebhook("h1", "https://ex.com", "s");
		w.setPropertyFilter("status:-");

		// Use a map with the key present but value null
		Map<String, Object> props = new HashMap<>();
		props.put("status", null);
		props.put("other", "value"); // make map non-empty
		assertTrue(WebhookEventDispatcher.propertyFilterMatches(w, props));
	}

	@Test
	public void testPropertyFilterMatches_dashMatchesBlank() {
		Webhook w = createWebhook("h1", "https://ex.com", "s");
		w.setPropertyFilter("status:-");

		Map<String, Object> props = new HashMap<>();
		props.put("status", "");
		assertTrue(WebhookEventDispatcher.propertyFilterMatches(w, props));
	}

	@Test
	public void testPropertyFilterMatches_pipeMeansAny() {
		Webhook w = createWebhook("h1", "https://ex.com", "s");
		w.setPropertyFilter("status:active|pending");

		Map<String, Object> props1 = new HashMap<>();
		props1.put("status", "active");
		assertTrue(WebhookEventDispatcher.propertyFilterMatches(w, props1));

		Map<String, Object> props2 = new HashMap<>();
		props2.put("status", "pending");
		assertTrue(WebhookEventDispatcher.propertyFilterMatches(w, props2));

		Map<String, Object> props3 = new HashMap<>();
		props3.put("status", "inactive");
		assertFalse(WebhookEventDispatcher.propertyFilterMatches(w, props3));
	}

	@Test
	public void testPropertyFilterMatches_mapPayload() {
		Webhook w = createWebhook("h1", "https://ex.com", "s");
		w.setPropertyFilter("status:active");

		Map<String, Object> props = new HashMap<>();
		props.put("status", "active");
		assertTrue(WebhookEventDispatcher.propertyFilterMatches(w, props));
	}

	@Test
	public void testPropertyFilterMatches_listPayload() {
		Webhook w = createWebhook("h1", "https://ex.com", "s");
		w.setPropertyFilter("status:active");

		Map<String, Object> props = new HashMap<>();
		props.put("status", "active");
		List<Map<String, Object>> list = new ArrayList<>();
		list.add(props);
		assertTrue(WebhookEventDispatcher.propertyFilterMatches(w, list));
	}
}
