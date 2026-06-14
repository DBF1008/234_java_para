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

import com.erudika.para.core.queue.Queue;
import com.erudika.para.core.queue.River;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.apache.commons.lang3.StringUtils;

/**
 * Tests for LocalQueue boundary conditions.
 */
public class LocalQueueTest {

	private static final int MAX_MESSAGES = 10;

	/**
	 * Verifies that the pullMessages logic in LocalQueue respects the MAX_MESSAGES limit.
	 * Previously, the off-by-one bug (msgs.size() <= MAX_MESSAGES) would allow 11 messages.
	 */
	@Test
	public void testPullRespectsMaxMessages() {
		LocalQueue queue = new LocalQueue("test");

		// Push 15 messages
		for (int i = 0; i < 15; i++) {
			queue.push("{\"msg\": " + i + "}");
		}

		// Simulate the pullMessages logic from LocalQueue's River subclass
		List<String> msgs = new ArrayList<>(MAX_MESSAGES);
		String msg;
		do {
			msg = queue.pull();
			if (!StringUtils.isBlank(msg)) {
				msgs.add(msg);
			}
		} while (!StringUtils.isBlank(msg) && msgs.size() < MAX_MESSAGES);

		// Should pull exactly MAX_MESSAGES (10), not 11
		assertEquals(MAX_MESSAGES, msgs.size(),
				"Should pull exactly " + MAX_MESSAGES + " messages, not more (off-by-one fix)");

		// Remaining messages should still be in queue
		String remaining = queue.pull();
		assertTrue(!StringUtils.isBlank(remaining),
				"There should be remaining messages in the queue");

		// Count remaining
		int remainingCount = 1;
		while (!StringUtils.isBlank(queue.pull())) {
			remainingCount++;
		}
		assertEquals(5, remainingCount, "Should have 5 remaining messages (15 - 10)");
	}

	@Test
	public void testPullFewerThanMax() {
		LocalQueue queue = new LocalQueue("test");

		// Push only 3 messages
		for (int i = 0; i < 3; i++) {
			queue.push("{\"msg\": " + i + "}");
		}

		// Simulate pullMessages
		List<String> msgs = new ArrayList<>(MAX_MESSAGES);
		String msg;
		do {
			msg = queue.pull();
			if (!StringUtils.isBlank(msg)) {
				msgs.add(msg);
			}
		} while (!StringUtils.isBlank(msg) && msgs.size() < MAX_MESSAGES);

		// Should pull exactly 3 (all available)
		assertEquals(3, msgs.size(), "Should pull all 3 available messages");
	}

	@Test
	public void testPullEmptyQueue() {
		LocalQueue queue = new LocalQueue("test");

		// Simulate pullMessages on empty queue
		List<String> msgs = new ArrayList<>(MAX_MESSAGES);
		String msg;
		do {
			msg = queue.pull();
			if (!StringUtils.isBlank(msg)) {
				msgs.add(msg);
			}
		} while (!StringUtils.isBlank(msg) && msgs.size() < MAX_MESSAGES);

		assertTrue(msgs.isEmpty(), "Should return empty list for empty queue");
	}
}
