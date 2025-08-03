package org.springframework.batch.item.redis;

import org.springframework.batch.item.KeyValueItemWriter;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.util.Pair;
import org.springframework.util.Assert;

import java.util.ArrayList;
import java.util.List;

public class RedisPipelineItemWriter<K, V> extends KeyValueItemWriter<K, V> {

	private RedisTemplate<K, V> redisTemplate;

	private final List<Pair<K, V>> buffer = new ArrayList<>();

	@Override
	protected void writeKeyValue(K key, V value) {
		this.buffer.add(Pair.of(key, value));
	}

	@Override
	protected void init() {
		Assert.notNull(this.redisTemplate, "RedisTemplate must not be null");
	}

	@Override
	protected void flush() throws Exception {
		if (this.buffer.isEmpty()) {
			return;
		}

		this.redisTemplate.executePipelined(sessionCallback());

		this.buffer.clear();
	}

	/**
	 * Set the {@link RedisTemplate} to use.
	 * @param redisTemplate the template to use
	 */
	public void setRedisTemplate(RedisTemplate<K, V> redisTemplate) {
		this.redisTemplate = redisTemplate;
	}

	private SessionCallback<Object> sessionCallback() {
		return new SessionCallback<>() {

			@SuppressWarnings("unchecked")
			@Override
			public Object execute(RedisOperations operations) throws DataAccessException {
				if (RedisPipelineItemWriter.this.delete) {
					executeDeleteOperations(operations);
				}
				else {
					executeSetOperations(operations);
				}
				return null;
			}
		};
	}

	private void executeDeleteOperations(RedisOperations<K, V> operations) {
		for (Pair<K, V> item : this.buffer) {
			operations.delete(item.getFirst());
		}
	}

	private void executeSetOperations(RedisOperations<K, V> operations) {
		for (Pair<K, V> item : this.buffer) {
			operations.opsForValue().set(item.getFirst(), item.getSecond());
		}
	}

}
