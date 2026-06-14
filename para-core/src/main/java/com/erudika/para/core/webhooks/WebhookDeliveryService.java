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

import com.erudika.para.core.Webhook;
import com.erudika.para.core.utils.Para;
import com.erudika.para.core.utils.Utils;
import java.nio.charset.Charset;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.IntStream;
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
 * Delivers webhook payloads via HTTP POST and tracks delivery failures.
 *
 * <p>This class was extracted from {@link com.erudika.para.core.queue.River}
 * to separate webhook delivery concerns from the data import pipeline.
 * It handles HTTP delivery, HMAC-signed headers, repeated delivery,
 * URL-encoded payloads, and automatic webhook disabling after repeated failures.</p>
 *
 * @author Para
 */
public class WebhookDeliveryService {

	private static final Logger logger = LoggerFactory.getLogger(WebhookDeliveryService.class);
	private static final int HTTP_TIMEOUT_SEC = 10;

	private final CloseableHttpClient httpClient;

	/**
	 * Creates a new delivery service with the default HTTP client.
	 */
	public WebhookDeliveryService() {
		this(WebhookDeliveryService::createDefaultHttpClient);
	}

	/**
	 * Creates a new delivery service with a custom HTTP client supplier.
	 * @param httpClientFactory a supplier that creates the HTTP client
	 */
	public WebhookDeliveryService(Supplier<CloseableHttpClient> httpClientFactory) {
		this.httpClient = httpClientFactory.get();
	}

	/**
	 * Creates the default HTTP client used for webhook delivery.
	 * @return a configured HTTP client
	 */
	public static CloseableHttpClient createDefaultHttpClient() {
		return HttpClientBuilder.create()
				.setConnectionReuseStrategy((HttpRequest hr, HttpResponse hr1, HttpContext hc) -> false)
				.setDefaultRequestConfig(RequestConfig.custom()
						.setConnectionRequestTimeout(HTTP_TIMEOUT_SEC, java.util.concurrent.TimeUnit.SECONDS)
						.build())
				.build();
	}

	/**
	 * Returns the HTTP client used by this service.
	 * @return the HTTP client
	 */
	public CloseableHttpClient getHttpClient() {
		return httpClient;
	}

	/**
	 * Delivers a webhook payload via HTTP POST to the target URL.
	 * Sends the payload with appropriate headers (signature, event, content-type)
	 * and tracks delivery failures.
	 *
	 * @param appid the application identifier
	 * @param id the webhook identifier
	 * @param parsed the parsed payload map containing targetUrl, payload, signature, etc.
	 * @return 1 if delivery was attempted, 0 if skipped
	 */
	public int deliverPayload(String appid, String id, Map<String, Object> parsed) {
		if (!Para.getConfig().webhooksEnabled() || !parsed.containsKey("targetUrl")
				|| StringUtils.isBlank(id) || parsed.isEmpty()) {
			return 0;
		}
		try {
			boolean urlEncoded = (boolean) parsed.get("urlEncoded");
			String targetUrl = StringUtils.trimToEmpty((String) parsed.get("targetUrl"));
			String payload = (String) parsed.get("payload");
			Integer repeatDelivery = Math.abs(NumberUtils.toInt(parsed.get("repeatedDeliveryAttempts") + "", 1));
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
				postToTarget.setEntity(new StringEntity(payload,
						Charset.forName(Para.getConfig().defaultEncoding())));
			}
			if (repeatDelivery > 100) {
				repeatDelivery = 100;
			}
			IntStream.range(0, Math.max(1, repeatDelivery)).parallel().forEach(r -> {
				String status = "";
				try {
					status = httpClient.execute(postToTarget, (resp1) -> {
						if (resp1 != null && Math.abs(resp1.getCode() - 200) > 10) {
							updateFailureCount(appid, id);
							logger.info("Webhook {} delivery failed! {} responded with code {} {} instead of 2xx.",
									id, targetUrl, resp1.getCode(), resp1.getReasonPhrase());
							return resp1.getReasonPhrase();
						} else {
							logger.debug("Webhook {} delivered to {} successfully.", id, targetUrl);
						}
						return "OK";
					});
				} catch (Exception e) {
					updateFailureCount(appid, id);
					logger.info("Webhook {} not delivered! {} isn't responding. {}", id, targetUrl, status);
				}
			});
			return 1;
		} catch (Exception e) {
			updateFailureCount(appid, id);
			logger.error("Webhook payload was not delivered:", e);
		}
		return 0;
	}

	/**
	 * Tracks failed delivery attempts for a webhook and disables it after reaching the threshold.
	 * Failure counts are stored in the cache. When the count reaches
	 * {@code maxFailedWebhookAttempts}, the webhook is set to inactive.
	 *
	 * @param appid the application identifier
	 * @param id the webhook identifier
	 */
	public void updateFailureCount(String appid, String id) {
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
