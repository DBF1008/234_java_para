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

import com.erudika.para.core.cache.Cache;
import com.erudika.para.core.utils.Para;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.github.benmanes.caffeine.cache.RemovalCause;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default implementation of the {@link Cache} interface using Caffeine.
 * <p>
 * Multitenancy is achieved by namespacing every cached object under a deterministic, collision-free
 * composite key: {@code appid.length() + ":" + appid + ":" + id}. The length prefix makes the key
 * injective, so no two {@code (appid, id)} pairs can ever collide - this is what keeps one app's
 * cache (including the default/root app) isolated from another's.
 * <p>
 * To support evicting a whole app's entries, the keys of each app are tracked in a side index
 * ({@code keysByApp}). {@link #removeAll(java.lang.String)} snapshots and drops that index entry and
 * bulk-invalidates exactly those keys, so an app's cache is truly cleared (no orphaned entries) while
 * other apps are left untouched. A Caffeine removal listener prunes the index when entries are
 * evicted by size or time, so the index cannot grow unbounded.
 *
 * @author Alex Bogdanovski [alex@erudika.com]
 */
public class CaffeineCache implements Cache {

	private static final Logger logger = LoggerFactory.getLogger(CaffeineCache.class);
	private static final int DEFAULT_EXPIRATION_MIN = Para.getConfig().caffeineEvictAfterMin();
	private final com.github.benmanes.caffeine.cache.Cache<String, Object> cache;
	// appid -> set of composite cache keys belonging to that app (used for correct removeAll(appid))
	private final ConcurrentHashMap<String, Set<String>> keysByApp = new ConcurrentHashMap<>();

	/**
	 * Default constructor.
	 */
	public CaffeineCache() {
		cache = Caffeine.newBuilder()
			.maximumSize(Para.getConfig().caffeineCacheSize())
			.expireAfter(Expiry.creating((k, v) -> Duration.ofMinutes(DEFAULT_EXPIRATION_MIN)))
			.removalListener(this::onRemoval)
			.build();
	}

	/**
	 * Creates a new instance.
	 * @param cache cache
	 */
	CaffeineCache(com.github.benmanes.caffeine.cache.Cache<String, Object> cache) {
		this.cache = cache;
	}

	@Override
	public boolean contains(String appid, String id) {
		if (StringUtils.isBlank(id) || StringUtils.isBlank(appid)) {
			return false;
		}
		boolean exists = get(appid, id) != null;
		logger.debug("Cache.contains({}) {}", id, exists);
		return exists;
	}

	@Override
	public <T> void put(String appid, String id, T object) {
		if (!StringUtils.isBlank(id) && object != null && !StringUtils.isBlank(appid)) {
			String key = key(appid, id);
			cache.put(key, object);
			trackKey(appid, key);
			logger.debug("Cache.put() {} {}", appid, id);
		}
	}

	@Override
	public <T> void put(String appid, String id, T object, Long ttlSeconds) {
		if (ttlSeconds == null || ttlSeconds <= 0L) {
			put(appid, id, object);
			return;
		}
		if (!StringUtils.isBlank(id) && object != null && !StringUtils.isBlank(appid)) {
			String key = key(appid, id);
			cache.policy().expireVariably().ifPresent((t) -> {
				t.put(key, object, ttlSeconds, TimeUnit.SECONDS);
				trackKey(appid, key);
			});
			logger.debug("Cache.put() {} {} ttl {}", appid, id, ttlSeconds);
		}
	}

	@Override
	public <T> void putAll(String appid, Map<String, T> objects) {
		if (objects != null && !objects.isEmpty() && !StringUtils.isBlank(appid)) {
			Map<String, T> cleanMap = new LinkedHashMap<>(objects.size());
			for (Map.Entry<String, T> entry : objects.entrySet()) {
				if (!StringUtils.isBlank(entry.getKey()) && entry.getValue() != null) {
					String key = key(appid, entry.getKey());
					cleanMap.put(key, entry.getValue());
					trackKey(appid, key);
				}
			}
			cache.putAll(cleanMap);
			logger.debug("Cache.putAll() {} {}", appid, objects.size());
		}
	}

	@Override
	@SuppressWarnings("unchecked")
	public <T> T get(String appid, String id) {
		if (StringUtils.isBlank(id) || StringUtils.isBlank(appid)) {
			return null;
		}
		String key = key(appid, id);
		logger.debug("Cache.get() {} {}", appid, id);
		return (T) cache.getIfPresent(key);
	}

	@Override
	@SuppressWarnings("unchecked")
	public <T> Map<String, T> getAll(String appid, List<String> ids) {
		if (ids == null || StringUtils.isBlank(appid)) {
			return Collections.emptyMap();
		}
		Map<String, T> map1 = new LinkedHashMap<>(ids.size());
		ids.remove(null);
		for (String id : ids) {
			T t = get(appid, id);
			if (t != null) {
				map1.put(id, t);
			}
		}
		logger.debug("Cache.getAll() {} {}", appid, ids.size());
		return map1;
	}

	@Override
	public void remove(String appid, String id) {
		if (!StringUtils.isBlank(id) && !StringUtils.isBlank(appid)) {
			logger.debug("Cache.remove() {} {}", appid, id);
			String key = key(appid, id);
			cache.invalidate(key);
			untrackKey(appid, key);
		}
	}

	@Override
	public void removeAll(String appid) {
		if (!StringUtils.isBlank(appid)) {
			Set<String> keys = keysByApp.remove(appid);
			if (keys != null && !keys.isEmpty()) {
				cache.invalidateAll(keys);
			}
			logger.debug("Cache.removeAll() {}", appid);
		}
	}

	@Override
	public void removeAll(String appid, List<String> ids) {
		if (ids != null && !StringUtils.isBlank(appid)) {
			for (String id : ids) {
				if (!StringUtils.isBlank(id)) {
					remove(appid, id);
				}
			}
			logger.debug("Cache.removeAll() {} {}", appid, ids.size());
		}
	}

	/**
	 * Builds the deterministic, collision-free composite key for an object. The length prefix makes
	 * the mapping injective even if {@code appid} or {@code id} contain the ':' separator.
	 */
	private String key(String appid, String id) {
		return appid.length() + ":" + appid + ":" + id;
	}

	private void trackKey(String appid, String key) {
		keysByApp.computeIfAbsent(appid, k -> ConcurrentHashMap.newKeySet()).add(key);
	}

	private void untrackKey(String appid, String key) {
		Set<String> keys = keysByApp.get(appid);
		if (keys != null) {
			keys.remove(key);
		}
	}

	/**
	 * Keeps the {@code keysByApp} index consistent when Caffeine evicts an entry by size or time.
	 * If the whole app entry was already dropped by {@link #removeAll(java.lang.String)} this is a no-op.
	 */
	private void onRemoval(String key, Object value, RemovalCause cause) {
		if (key == null) {
			return;
		}
		String appid = appidFromKey(key);
		if (appid != null) {
			Set<String> keys = keysByApp.get(appid);
			if (keys != null) {
				keys.remove(key);
			}
		}
	}

	/**
	 * Extracts the appid from a composite key of the form {@code len:appid:id}. Returns null if the
	 * key is not one we produced.
	 */
	private String appidFromKey(String key) {
		int sep = key.indexOf(':');
		if (sep <= 0) {
			return null;
		}
		try {
			int len = Integer.parseInt(key.substring(0, sep));
			int start = sep + 1;
			if (len >= 0 && start + len <= key.length()) {
				return key.substring(start, start + len);
			}
		} catch (NumberFormatException e) {
			return null;
		}
		return null;
	}

	////////////////////////////////////////////////////

	@Override
	public boolean contains(String id) {
		return contains(Para.getConfig().getRootAppIdentifier(), id);
	}

	@Override
	public <T> void put(String id, T object) {
		put(Para.getConfig().getRootAppIdentifier(), id, object);
	}

	@Override
	public <T> void putAll(Map<String, T> objects) {
		putAll(Para.getConfig().getRootAppIdentifier(), objects);
	}

	@Override
	public <T> T get(String id) {
		return get(Para.getConfig().getRootAppIdentifier(), id);
	}

	@Override
	public <T> Map<String, T> getAll(List<String> ids) {
		return getAll(Para.getConfig().getRootAppIdentifier(), ids);
	}

	@Override
	public void remove(String id) {
		remove(Para.getConfig().getRootAppIdentifier(), id);
	}

	@Override
	public void removeAll() {
		removeAll(Para.getConfig().getRootAppIdentifier());
	}

	@Override
	public void removeAll(List<String> ids) {
		removeAll(Para.getConfig().getRootAppIdentifier(), ids);
	}

}
