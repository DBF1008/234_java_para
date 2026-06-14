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
package com.erudika.para.core.webhooks;

import com.erudika.para.core.App;
import com.erudika.para.core.ParaObject;
import com.erudika.para.core.Webhook;
import com.erudika.para.core.utils.Config;
import com.erudika.para.core.utils.Pager;
import com.erudika.para.core.utils.Para;
import com.erudika.para.core.utils.ParaObjectUtils;
import com.erudika.para.core.utils.Utils;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Dispatches webhook events by searching for matching webhook registrations,
 * building payloads, and pushing them onto the queue for delivery.
 *
 * <p>This class was extracted from {@link Webhook} to separate event dispatch
 * concerns from the domain model. All original method signatures on {@code Webhook}
 * delegate here for backward compatibility.</p>
 *
 * @author Para
 */
public class WebhookEventDispatcher {

	private static final Logger logger = LoggerFactory.getLogger(WebhookEventDispatcher.class);

	/**
	 * No-args constructor.
	 */
	public WebhookEventDispatcher() {
		// default constructor
	}

	/**
	 * Sends out the payload object for an event to the queue for processing.
	 * Searches for all active webhooks matching the event, applies type and property
	 * filters, then pushes each matching webhook's payload onto the queue.
	 *
	 * @param appid appid
	 * @param eventName event name like "create", "delete" or "customEvents"
	 * @param eventValue event value - for custom events this is the name of the custom event
	 * @param payload the payload object
	 */
	public void dispatchEvent(String appid, String eventName, Object eventValue, Object payload) {
		if (StringUtils.isBlank(appid)) {
			return;
		}
		Pager p = new Pager(10);
		p.setSortby("_docid");
		List<Webhook> webhooks;
		do {
			Map<String, Object> terms = new HashMap<>();
			terms.put(eventName, eventValue);
			terms.put(Config._APPID, appid);
			terms.put("active", true);
			webhooks = Para.getSearch().findTerms(appid, Utils.type(Webhook.class), terms, true, p);
			webhooks.stream().
					filter(webhook -> typeFilterMatches(webhook, payload)
						&& propertyFilterMatches(webhook, payload)).
					forEach(webhook -> Para.getQueue().push(buildPayload(webhook,
					(eventValue instanceof String) ? (String) eventValue : eventName, payload)));
		} while (!webhooks.isEmpty());
	}

	/**
	 * Builds the JSON payload object for a webhook.
	 * @param webhook the webhook registration
	 * @param event Para DAO method name or custom event name
	 * @param payload payload object to convert to JSON
	 * @return the payload + metadata object as JSON string
	 */
	public String buildPayload(Webhook webhook, String event, Object payload) {
		Map<String, Object> data = new HashMap<>();
		data.put(Config._ID, webhook.getId());
		data.put(Config._APPID, webhook.getAppid());
		data.put(Config._TYPE, "webhookpayload");
		data.put("targetUrl", webhook.getTargetUrl());
		data.put("urlEncoded", webhook.getUrlEncoded());
		data.put("repeatedDeliveryAttempts", webhook.getRepeatedDeliveryAttempts());
		data.put("event", event);

		Map<String, Object> payloadObject = new HashMap<>();
		payloadObject.put(Config._TIMESTAMP, System.currentTimeMillis());
		payloadObject.put(Config._APPID, webhook.getAppid());
		payloadObject.put("event", event);
		if (payload instanceof List) {
			payloadObject.put("items", payload);
		} else {
			payloadObject.put("items", Collections.singletonList(payload));
		}
		try {
			String payloadString = ParaObjectUtils.getJsonWriterNoIdent().writeValueAsString(payloadObject);
			data.put("payload", payloadString);
			data.put("signature", Utils.hmacSHA256(payloadString, resolveSecret(webhook)));
			return ParaObjectUtils.getJsonWriterNoIdent().writeValueAsString(data);
		} catch (Exception e) {
			logger.error("Failed to build webhook payload JSON", e);
		}
		return "";
	}

	/**
	 * Returns true if the type filter matches the payload.
	 * @param webhook the webhook
	 * @param paraObjects the payload (single ParaObject or List of ParaObjects)
	 * @return true if matches
	 */
	public static boolean typeFilterMatches(Webhook webhook, Object paraObjects) {
		if (StringUtils.isBlank(webhook.getTypeFilter()) || App.ALLOW_ALL.equals(webhook.getTypeFilter())) {
			return true;
		}
		if (paraObjects instanceof ParaObject) {
			return webhook.getTypeFilter().equalsIgnoreCase(((ParaObject) paraObjects).getType());
		} else if (paraObjects instanceof List) {
			List<?> list = (List<?>) paraObjects;
			if (!list.isEmpty() && list.get(0) instanceof ParaObject) {
				return webhook.getTypeFilter().equalsIgnoreCase(((ParaObject) list.get(0)).getType());
			}
		}
		return false;
	}

	/**
	 * Matches a property filter against a payload.
	 * @param webhook the webhook containing the filter
	 * @param payload the payload to match against
	 * @return true if the payload matches the filter
	 */
	@SuppressWarnings("unchecked")
	public static boolean propertyFilterMatches(Webhook webhook, Object payload) {
		if (StringUtils.isBlank(webhook.getPropertyFilter())) {
			return true;
		}
		if (webhook.getPropertyFilter().contains(":")) {
			if (payload instanceof ParaObject) {
				return matchesPropFilter(webhook, (ParaObject) payload);
			} else if (payload instanceof List) {
				List<?> list = (List<?>) payload;
				return !list.isEmpty() && list.stream().anyMatch((pobj) -> matchesPropFilter(webhook, pobj));
			} else if (payload instanceof Map) {
				Map<?, ?> props = (Map<?, ?>) payload;
				return !props.isEmpty() && matchesProp(webhook, (Map<String, Object>) props);
			}
		}
		return false;
	}

	/**
	 * Matches a property filter against a Para object.
	 * @param webhook the webhook
	 * @param paraObject the object
	 * @return true if matches
	 */
	@SuppressWarnings("unchecked")
	private static boolean matchesPropFilter(Webhook webhook, Object paraObject) {
		if (paraObject instanceof ParaObject) {
			Map<String, Object> props = ParaObjectUtils.getAnnotatedFields((ParaObject) paraObject, null, false);
			return matchesProp(webhook, props);
		} else if (paraObject instanceof Map) {
			return matchesProp(webhook, (Map<String, Object>) paraObject);
		}
		return false;
	}

	/**
	 * Matches a property filter against a map of properties.
	 * @param webhook the webhook
	 * @param props the properties
	 * @return true if matches
	 */
	private static boolean matchesProp(Webhook webhook, Map<String, Object> props) {
		String propName = StringUtils.substringBefore(webhook.getPropertyFilter(), ":");
		String propValue = StringUtils.substringAfter(webhook.getPropertyFilter(), ":");
		Set<String> vals = new LinkedHashSet<>(List.of(StringUtils.split(propValue, ",|", 50)));
		boolean matchAll = Strings.CS.contains(propValue, ",");
		if (props.containsKey(propName)) {
			Object v = props.get(propName);
			if ("-".equals(propValue) && (v == null || StringUtils.isBlank(v.toString())
					|| (v instanceof Collection && ((Collection<?>) v).isEmpty()))) {
				return true;
			}
			if (v instanceof Collection) {
				if (matchAll) {
					try {
						return ((Collection<?>) v).containsAll(vals);
					} catch (Exception e) {
						return false;
					}
				} else {
					for (String val : vals) {
						if (((Collection<?>) v).contains(val)) {
							return true;
						}
					}
				}
			} else {
				if (vals.size() > 1 && !matchAll) {
					for (String val : vals) {
						if (v != null && v.equals(val)) {
							return true;
						}
					}
				} else {
					return v != null && v.equals(propValue);
				}
			}
		}
		return false;
	}

	/**
	 * Returns the secret key for the app or the webhook.
	 * If the webhook secret is {@code "{{secretKey}}"}, the app's secret key is used instead.
	 * @param webhook the webhook
	 * @return secret key
	 */
	private String resolveSecret(Webhook webhook) {
		if ("{{secretKey}}".equals(webhook.getSecret())) {
			App app = Para.getDAO().read(App.id(webhook.getAppid()));
			if (app != null) {
				return app.getSecret();
			}
		}
		return webhook.getSecret();
	}
}
