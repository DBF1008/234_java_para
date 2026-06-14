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
package com.erudika.para.server.cache;

import com.erudika.para.core.App;
import com.erudika.para.core.listeners.AppSettingAddedListener;
import com.erudika.para.core.listeners.AppSettingRemovedListener;
import com.erudika.para.core.utils.Para;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Built-in listener that evicts the cached {@link App} object whenever its settings change.
 * This ensures that the next read via {@code ManagedDAO.readFromCacheOrDB()} reloads the
 * App from the database with fresh settings, preventing stale configuration from being served.
 *
 * <p>Registered once during {@code ParaServer.initialize()} before {@code Para.initialize()}
 * is called, so it is active for the entire server lifetime.</p>
 *
 * @author Para Contributors
 */
public class AppCacheInvalidationListener
		implements AppSettingAddedListener, AppSettingRemovedListener {

	private static final Logger logger = LoggerFactory.getLogger(AppCacheInvalidationListener.class);

	@Override
	public void onSettingAdded(App app, String key, Object value) {
		evict(app, key);
	}

	@Override
	public void onSettingRemoved(App app, String key) {
		evict(app, key);
	}

	private void evict(App app, String key) {
		if (app == null || StringUtils.isBlank(app.getId())) {
			return;
		}
		if (Para.getConfig().isCacheEnabled()) {
			Para.getCache().remove(app.getAppid(), app.getId());
			logger.debug("Evicted cached App '{}' due to setting change on key '{}'.",
					app.getId(), key);
		}
	}
}
