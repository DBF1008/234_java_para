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
package com.erudika.para.core;

import com.erudika.para.core.annotations.Stored;
import com.erudika.para.core.utils.Para;
import com.erudika.para.core.utils.Utils;
import com.erudika.para.core.webhooks.WebhookEventDispatcher;
import jakarta.validation.constraints.NotBlank;
import java.util.LinkedList;
import java.util.List;
import org.apache.commons.lang3.StringUtils;
import org.hibernate.validator.constraints.URL;

/**
 * Represents a webhook registration.
 * @author Alex Bogdanovski [alex@erudika.com]
 */
public class Webhook extends Sysprop {
	private static final long serialVersionUID = 1L;

	/**
	 * The target URL.
	 */
	@Stored @NotBlank @URL private String targetUrl;
	/**
	 * The secret key.
	 */
	@Stored private String secret;
	/**
	 * The type filter.
	 */
	@Stored private String typeFilter;
	/**
	 * The property filter.
	 */
	@Stored private String propertyFilter;
	/**
	 * The URL encoded flag.
	 */
	@Stored private Boolean urlEncoded;
	/**
	 * The active flag.
	 */
	@Stored private Boolean active;
	/**
	 * The too many failures flag.
	 */
	@Stored private Boolean tooManyFailures;

	/**
	 * The create flag.
	 */
	@Stored private Boolean create;
	/**
	 * The update flag.
	 */
	@Stored private Boolean update;
	/**
	 * The delete flag.
	 */
	@Stored private Boolean delete;
	/**
	 * The create all flag.
	 */
	@Stored private Boolean createAll;
	/**
	 * The update all flag.
	 */
	@Stored private Boolean updateAll;
	/**
	 * The delete all flag.
	 */
	@Stored private Boolean deleteAll;
	/**
	 * The custom events list.
	 */
	@Stored private List<String> customEvents;
	/**
	 * The triggered event name.
	 */
	@Stored private String triggeredEvent;
	/**
	 * The custom payload object.
	 */
	@Stored private Object customPayload;
	/**
	 * The repeated delivery attempts number.
	 */
	@Stored private Integer repeatedDeliveryAttempts; // send the same payload X times

	/**
	 * No-args constructor.
	 */
	public Webhook() {
		this(null);
	}

	/**
	 * Default constructor.
	 * @param targetUrl the URL where the payload will be sent
	 */
	public Webhook(String targetUrl) {
		this.targetUrl = targetUrl;
		this.urlEncoded = false;
		this.create = false;
		this.update = false;
		this.delete = false;
		this.createAll = false;
		this.updateAll = false;
		this.deleteAll = false;
		this.active = false;
		this.tooManyFailures = false;
		this.repeatedDeliveryAttempts = 1;
	}

	/**
	 * Returns the target URL.
	 * @return the target URL
	 */
	public String getTargetUrl() {
		return targetUrl;
	}

	/**
	 * Sets the target URL.
	 * @param targetUrl target URL value
	 */
	public void setTargetUrl(String targetUrl) {
		this.targetUrl = targetUrl;
	}

	/**
	 * Returns the webhook secret key.
	 * @return the webhook secret key
	 */
	public String getSecret() {
		return secret;
	}

	/**
	 * Sets the webhook secret key.
	 * @param secret webhook secret key
	 */
	public void setSecret(String secret) {
		this.secret = secret;
	}

	/**
	 * Returns the type filter.
	 * @return type filter
	 */
	public String getTypeFilter() {
		return typeFilter;
	}

	/**
	 * Sets the type filter.
	 * @param typeFilter type filter
	 */
	public void setTypeFilter(String typeFilter) {
		this.typeFilter = typeFilter;
	}

	/**
	 * Returns the property filter.
	 * @return property filter
	 */
	public String getPropertyFilter() {
		return propertyFilter;
	}

	/**
	 * Sets the property filter.
	 * @param propertyFilter property filter
	 */
	public void setPropertyFilter(String propertyFilter) {
		this.propertyFilter = propertyFilter;
	}

	/**
	 * Returns true if the payload should be URL encoded.
	 * @return if false, JSON is returned, otherwise x-www-form-urlencoded
	 */
	public Boolean getUrlEncoded() {
		return urlEncoded;
	}

	/**
	 * Sets the URL encoded flag.
	 * @param urlEncoded false for JSON payloads
	 */
	public void setUrlEncoded(Boolean urlEncoded) {
		this.urlEncoded = urlEncoded;
	}

	/**
	 * Returns true if the webhook is active.
	 * @return if false, nothing is sent to {@code targetUrl}.
	 */
	public Boolean getActive() {
		return active;
	}

	/**
	 * Sets the active flag.
	 * @param active if false, nothing is sent to {@code targetUrl}.
	 */
	public void setActive(Boolean active) {
		this.active = active;
	}

	/**
	 * Returns true if the webhook has too many failures.
	 * @return true if this was disabled by the system
	 */
	public Boolean getTooManyFailures() {
		return tooManyFailures;
	}

	/**
	 * Sets the too many failures flag.
	 * @param tooManyFailures don't set this manually
	 */
	public void setTooManyFailures(Boolean tooManyFailures) {
		this.tooManyFailures = tooManyFailures;
	}

	/**
	 * Returns true if subscribed to create events.
	 * @return true if subscribed to DAO.create() methods
	 */
	public Boolean getCreate() {
		return create;
	}

	/**
	 * Sets the create flag.
	 * @param create set to true to subscribe to create methods
	 */
	public void setCreate(Boolean create) {
		this.create = create;
	}

	/**
	 * Returns true if subscribed to update events.
	 * @return true if subscribed to DAO.update() methods
	 */
	public Boolean getUpdate() {
		return update;
	}

	/**
	 * Sets the update flag.
	 * @param update set to true to subscribe to update methods
	 */
	public void setUpdate(Boolean update) {
		this.update = update;
	}

	/**
	 * Returns true if subscribed to delete events.
	 * @return true if subscribed to DAO.delete() methods
	 */
	public Boolean getDelete() {
		return delete;
	}

	/**
	 * Sets the delete flag.
	 * @param delete set to true to subscribe to delete methods
	 */
	public void setDelete(Boolean delete) {
		this.delete = delete;
	}

	/**
	 * Returns true if subscribed to create all events.
	 * @return true if subscribed to DAO.createAll() methods
	 */
	public Boolean getCreateAll() {
		return createAll;
	}

	/**
	 * Sets the create all flag.
	 * @param createAll set to true to subscribe to createAll methods
	 */
	public void setCreateAll(Boolean createAll) {
		this.createAll = createAll;
	}

	/**
	 * Returns true if subscribed to update all events.
	 * @return true if subscribed to DAO.updateAll() methods
	 */
	public Boolean getUpdateAll() {
		return updateAll;
	}

	/**
	 * Sets the update all flag.
	 * @param updateAll set to true to subscribe to updateAll methods
	 */
	public void setUpdateAll(Boolean updateAll) {
		this.updateAll = updateAll;
	}

	/**
	 * Returns true if subscribed to delete all events.
	 * @return true if subscribed to DAO.deleteAll() methods
	 */
	public Boolean getDeleteAll() {
		return deleteAll;
	}

	/**
	 * Sets the delete all flag.
	 * @param deleteAll set to true to subscribe to deleteAll methods
	 */
	public void setDeleteAll(Boolean deleteAll) {
		this.deleteAll = deleteAll;
	}

	/**
	 * Returns the list of custom events.
	 * @return the name of the custom event
	 */
	public List<String> getCustomEvents() {
		if (customEvents == null) {
			customEvents = new LinkedList<>();
		}
		return customEvents;
	}

	/**
	 * Sets the list of custom events.
	 * @param customEvents set the name of the custom event
	 */
	public void setCustomEvents(List<String> customEvents) {
		this.customEvents = customEvents;
	}

	/**
	 * Returns the triggered event name.
	 * @return the name of the custom event to be triggered
	 */
	public String getTriggeredEvent() {
		return triggeredEvent;
	}

	/**
	 * Sets the triggered event name.
	 * @param triggeredEvent custom event name
	 */
	public void setTriggeredEvent(String triggeredEvent) {
		this.triggeredEvent = triggeredEvent;
		if (!StringUtils.isBlank(triggeredEvent) && StringUtils.isBlank(targetUrl)) {
			// get around the validation
			setTargetUrl("https://para");
		}
	}

	/**
	 * Returns the custom payload.
	 * @return the custom payload object
	 */
	public Object getCustomPayload() {
		return customPayload;
	}

	/**
	 * Sets the custom payload.
	 * @param customPayload set the custom payload object which will be sent when a custom event is triggered
	 */
	public void setCustomPayload(Object customPayload) {
		this.customPayload = customPayload;
	}

	/**
	 * Returns the number of repeated delivery attempts.
	 * @return the number of times to deliver the same payload to target.
	 */
	public Integer getRepeatedDeliveryAttempts() {
		if (repeatedDeliveryAttempts == null) {
			return 1;
		}
		return Math.abs(repeatedDeliveryAttempts);
	}

	/**
	 * Sets the number of repeated delivery attempts.
	 * @param repeatedDeliveryAttempts the number of times to deliver the same payload to target.
	 */
	public void setRepeatedDeliveryAttempts(Integer repeatedDeliveryAttempts) {
		this.repeatedDeliveryAttempts = repeatedDeliveryAttempts;
	}

	/**
	 * Resets the secret key by generating a new one.
	 */
	public void resetSecret() {
		this.secret = Utils.generateSecurityToken();
	}

	/**
	 * Updates the webhook.
	 */
	@Override
	public void update() {
		if (active) {
			this.tooManyFailures = false; // clear notification flag
		}
		triggeredEvent = null; // not used
		customPayload = null; // not used
		super.update();
	}

	/**
	 * Creates the webhook.
	 * @return the id of the created webhook
	 */
	@Override
	public String create() {
		// check if this is a trigger request for a custom event using POST /webhooks
		if (!StringUtils.isBlank(triggeredEvent) && customPayload != null) {
			WebhookEventDispatcher dispatcher = new WebhookEventDispatcher();
			dispatcher.dispatchEvent(getAppid(), "customEvents", triggeredEvent, customPayload);
			if (!StringUtils.isBlank(secret) && Utils.isValidURL(targetUrl) && !"https://para".equals(targetUrl)) {
				// support for triggering and delivering the custom event directly
				Para.getQueue().push(dispatcher.buildPayload(this, triggeredEvent, customPayload));
			}
			setId("triggered" + Para.getConfig().separator() + triggeredEvent);
			setName("This webhook object is not persisted and should be discarded.");
			setStored(false);
			setIndexed(false);
			setCached(false);
			return getId();
		}

		if (StringUtils.isBlank(secret)) {
			resetSecret();
		}
		if (create || update || delete || createAll || updateAll || deleteAll || !getCustomEvents().isEmpty()) {
			active = true;
		}
		triggeredEvent = null; // not used
		customPayload = null; // not used
		return super.create();
	}

	/**
	 * Builds the JSON payload object.
	 * @param event Para.DAO method name or custom event name
	 * @param payload payload object to convert to JSON
	 * @return the payload + metadata object as JSON string
	 */
	public String buildPayloadAsJSON(String event, Object payload) {
		return new WebhookEventDispatcher().buildPayload(this, event, payload);
	}

	/**
	 * Sends out the payload object for an event to the queue for processing.
	 * @param appid appid
	 * @param eventName event name like "create", "delete" or "customEvents"
	 * @param eventValue event value - for custom events this is the name of the custom event
	 * @param payload the payload
	 */
	public static void sendEventPayloadToQueue(String appid, String eventName, Object eventValue, Object payload) {
		new WebhookEventDispatcher().dispatchEvent(appid, eventName, eventValue, payload);
	}

	/**
	 * Returns true if the type filter matches the payload.
	 * @param webhook the webhook
	 * @param paraObjects the payload
	 * @return true if matches
	 */
	private static boolean typeFilterMatches(Webhook webhook, Object paraObjects) {
		return WebhookEventDispatcher.typeFilterMatches(webhook, paraObjects);
	}

	/**
	 * Matches a property filter against a payload.
	 * @param webhook the webhook containing the filter
	 * @param payload the payload to match against
	 * @return true if the payload matches the filter
	 */
	public static boolean propertyFilterMatches(Webhook webhook, Object payload) {
		return WebhookEventDispatcher.propertyFilterMatches(webhook, payload);
	}

}
