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
package com.erudika.para.core.listeners;

import com.erudika.para.core.App;
import com.erudika.para.core.utils.Para;

/**
 * Built-in listener that invalidates an application's cached {@link App} object whenever one of its
 * settings is added, updated or removed. This is the single, unified hook through which app-setting
 * changes propagate to the cache: it evicts the canonical cache entry for the app so that the next
 * read reloads the fresh settings from the data store.
 * <p>
 * An {@link App} object always lives in the root application's cache partition - an app's
 * {@code appid} is the root identifier and child apps cannot contain app objects - keyed by
 * {@link App#getId()}. Eviction therefore always targets the root partition, regardless of which
 * tenant the settings belong to. Routing every settings change through this single key/partition is
 * what keeps the tenant and default-app cache boundaries from leaking into one another.
 * <p>
 * The eviction is node-local. With a node-local cache, other nodes converge once their own entries
 * expire; with a distributed cache the eviction propagates immediately, because the correct
 * partition and key are always used.
 *
 * @author Alex Bogdanovski [alex@erudika.com]
 */
public final class AppSettingsCacheInvalidationListener implements AppSettingAddedListener, AppSettingRemovedListener {

	/**
	 * No-args constructor.
	 */
	public AppSettingsCacheInvalidationListener() {
	}

	@Override
	public void onSettingAdded(App app, String settingKey, Object settingValue) {
		invalidate(app);
	}

	@Override
	public void onSettingRemoved(App app, String settingKey) {
		invalidate(app);
	}

	/**
	 * Evicts the cached representation of the given app from the root cache partition.
	 * No-op if the app or its id is null, or if caching is disabled (the underlying remove is a no-op).
	 * @param app the app whose cached representation should be invalidated
	 */
	public static void invalidate(App app) {
		if (app != null && app.getId() != null) {
			Para.getCache().remove(Para.getConfig().getRootAppIdentifier(), app.getId());
		}
	}

	/**
	 * Evicts the cached representation of the given app identifier from the root cache partition.
	 * @param appid the app identifier, with or without the "app:" prefix
	 */
	public static void invalidate(String appid) {
		String id = App.id(appid);
		if (id != null) {
			Para.getCache().remove(Para.getConfig().getRootAppIdentifier(), id);
		}
	}
}
