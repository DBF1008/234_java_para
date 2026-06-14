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
import com.erudika.para.core.queue.River;
import com.erudika.para.core.search.Search;
import com.erudika.para.core.utils.CoreUtils;
import com.erudika.para.core.utils.Para;
import com.erudika.para.core.utils.ParaObjectUtils;
import com.erudika.para.core.utils.Utils;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.mock;

/**
 * Tests that node roles are cleanly separated when consuming the queue: a node only delivers webhook
 * payloads if it has the webhooks "worker" role enabled, and only imports/indexes data if it has the
 * "river" (queue polling) role enabled. The two roles never cross.
 * @author Alex Bogdanovski [alex@erudika.com]
 */
public class WebhookRoleSeparationTest {

	private String appid;
	private MockDAO dao;
	private WebhookTestServer server;

	@BeforeEach
	public void setUp() throws Exception {
		appid = "app_" + Utils.getNewId();
		dao = new MockDAO();
		CoreUtils.getInstance().setDao(dao);
		CoreUtils.getInstance().setCache(new MockCache());
		CoreUtils.getInstance().setSearch(mock(Search.class));
		CoreUtils.getInstance().setQueue(new MockQueue());
		server = new WebhookTestServer("/hook");
		Para.setHealthy(true);
		System.setProperty("para.queue.polling_sleep_seconds", "0"); // never sleep in the one-shot river
	}

	@AfterEach
	public void tearDown() {
		server.stop();
		System.clearProperty("para.webhooks.worker_enabled");
		System.clearProperty("para.queue_link_enabled");
		System.clearProperty("para.queue.polling_sleep_seconds");
	}

	// builds a webhookpayload message addressed at the local test server
	private String webhookMessage() {
		Webhook wh = new Webhook(server.url("/hook"));
		wh.setId("wh_" + Utils.getNewId());
		wh.setAppid(appid);
		wh.setSecret("s");
		wh.setActive(true);
		Sysprop obj = new Sysprop("o");
		obj.setAppid(appid);
		return wh.buildPayloadAsJSON("create", obj);
	}

	// builds a plain data-import message for a Sysprop (create)
	private String dataMessage(String objId) throws Exception {
		Map<String, Object> m = new HashMap<>();
		m.put("type", "sysprop");
		m.put("id", objId);
		m.put("appid", appid);
		m.put("name", "imported");
		m.put("_create", "true");
		return ParaObjectUtils.getJsonWriterNoIdent().writeValueAsString(m);
	}

	// a river that yields the given batch exactly once, then stops the loop
	private void runOneShotRiver(List<String> batch) {
		River river = new River() {
			private boolean done = false;
			@Override
			public List<String> pullMessages() {
				if (done) {
					Thread.currentThread().interrupt();
					return Collections.emptyList();
				}
				done = true;
				return batch;
			}
		};
		river.run();
	}

	@Test
	public void testProduceOnlyNodeDoesNotDeliver() {
		System.setProperty("para.webhooks.worker_enabled", "false");
		System.setProperty("para.queue_link_enabled", "false");

		runOneShotRiver(Collections.singletonList(webhookMessage()));

		assertEquals(0, server.hitCount(), "an API-only node must not deliver webhooks");
	}

	@Test
	public void testWorkerNodeDelivers() {
		System.setProperty("para.webhooks.worker_enabled", "true");

		runOneShotRiver(Collections.singletonList(webhookMessage()));

		assertEquals(1, server.hitCount(), "a worker node should deliver the webhook");
	}

	@Test
	public void testDataConsumerNodeDoesNotDeliverWebhooks() {
		System.setProperty("para.webhooks.worker_enabled", "false");
		System.setProperty("para.queue_link_enabled", "true");

		runOneShotRiver(Collections.singletonList(webhookMessage()));

		assertEquals(0, server.hitCount(), "a data-import node must not deliver webhooks");
	}

	@Test
	public void testDataImportedOnlyWhenQueuePollingEnabled() throws Exception {
		System.setProperty("para.webhooks.worker_enabled", "false");
		System.setProperty("para.queue_link_enabled", "true");
		String objId = "imp_" + Utils.getNewId();

		runOneShotRiver(Collections.singletonList(dataMessage(objId)));

		// the river imports via the no-appid DAO.createAll(), which MockDAO stores under the root app id
		assertNotNull(dao.read(Para.getConfig().getRootAppIdentifier(), objId),
				"data should be imported when queue polling is enabled");
	}

	@Test
	public void testWorkerNodeDoesNotImportData() throws Exception {
		System.setProperty("para.webhooks.worker_enabled", "true");
		System.setProperty("para.queue_link_enabled", "false");
		String objId = "imp_" + Utils.getNewId();

		runOneShotRiver(Collections.singletonList(dataMessage(objId)));

		assertNull(dao.read(Para.getConfig().getRootAppIdentifier(), objId),
				"a webhook-only worker must not import data");
	}
}
