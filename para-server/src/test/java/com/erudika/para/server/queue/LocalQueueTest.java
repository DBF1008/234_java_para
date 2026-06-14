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

import com.erudika.para.core.cache.Cache;
import com.erudika.para.core.persistence.DAO;
import com.erudika.para.core.search.Search;
import com.erudika.para.core.utils.CoreUtils;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.mock;

/**
 * Tests for {@link LocalQueue} with separate webhook polling.
 */
public class LocalQueueTest {

	@BeforeEach
	public void setUp() {
		CoreUtils.getInstance().setDao(mock(DAO.class));
		CoreUtils.getInstance().setCache(mock(Cache.class));
		CoreUtils.getInstance().setSearch(mock(Search.class));
	}

	@AfterEach
	public void tearDown() {
		LocalQueue.stopPollingForMessages();
		LocalQueue.stopWebhookPollingForMessages();
	}

	@Test
	public void testPushPull() {
		LocalQueue q = new LocalQueue();
		q.push("{\"test\": 123}");
		assertEquals("{\"test\": 123}", q.pull());
		assertEquals("", q.pull());
	}

	@Test
	public void testPushBlankIgnored() {
		LocalQueue q = new LocalQueue();
		q.push("");
		q.push(null);
		assertEquals("", q.pull());
	}

	@Test
	public void testName() {
		LocalQueue q = new LocalQueue("my-queue");
		assertEquals("my-queue", q.getName());
		q.setName("new-name");
		assertEquals("new-name", q.getName());
	}

	@Test
	public void testStartPolling_startsRiverOnly() {
		LocalQueue q = new LocalQueue();
		q.startPolling();
		// Calling again should be idempotent
		q.startPolling();
		// No exception means success
		q.stopPolling();
	}

	@Test
	public void testStartWebhookPolling_startsWorkerOnly() {
		LocalQueue q = new LocalQueue();
		q.startWebhookPolling();
		// Calling again should be idempotent
		q.startWebhookPolling();
		// No exception means success
		q.stopWebhookPolling();
	}

	@Test
	public void testIndependentStop() {
		LocalQueue q = new LocalQueue();
		q.startPolling();
		q.startWebhookPolling();
		q.stopPolling();
		// Webhook worker should still be running
		// Restart river should work
		q.startPolling();
		q.stopPolling();
		q.stopWebhookPolling();
	}
}
