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

import com.erudika.para.core.search.MockSearch;
import com.erudika.para.core.search.Search;
import com.erudika.para.core.utils.CoreUtils;
import com.erudika.para.core.utils.Pager;
import com.erudika.para.core.utils.Para;
import com.erudika.para.core.utils.Utils;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for the unified app-deletion finalization boundary
 * ({@link CoreUtils#deleteApp(App)} invoked from {@link App#delete()}). They assert that
 * deleting an app leaves no residue in the DAO, the search index, the cache, or the file store.
 *
 * @author Alex Bogdanovski [alex@erudika.com]
 */
public class AppCleanupTest {

	private static InputStream stream(String content) {
		return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
	}

	// each test uses a unique appid because MockDAO/MockCache/MockFileStore keep static/shared state
	private static App newApp(String prefix) {
		App app = new App(prefix + "-" + Utils.getNewId());
		assertNotNull(app.create());
		return app;
	}

	@Test
	public void testDeleteApp_removesAllChildObjectsAndTheAppRow() {
		App app = newApp("cleanup-objects");
		String appid = app.getAppIdentifier();
		for (int i = 0; i < 7; i++) {
			Para.getDAO().create(appid, new Sysprop("child-" + i));
		}
		// sanity - children and the app row exist before deletion
		assertFalse(Para.getDAO().readPage(appid, new Pager()).isEmpty());
		assertNotNull(Para.getDAO().read(app.getAppid(), app.getId()));

		app.delete();

		// no child objects remain in the app's table, and the app row itself is gone
		assertTrue(Para.getDAO().readPage(appid, new Pager()).isEmpty());
		assertNull(Para.getDAO().read(app.getAppid(), app.getId()));
	}

	@Test
	public void testDeleteApp_evictsCachedObjects() {
		App app = newApp("cleanup-cache");
		String appid = app.getAppIdentifier();
		Para.getCache().put(appid, "cached-1", new Sysprop("cached-1"));
		Para.getCache().put(appid, "cached-2", new Sysprop("cached-2"));
		// the app object is typically cached in the root namespace
		Para.getCache().put(app.getAppid(), app.getId(), app);
		assertTrue(Para.getCache().contains(appid, "cached-1"));
		assertTrue(Para.getCache().contains(app.getAppid(), app.getId()));

		app.delete();

		// the whole app cache namespace is wiped and the app object is evicted from the root cache
		assertFalse(Para.getCache().contains(appid, "cached-1"));
		assertFalse(Para.getCache().contains(appid, "cached-2"));
		assertFalse(Para.getCache().contains(app.getAppid(), app.getId()));
	}

	@Test
	public void testDeleteApp_removesFromSearchIndex() {
		final List<String> deletedIndexes = new ArrayList<>();
		Search previous = CoreUtils.getInstance().getSearch();
		// record which apps are removed from the index
		CoreUtils.getInstance().setSearch(new MockSearch() {
			@Override
			public void deleteIndex(App app) {
				deletedIndexes.add(app.getId());
			}
		});
		try {
			App app = newApp("cleanup-index");
			app.delete();
			// the deleted app was removed from the search index exactly once
			assertTrue(deletedIndexes.contains(app.getId()));
			assertEquals(1, deletedIndexes.size());
		} finally {
			CoreUtils.getInstance().setSearch(previous);
		}
	}

	@Test
	public void testDeleteApp_deletesAllAppFilesButKeepsOtherApps() {
		App app = newApp("cleanup-files");
		String appid = app.getAppIdentifier();
		Para.getFileStore().store(appid + "/avatar.png", stream("a"));
		Para.getFileStore().store(appid + "/attachments/doc.pdf", stream("b"));
		// a file that belongs to a different app must survive
		String otherFile = "some-other-app/keep.txt";
		Para.getFileStore().store(otherFile, stream("c"));
		assertNotNull(Para.getFileStore().load(appid + "/avatar.png"));

		app.delete();

		assertNull(Para.getFileStore().load(appid + "/avatar.png"));
		assertNull(Para.getFileStore().load(appid + "/attachments/doc.pdf"));
		assertNotNull(Para.getFileStore().load(otherFile));
	}

	@Test
	public void testDeleteApp_doesNotTouchRootApp() {
		App root = new App(Para.getConfig().getRootAppIdentifier());
		assertTrue(root.isRootApp());
		String appid = root.getAppIdentifier();
		Sysprop child = new Sysprop("root-child-" + Utils.getNewId());
		Para.getDAO().create(appid, child);

		// the finalization boundary must refuse to wipe the root app
		CoreUtils.getInstance().deleteApp(root);

		assertNotNull(Para.getDAO().read(appid, child.getId()));
		// cleanup
		Para.getDAO().delete(appid, child);
	}
}
