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
package com.erudika.para.server.queue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A tiny local HTTP server used as a webhook delivery target in tests. Records every request it
 * receives (count, headers and body) and replies with a configurable status code.
 */
final class WebhookTestServer {

	private final HttpServer server;
	private final AtomicInteger hits = new AtomicInteger(0);
	private final List<RequestRecord> requests = new CopyOnWriteArrayList<>();
	private volatile int responseCode = 200;

	WebhookTestServer(String path) throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext(path, this::handle);
		server.start();
	}

	private void handle(HttpExchange ex) throws IOException {
		String body;
		try (InputStream in = ex.getRequestBody()) {
			body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		RequestRecord rec = new RequestRecord();
		rec.body = body;
		rec.signature = ex.getRequestHeaders().getFirst("X-Webhook-Signature");
		rec.event = ex.getRequestHeaders().getFirst("X-Para-Event");
		rec.contentType = ex.getRequestHeaders().getFirst("Content-Type");
		requests.add(rec);
		hits.incrementAndGet();
		byte[] resp = ("status:" + responseCode).getBytes(StandardCharsets.UTF_8);
		ex.sendResponseHeaders(responseCode, resp.length);
		try (OutputStream os = ex.getResponseBody()) {
			os.write(resp);
		}
	}

	String url(String path) {
		return "http://127.0.0.1:" + server.getAddress().getPort() + path;
	}

	void setResponseCode(int code) {
		this.responseCode = code;
	}

	int hitCount() {
		return hits.get();
	}

	List<RequestRecord> requests() {
		return requests;
	}

	void stop() {
		server.stop(0);
	}

	static final class RequestRecord {
		private String body;
		private String signature;
		private String event;
		private String contentType;

		String getBody() {
			return body;
		}

		String getSignature() {
			return signature;
		}

		String getEvent() {
			return event;
		}

		String getContentType() {
			return contentType;
		}
	}
}
