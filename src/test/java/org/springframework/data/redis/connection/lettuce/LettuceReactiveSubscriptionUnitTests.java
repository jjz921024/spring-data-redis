/*
 * Copyright 2018-2022 the original author or authors.
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
package org.springframework.data.redis.connection.lettuce;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.data.redis.util.ByteUtils.*;

import io.lettuce.core.RedisConnectionException;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import io.lettuce.core.pubsub.api.reactive.RedisPubSubReactiveCommands;
import reactor.core.Disposable;
import reactor.core.publisher.DirectProcessor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.nio.ByteBuffer;
import java.util.concurrent.CancellationException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.connection.ReactiveSubscription.Message;
import org.springframework.data.redis.connection.ReactiveSubscription.PatternMessage;
import org.springframework.data.redis.connection.SubscriptionListener;

/**
 * Unit tests for {@link LettuceReactiveSubscription}.
 *
 * @author Mark Paluch
 * @author Christoph Strobl
 */
@ExtendWith(MockitoExtension.class)
class LettuceReactiveSubscriptionUnitTests {

	private LettuceReactiveSubscription subscription;

	@Mock StatefulRedisPubSubConnection<ByteBuffer, ByteBuffer> connectionMock;
	@Mock RedisPubSubReactiveCommands<ByteBuffer, ByteBuffer> commandsMock;

	@BeforeEach
	void before() {
		when(connectionMock.reactive()).thenReturn(commandsMock);
		subscription = new LettuceReactiveSubscription(mock(SubscriptionListener.class), connectionMock,
				e -> new RedisSystemException(e.getMessage(), e));
	}

	@Test // DATAREDIS-612
	void shouldSubscribeChannels() {

		when(commandsMock.subscribe(any())).thenReturn(Mono.empty());

		Mono<Void> subscribe = subscription.subscribe(getByteBuffer("foo"), getByteBuffer("bar"));

		assertThat(subscription.getChannels()).isEmpty();

		subscribe.as(StepVerifier::create).verifyComplete();

		assertThat(subscription.getChannels()).containsOnly(getByteBuffer("foo"), getByteBuffer("bar"));
		assertThat(subscription.getPatterns()).isEmpty();
	}

	@Test // DATAREDIS-612
	void shouldSubscribeChannelsShouldFail() {

		when(commandsMock.subscribe(any())).thenReturn(Mono.error(new RedisConnectionException("Foo")));

		Mono<Void> subscribe = subscription.subscribe(getByteBuffer("foo"), getByteBuffer("bar"));

		subscribe.as(StepVerifier::create).expectError(RedisSystemException.class).verify();
	}

	@Test // DATAREDIS-612
	void shouldSubscribePatterns() {

		when(commandsMock.psubscribe(any())).thenReturn(Mono.empty());

		Mono<Void> subscribe = subscription.pSubscribe(getByteBuffer("foo"), getByteBuffer("bar"));

		assertThat(subscription.getPatterns()).isEmpty();

		subscribe.as(StepVerifier::create).verifyComplete();

		assertThat(subscription.getPatterns()).containsOnly(getByteBuffer("foo"), getByteBuffer("bar"));
		assertThat(subscription.getChannels()).isEmpty();
	}

	@Test // DATAREDIS-612
	void shouldUnsubscribeChannels() {

		when(commandsMock.subscribe(any())).thenReturn(Mono.empty());
		when(commandsMock.unsubscribe(any())).thenReturn(Mono.empty());
		subscription.subscribe(getByteBuffer("foo"), getByteBuffer("bar")).as(StepVerifier::create).verifyComplete();

		subscription.unsubscribe().as(StepVerifier::create).verifyComplete();

		assertThat(subscription.getChannels()).isEmpty();
		verify(commandsMock).unsubscribe(any());
	}

	@Test // DATAREDIS-612
	void shouldUnsubscribePatterns() {

		when(commandsMock.psubscribe(any())).thenReturn(Mono.empty());
		when(commandsMock.punsubscribe(any())).thenReturn(Mono.empty());
		subscription.pSubscribe(getByteBuffer("foo"), getByteBuffer("bar")).as(StepVerifier::create).verifyComplete();

		subscription.pUnsubscribe().as(StepVerifier::create).verifyComplete();

		assertThat(subscription.getPatterns()).isEmpty();
		verify(commandsMock).punsubscribe(any());
	}

	@Test // DATAREDIS-612
	void shouldEmitChannelMessage() {

		when(commandsMock.subscribe(any())).thenReturn(Mono.empty());
		subscription.subscribe(getByteBuffer("foo"), getByteBuffer("bar")).as(StepVerifier::create).verifyComplete();

		DirectProcessor<io.lettuce.core.pubsub.api.reactive.ChannelMessage<ByteBuffer, ByteBuffer>> emitter = DirectProcessor
				.create();
		when(commandsMock.observeChannels()).thenReturn(emitter);
		when(commandsMock.observePatterns()).thenReturn(Flux.empty());

		subscription.receive().as(StepVerifier::create).then(() -> {

			emitter.onNext(createChannelMessage("other", "body"));
			emitter.onNext(createChannelMessage("foo", "body"));
		}).assertNext(msg -> {
			assertThat(msg.getChannel()).isEqualTo(getByteBuffer("foo"));
		}).thenCancel().verify();
	}

	@Test // DATAREDIS-612
	void shouldEmitPatternMessage() {

		when(commandsMock.psubscribe(any())).thenReturn(Mono.empty());
		subscription.pSubscribe(getByteBuffer("foo*"), getByteBuffer("bar*")).as(StepVerifier::create).verifyComplete();

		DirectProcessor<io.lettuce.core.pubsub.api.reactive.PatternMessage<ByteBuffer, ByteBuffer>> emitter = DirectProcessor
				.create();
		when(commandsMock.observeChannels()).thenReturn(Flux.empty());
		when(commandsMock.observePatterns()).thenReturn(emitter);

		subscription.receive().as(StepVerifier::create).then(() -> {

			emitter.onNext(createPatternMessage("other*", "channel", "body"));
			emitter.onNext(createPatternMessage("foo*", "foo", "body"));
		}).assertNext(msg -> {

			assertThat(((PatternMessage) msg).getPattern()).isEqualTo(getByteBuffer("foo*"));
			assertThat(msg.getChannel()).isEqualTo(getByteBuffer("foo"));
		}).thenCancel().verify();
	}

	@Test // DATAREDIS-612
	void shouldEmitError() {

		when(commandsMock.subscribe(any())).thenReturn(Mono.empty());
		subscription.subscribe(getByteBuffer("foo"), getByteBuffer("bar")).as(StepVerifier::create).verifyComplete();

		DirectProcessor<io.lettuce.core.pubsub.api.reactive.ChannelMessage<ByteBuffer, ByteBuffer>> emitter = DirectProcessor
				.create();
		when(commandsMock.observeChannels()).thenReturn(emitter);
		when(commandsMock.observePatterns()).thenReturn(Flux.empty());

		subscription.receive().as(StepVerifier::create).then(() -> {

			emitter.onError(new RedisConnectionException("foo"));
		}).expectError(RedisSystemException.class).verify();
	}

	@Test // DATAREDIS-612
	void shouldTerminateActiveSubscriptions() {

		when(commandsMock.psubscribe(any())).thenReturn(Mono.empty());
		when(commandsMock.punsubscribe(any())).thenReturn(Mono.empty());
		subscription.pSubscribe(getByteBuffer("foo*")).as(StepVerifier::create).verifyComplete();

		when(commandsMock.observeChannels()).thenReturn(Flux.never());
		when(commandsMock.observePatterns()).thenReturn(Flux.never());

		subscription.receive().as(StepVerifier::create).then(() -> {
			subscription.cancel().subscribe();
		}).expectError(CancellationException.class).verify();

		assertThat(subscription.getPatterns()).isEmpty();
	}

	@Test // DATAREDIS-612
	void cancelledSubscriptionShouldUnregisterDownstream() {

		DirectProcessor<io.lettuce.core.pubsub.api.reactive.PatternMessage<ByteBuffer, ByteBuffer>> emitter = DirectProcessor
				.create();

		when(commandsMock.psubscribe(any())).thenReturn(Mono.empty());
		subscription.pSubscribe(getByteBuffer("foo*")).as(StepVerifier::create).verifyComplete();

		when(commandsMock.observeChannels()).thenReturn(Flux.never());
		when(commandsMock.observePatterns()).thenReturn(emitter);

		Flux<Message<ByteBuffer, ByteBuffer>> receive = subscription.receive();
		Disposable subscribe = receive.subscribe();

		assertThat(emitter.downstreamCount()).isEqualTo(1);

		subscribe.dispose();
		assertThat(emitter.downstreamCount()).isEqualTo(0);
	}

	private static io.lettuce.core.pubsub.api.reactive.ChannelMessage<ByteBuffer, ByteBuffer> createChannelMessage(
			String channel, String message) {

		return new io.lettuce.core.pubsub.api.reactive.ChannelMessage<>(getByteBuffer(channel), getByteBuffer(message));
	}

	private static io.lettuce.core.pubsub.api.reactive.PatternMessage<ByteBuffer, ByteBuffer> createPatternMessage(
			String pattern, String channel, String message) {

		return new io.lettuce.core.pubsub.api.reactive.PatternMessage<>(getByteBuffer(pattern), getByteBuffer(channel),
				getByteBuffer(message));
	}
}
