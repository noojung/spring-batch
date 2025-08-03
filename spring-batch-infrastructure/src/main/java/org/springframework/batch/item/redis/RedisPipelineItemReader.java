package org.springframework.batch.item.redis;

import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemStreamException;
import org.springframework.batch.item.ItemStreamReader;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.*;
import org.springframework.util.Assert;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

public class RedisPipelineItemReader<K, V> implements ItemStreamReader<V> {

	private final RedisTemplate<K, V> redisTemplate;

	private final ScanOptions scanOptions;

	private final int fetchSize;

	private final Deque<V> buffer;

	private Cursor<K> cursor;

	public RedisPipelineItemReader(RedisTemplate<K, V> redisTemplate, ScanOptions scanOptions, int fetchSize) {
		Assert.notNull(redisTemplate, "redisTemplate must not be null");
		Assert.notNull(scanOptions, "scanOptions must no be null");
		Assert.isTrue(fetchSize > 0, "fetchSize must be greater than 0");
		this.redisTemplate = redisTemplate;
		this.scanOptions = scanOptions;
		this.fetchSize = fetchSize;
		this.buffer = new ArrayDeque<>();
	}

	@Override
	public void open(ExecutionContext executionContext) throws ItemStreamException {
		this.cursor = this.redisTemplate.scan(this.scanOptions);
	}

	@Override
	public V read() throws Exception {
		if (this.buffer.isEmpty()) {
			fetchNext();
		}

		return this.buffer.pollFirst();
	}

	@Override
	public void close() throws ItemStreamException {
		this.cursor.close();
	}

	private void fetchNext() {
		List<K> keys = new ArrayList<>();
		while (this.cursor.hasNext() && keys.size() < this.fetchSize) {
			keys.add(this.cursor.next());
		}

		if (keys.isEmpty()) {
			return;
		}

		@SuppressWarnings("unchecked")
		List<V> items = (List<V>) this.redisTemplate.executePipelined(sessionCallback(keys));

		this.buffer.addAll(items);
	}

	private SessionCallback<Object> sessionCallback(List<K> keys) {
		return new SessionCallback<>() {
			@Override
			public Object execute(RedisOperations operations) throws DataAccessException {
				for (K key : keys) {
					operations.opsForValue().get(key);
				}

				return null;
			}
		};
	}

}
