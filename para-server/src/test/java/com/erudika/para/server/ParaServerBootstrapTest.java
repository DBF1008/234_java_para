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
package com.erudika.para.server;

import com.erudika.para.core.utils.Para;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for ParaServer bootstrap configuration of webhook node roles.
 */
public class ParaServerBootstrapTest {

	@BeforeEach
	public void setUp() {
		// Reset all relevant config properties to explicit defaults
		System.setProperty("para.webhooks_enabled", "false");
		System.setProperty("para.webhooks.worker_enabled", "false");
		System.setProperty("para.queue_link_enabled", "false");
	}

	@AfterEach
	public void tearDown() {
		System.setProperty("para.webhooks_enabled", "false");
		System.setProperty("para.webhooks.worker_enabled", "false");
		System.setProperty("para.queue_link_enabled", "false");
	}

	@Test
	public void testDefaultConfig_noWebhooksNoWorker() {
		// All properties set to "false" in setUp
		assertFalse(Para.getConfig().webhooksEnabled());
		assertFalse(Para.getConfig().webhooksWorkerEnabled());
		assertFalse(Para.getConfig().queuePollingEnabled());
	}

	@Test
	public void testFullNodeConfig_webhooksEnabledImpliesWorker() {
		System.setProperty("para.webhooks_enabled", "true");
		// Explicitly clear worker_enabled so it falls back to webhooks_enabled
		System.clearProperty("para.webhooks.worker_enabled");

		assertTrue(Para.getConfig().webhooksEnabled());
		// When webhooks.worker_enabled is NOT set, it follows webhooks_enabled (backward compat)
		assertTrue(Para.getConfig().webhooksWorkerEnabled());
	}

	@Test
	public void testApiOnlyNodeConfig_noWorker() {
		System.setProperty("para.webhooks_enabled", "true");
		System.setProperty("para.webhooks.worker_enabled", "false");
		assertTrue(Para.getConfig().webhooksEnabled());
		assertFalse(Para.getConfig().webhooksWorkerEnabled());
	}

	@Test
	public void testWorkerOnlyNodeConfig_noListener() {
		System.setProperty("para.webhooks_enabled", "false");
		System.setProperty("para.webhooks.worker_enabled", "true");
		assertFalse(Para.getConfig().webhooksEnabled());
		assertTrue(Para.getConfig().webhooksWorkerEnabled());
	}

	@Test
	public void testBothExplicitlyEnabled() {
		System.setProperty("para.webhooks_enabled", "true");
		System.setProperty("para.webhooks.worker_enabled", "true");
		assertTrue(Para.getConfig().webhooksEnabled());
		assertTrue(Para.getConfig().webhooksWorkerEnabled());
	}

	@Test
	public void testQueuePollingIndependent() {
		System.setProperty("para.queue_link_enabled", "true");
		System.setProperty("para.webhooks_enabled", "false");
		System.setProperty("para.webhooks.worker_enabled", "false");
		assertTrue(Para.getConfig().queuePollingEnabled());
		assertFalse(Para.getConfig().webhooksEnabled());
		assertFalse(Para.getConfig().webhooksWorkerEnabled());
	}
}
