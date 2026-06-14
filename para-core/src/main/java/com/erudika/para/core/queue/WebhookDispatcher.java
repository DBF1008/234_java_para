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
package com.erudika.para.core.queue;

import com.erudika.para.core.Webhook;
import com.erudika.para.core.utils.Para;
import com.erudika.para.core.utils.Utils;
import java.nio.charset.Charset;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.core5.http.HttpHeaders;
import org.apache.hc.core5.http.HttpRequest;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Delivers webhook payloads pulled from the queue to their target URLs.
 * This is the worker-side counterpart to {@link Webhook} (which builds and enqueues payloads):
 * it owns the HTTP delivery, the repeated-delivery attempts and the failed-delivery / auto-disable logic.
 * Whether a node runs this dispatcher at all is decided by the caller (the queue consumer / "worker" role),
 * so {@link #deliver(String, String, Map)} unconditionally delivers the payload it is given.
 * @author Alex Bogdanovski [alex@erudika.com]
 */
public final class WebhookDispatcher {

	private static final Logger logger = LoggerFactory.getLogger(WebhookDispatcher.class);
	private static final CloseableHttpClient HTTP;

	static {
		int timeout = 10;
		HTTP = HttpClientBuilder.create().
				setConnectionReuseStrategy((HttpRequest hr, HttpResponse hr1, HttpContext hc) -> false).
				setDefaultRequestConfig(RequestConfig.custom().
						setConnectionRequestTimeout(timeout, TimeUnit.SECONDS).
						build()).
				build();
	}

	private WebhookDispatcher() {
		// utility class
	}

	/**
	 * Delivers a single webhook payload to its target URL. Sends a POST to {@code targetUrl}.
	 * The same payload is delivered {@code repeatedDeliveryAttempts} times, sequentially (capped at 100).
	 * @param appid appid
	 * @param id webhook id
	 * @param parsed payload with metadata, as pulled from the queue
	 * @return 1 if a delivery was attempted, 0 otherwise
	 */
	public static int deliver(String appid, String id, Map<String, Object> parsed) {
		if (!parsed.containsKey("targetUrl") || StringUtils.isBlank(id) || parsed.isEmpty()) {
			return 0;
		}
		try {
			String targetUrl = StringUtils.trimToEmpty((String) parsed.get("targetUrl"));
			int repeatDelivery = Math.min(100, Math.max(1,
					Math.abs(NumberUtils.toInt(parsed.get("repeatedDeliveryAttempts") + "", 1))));
			// deliver the same payload sequentially - "repeatedDeliveryAttempts" times.
			// a fresh request is built per attempt because a sent HttpPost/entity cannot be reused.
			for (int r = 0; r < repeatDelivery; r++) {
				deliverOnce(appid, id, parsed, targetUrl);
			}
			return 1;
		} catch (Exception e) {
			registerFailure(appid, id);
			logger.error("Webhook payload was not delivered:", e);
		}
		return 0;
	}

	private static void deliverOnce(String appid, String id, Map<String, Object> parsed, String targetUrl) {
		boolean urlEncoded = (boolean) parsed.get("urlEncoded");
		String payload = (String) parsed.get("payload");
		HttpPost postToTarget = new HttpPost(targetUrl);
		postToTarget.addHeader("User-Agent", "Para Webhook Dispacher " + Para.getVersion());
		postToTarget.setHeader(HttpHeaders.CONTENT_TYPE, urlEncoded
				? "application/x-www-form-urlencoded" : "application/json");
		postToTarget.setHeader("X-Webhook-Signature", (String) parsed.get("signature"));
		postToTarget.setHeader("X-Para-Event", (String) parsed.get("event"));
		if (urlEncoded) {
			postToTarget.setEntity(new StringEntity("payload=".concat(Utils.urlEncode(payload)),
					Charset.forName(Para.getConfig().defaultEncoding())));
		} else {
			postToTarget.setEntity(new StringEntity(payload, Charset.forName(Para.getConfig().defaultEncoding())));
		}
		try {
			HTTP.execute(postToTarget, resp -> {
				if (resp != null && Math.abs(resp.getCode() - 200) > 10) {
					registerFailure(appid, id);
					logger.info("Webhook {} delivery failed! {} responded with code {} {} instead of 2xx.", id,
							targetUrl, resp.getCode(), resp.getReasonPhrase());
					return resp.getReasonPhrase();
				} else {
					logger.debug("Webhook {} delivered to {} successfully.", id, targetUrl);
				}
				return "OK";
			});
		} catch (Exception e) {
			registerFailure(appid, id);
			logger.info("Webhook {} not delivered! {} isn't responding.", id, targetUrl);
		}
	}

	private static void registerFailure(String appid, String id) {
		// count failed deliveries and disable that webhook object after X failed attempts
		String countId = "failed_webhook_count" + Para.getConfig().separator() + id;
		Integer count = Para.getCache().get(appid, countId);
		if (count == null) {
			count = 0;
		}
		if (count >= (Para.getConfig().maxFailedWebhookAttempts() - 1)) {
			Webhook hook = Para.getDAO().read(appid, id);
			if (hook != null) {
				hook.setActive(false);
				hook.setTooManyFailures(true);
				Para.getDAO().update(appid, hook);
				Para.getCache().remove(appid, countId);
				logger.info("Webhook {} was disabled - a maximum of {} failed deliveries was reached.",
						id, Para.getConfig().maxFailedWebhookAttempts());
			}
		} else {
			Para.getCache().put(appid, countId, ++count);
		}
	}
}
