/*
 * Copyright 2013-2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.gateway.filter;

import java.net.URI;
import java.util.List;

import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.NettyPipeline;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.client.HttpClientResponse;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.gateway.config.HttpClientProperties;
import org.springframework.cloud.gateway.filter.headers.HttpHeadersFilter;
import org.springframework.cloud.gateway.filter.headers.HttpHeadersFilter.Type;
import org.springframework.cloud.gateway.support.TimeoutException;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.NettyDataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.AbstractServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

import static org.springframework.cloud.gateway.filter.headers.HttpHeadersFilter.filterRequest;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.CLIENT_RESPONSE_ATTR;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.CLIENT_RESPONSE_CONN_ATTR;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.CLIENT_RESPONSE_HEADER_NAMES;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.ORIGINAL_RESPONSE_CONTENT_TYPE_ATTR;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.PRESERVE_HOST_HEADER_ATTRIBUTE;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.isAlreadyRouted;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.setAlreadyRouted;

/**
 * 低优先级的一个 filter
 * @author Spencer Gibb
 * @author Biju Kunjummen
 */
public class NettyRoutingFilter implements GlobalFilter, Ordered {

	private static final Log log = LogFactory.getLog(NettyRoutingFilter.class);

	private final HttpClient httpClient;

	private final ObjectProvider<List<HttpHeadersFilter>> headersFiltersProvider;

	private final HttpClientProperties properties;

	// do not use this headersFilters directly, use getHeadersFilters() instead.
	private volatile List<HttpHeadersFilter> headersFilters;

	public NettyRoutingFilter(HttpClient httpClient,
			ObjectProvider<List<HttpHeadersFilter>> headersFiltersProvider,
			HttpClientProperties properties) {
		this.httpClient = httpClient;
		this.headersFiltersProvider = headersFiltersProvider;
		this.properties = properties;
	}

	public List<HttpHeadersFilter> getHeadersFilters() {
		if (headersFilters == null) {
			headersFilters = headersFiltersProvider.getIfAvailable();
		}
		return headersFilters;
	}

	@Override
	public int getOrder() {
		return Ordered.LOWEST_PRECEDENCE;
	}

	@Override
	@SuppressWarnings("Duplicates")
	public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
		URI requestUrl = exchange.getRequiredAttribute(GATEWAY_REQUEST_URL_ATTR);

		/**
		 * 获取访问的url地址
		 */
		String scheme = requestUrl.getScheme();

		/**
		 * 判断这次请求是否被处理过，请求协议是否是http/https
		 * 如果满足则不处理
		 */
		if (isAlreadyRouted(exchange)
				|| (!"http".equals(scheme) && !"https".equals(scheme))) {
			return chain.filter(exchange);
		}
		// 设置已经路由
		setAlreadyRouted(exchange);

		/**
		 * 获取ServerHttpRequest，相当于HttpServletRequest请求，在gateway
		 * 中使用的是webflux 做的web服务，所有处理有点变化
		 */
		ServerHttpRequest request = exchange.getRequest();

		/**
		 * 获取 请求方式
		 */
		final HttpMethod method = HttpMethod.valueOf(request.getMethodValue());
		final String url = requestUrl.toASCIIString();

		/**
		 * 获取 HttpHeaders
		 */
		HttpHeaders filtered = filterRequest(getHeadersFilters(), exchange);

		final DefaultHttpHeaders httpHeaders = new DefaultHttpHeaders();
		// 创建 Netty Request Header 对象( io.netty.handler.codec.http.DefaultHttpHeaders )，
		// 将请求的 Header 设置给它。
		filtered.forEach(httpHeaders::set);

		boolean preserveHost = exchange
				.getAttributeOrDefault(PRESERVE_HOST_HEADER_ATTRIBUTE, false);

		/**
		 * 这里开始请求
		 * httpClient 这次采用httpclient 去代理访问要去代理的地址，相当于网关去
		 * 帮你请求了一次
		 */
		Flux<HttpClientResponse> responseFlux = this.httpClient.headers(headers -> {
			headers.add(httpHeaders);
			// Will either be set below, or later by Netty
			headers.remove(HttpHeaders.HOST);
			/**
			 * 判断请求主机是否一致，一起是访问地址和路由地址是否是一个
			 * ip 不是则需要替换
			 */
			if (preserveHost) {
				String host = request.getHeaders().getFirst(HttpHeaders.HOST);
				headers.add(HttpHeaders.HOST, host);
			}
//			调用 HttpClient#request(HttpMethod, String, Function) 方法，请求后端 Http 服务
		}).request(method).uri(url).send((req, nettyOutbound) -> {
			if (log.isTraceEnabled()) {
				nettyOutbound.withConnection(connection -> log.trace(
						"outbound route: " + connection.channel().id().asShortText()
								+ ", inbound: " + exchange.getLogPrefix()));
			}
			return nettyOutbound.options(NettyPipeline.SendOptions::flushOnEach)
					// 请求后端 http 服务
					.send(
					request.getBody().map(dataBuffer -> ((NettyDataBuffer) dataBuffer)
							.getNativeBuffer()));
		}).responseConnection((res, connection) -> {
			// 请求成功
			// Defer committing the response until all route filters have run
			// Put client response as ServerWebExchange attribute and write
			// response later NettyWriteResponseFilter
			// 设置 Response 到 CLIENT_RESPONSE_ATTR
//			续 NettyWriteResponseFilter 将 Netty Response 写回给客户端
			exchange.getAttributes().put(CLIENT_RESPONSE_ATTR, res);
			exchange.getAttributes().put(CLIENT_RESPONSE_CONN_ATTR, connection);

			/**
			 * 异步获取 httpClient 访问的响应结果，下面就是对响应结果的封装
			 */
			ServerHttpResponse response = exchange.getResponse();
			// put headers and status so filters can modify the response
			HttpHeaders headers = new HttpHeaders();

			res.responseHeaders()
					.forEach(entry -> headers.add(entry.getKey(), entry.getValue()));

			String contentTypeValue = headers.getFirst(HttpHeaders.CONTENT_TYPE);
			if (StringUtils.hasLength(contentTypeValue)) {
				exchange.getAttributes().put(ORIGINAL_RESPONSE_CONTENT_TYPE_ATTR,
						contentTypeValue);
			}

			HttpStatus status = HttpStatus.resolve(res.status().code());
			if (status != null) {
				response.setStatusCode(status);
			}
			else if (response instanceof AbstractServerHttpResponse) {
				// https://jira.spring.io/browse/SPR-16748
				((AbstractServerHttpResponse) response)
						.setStatusCodeValue(res.status().code());
			}
			else {
				// TODO: log warning here, not throw error?
				throw new IllegalStateException("Unable to set status code on response: "
						+ res.status().code() + ", " + response.getClass());
			}

			// make sure headers filters run after setting status so it is
			// available in response
			HttpHeaders filteredResponseHeaders = HttpHeadersFilter
					.filter(getHeadersFilters(), headers, exchange, Type.RESPONSE);

			if (!filteredResponseHeaders.containsKey(HttpHeaders.TRANSFER_ENCODING)
					&& filteredResponseHeaders.containsKey(HttpHeaders.CONTENT_LENGTH)) {
				// It is not valid to have both the transfer-encoding header and
				// the content-length header.
				// Remove the transfer-encoding header in the response if the
				// content-length header is present.
				response.getHeaders().remove(HttpHeaders.TRANSFER_ENCODING);
			}

			exchange.getAttributes().put(CLIENT_RESPONSE_HEADER_NAMES,
					filteredResponseHeaders.keySet());

			response.getHeaders().putAll(filteredResponseHeaders);

			return Mono.just(res);
		});

		if (properties.getResponseTimeout() != null) {
			responseFlux = responseFlux.timeout(properties.getResponseTimeout(),
					Mono.error(new TimeoutException("Response took longer than timeout: "
							+ properties.getResponseTimeout())))
					.onErrorMap(TimeoutException.class,
							th -> new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT,
									th.getMessage(), th));
		}

//		提交过滤器链继续过滤
		return responseFlux.then(chain.filter(exchange));
	}

}
