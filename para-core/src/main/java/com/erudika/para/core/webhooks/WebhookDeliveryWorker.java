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

import com.erudika.para.core.utils.Config;
import com.erudika.para.core.utils.Para;
import com.erudika.para.core.utils.ParaObjectUtils;
import com.fasterxml.jackson.databind.ObjectReader;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A dedicated worker that pulls messages from a queue and delivers webhook payloads.
 *
 * <p>This class was extracted from {@link com.erudika.para.core.queue.River}
 * to separate webhook delivery from the data import pipeline. Unlike River,
 * this worker only processes messages of type {@code "webhookpayload"} and
 * ignores all other message types.</p>
 *
 * @author Para
 */
public abstract class WebhookDeliveryWorker implements Runnable {

	private static final Logger logger = LoggerFactory.getLogger(WebhookDeliveryWorker.class);

	private final WebhookDeliveryService deliveryService;

	/**
	 * Creates a new worker with the default delivery service.
	 */
	protected WebhookDeliveryWorker() {
		this(new WebhookDeliveryService());
	}

	/**
	 * Creates a new worker with a custom delivery service.
	 * @param deliveryService the delivery service to use
	 */
	protected WebhookDeliveryWorker(WebhookDeliveryService deliveryService) {
		this.deliveryService = deliveryService;
	}

	/**
	 * Returns a list of messages pulled from queue.
	 * @return a list of messages
	 */
	public abstract List<String> pullMessages();

	/**
	 * Returns the delivery service used by this worker.
	 * @return the delivery service
	 */
	protected WebhookDeliveryService getDeliveryService() {
		return deliveryService;
	}

	@Override
	public void run() {
		ObjectReader jreader = ParaObjectUtils.getJsonReader(Map.class);
		int idleCount = 0;

		try {
			while (!Thread.interrupted()) {
				logger.debug("Webhook worker waiting {}s for messages...",
						Para.getConfig().queuePollingIntervalSec());
				int delivered = 0;
				List<String> msgs = Collections.emptyList();
				if (Para.isHealthy()) {
					try {
						msgs = pullMessages();
						logger.debug("Webhook worker pulled {} messages from queue.", msgs.size());

						for (final String msg : msgs) {
							logger.debug("Webhook worker message: {}", msg);
							if (Strings.CS.contains(msg, Config._APPID)
									&& Strings.CS.contains(msg, Config._TYPE)
									&& Strings.CS.contains(msg, "webhookpayload")) {
								Map<String, Object> parsed = jreader.readValue(msg);
								String type = (String) parsed.get(Config._TYPE);
								if ("webhookpayload".equals(type)) {
									String id = parsed.containsKey(Config._ID)
											? (String) parsed.get(Config._ID) : null;
									String appid = (String) parsed.get(Config._APPID);
									if (!StringUtils.isBlank(appid) && !StringUtils.isBlank(id)) {
										delivered += deliveryService.deliverPayload(appid, id, parsed);
									}
								}
							}
						}
					} catch (Exception e) {
						logger.error("Webhook worker batch processing failed:", e);
					}
				}

				if (delivered > 0) {
					logger.debug("Webhook worker summary: {} webhooks delivered.", delivered);
					idleCount = 0;
				} else if (msgs.isEmpty()) {
					idleCount++;
					int sleep = Para.getConfig().queuePollingWaitSec();
					if (sleep > 0 && idleCount >= 3) {
						logger.debug("Webhook worker queue is empty. Sleeping for {}s...", sleep);
						Thread.sleep(sleep * 1000L);
					}
				}
			}
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}
}
