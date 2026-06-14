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
package com.erudika.para.core;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import org.junit.jupiter.api.Test;

/**
 * Verifies the firing semantics of the app-setting listeners and that their registries are
 * concurrency-safe.
 *
 * @author Alex Bogdanovski [alex@erudika.com]
 */
public class AppSettingListenersTest {

	@Test
	public void testAddSettingFiresOnlyForNonNullValue() {
		AtomicInteger added = new AtomicInteger();
		App.addAppSettingAddedListener((app, key, value) -> added.incrementAndGet());
		App app = new App("listenertest1");
		app.addSetting("k", "v");
		assertEquals(1, added.get());
		app.addSetting("k2", null); // null value -> no event
		assertEquals(1, added.get());
	}

	@Test
	public void testRemoveSettingFiresOnlyIfPresent() {
		AtomicInteger removed = new AtomicInteger();
		App.addAppSettingRemovedListener((app, key) -> removed.incrementAndGet());
		App app = new App("listenertest2");
		app.addSetting("k", "v");
		app.removeSetting("absent"); // not present -> no event
		assertEquals(0, removed.get());
		app.removeSetting("k");
		assertEquals(1, removed.get());
	}

	@Test
	public void testAddAllAndClearFirePerKey() {
		AtomicInteger added = new AtomicInteger();
		AtomicInteger removed = new AtomicInteger();
		App.addAppSettingAddedListener((app, key, value) -> added.incrementAndGet());
		App.addAppSettingRemovedListener((app, key) -> removed.incrementAndGet());
		App app = new App("listenertest3");
		Map<String, Object> s = new HashMap<>();
		s.put("a", 1);
		s.put("b", 2);
		s.put("c", 3);
		app.addAllSettings(s);
		assertEquals(3, added.get());
		app.clearSettings();
		assertEquals(3, removed.get());
	}

	@Test
	public void testRegistrationIsThreadSafeDuringFiring() throws InterruptedException {
		App app = new App("listenertest4");
		app.addSetting("seed", "v");
		AtomicBoolean failed = new AtomicBoolean(false);
		Runnable register = () -> {
			try {
				for (int i = 0; i < 200; i++) {
					App.addAppSettingAddedListener((a, k, v) -> { });
				}
			} catch (Exception e) {
				failed.set(true);
			}
		};
		Runnable fire = () -> {
			try {
				for (int i = 0; i < 200; i++) {
					app.addSetting("k" + i, "v" + i);
				}
			} catch (Exception e) {
				failed.set(true);
			}
		};
		Thread t1 = new Thread(register);
		Thread t2 = new Thread(fire);
		t1.start();
		t2.start();
		t1.join();
		t2.join();
		// with a non-thread-safe set this would throw ConcurrentModificationException during iteration
		assertFalse(failed.get());
	}

}
