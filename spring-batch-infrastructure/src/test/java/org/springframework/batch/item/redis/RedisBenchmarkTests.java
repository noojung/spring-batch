package org.springframework.batch.item.redis;

import com.redis.testcontainers.RedisContainer;
import io.lettuce.core.cluster.ClusterClientOptions;
import io.lettuce.core.cluster.ClusterTopologyRefreshOptions;
import io.lettuce.core.internal.HostAndPort;
import io.lettuce.core.resource.ClientResources;
import io.lettuce.core.resource.DnsResolvers;
import io.lettuce.core.resource.MappingSocketAddressResolver;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemStreamReader;
import org.springframework.batch.item.KeyValueItemWriter;
import org.springframework.batch.item.redis.example.Person;
import org.springframework.data.redis.connection.RedisClusterConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.serializer.JdkSerializationRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.Network;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.*;
import java.util.stream.IntStream;

@ExtendWith(SpringExtension.class)
class RedisBenchmarkTests {

	private static final DockerImageName REDIS_IMAGE = DockerImageName.parse("redis:8.0.3");

	private static final int REDIS_NODE_COUNT = 6;

	private static final int REDIS_HASH_SLOTS = 16_384;

	private static final int CHUNK_SIZE = 1_000;

	private Network network;

	private final List<RedisContainer> redisContainers = new ArrayList<>();

	private final Map<String, Integer> hostToMappedPort = new HashMap<>();

	private ClientResources clientResources;

	private RedisTemplate<String, Person> template;

	private static IntStream itemSizes() {
		return IntStream.of(1_000, 10_000, 100_000, 1_000_000);
	}

	private static final Map<String, Map<Integer, Long>> benchmarkResults = new LinkedHashMap<>();

	@BeforeAll
	static void beforeAll() {
		initBenchmarkResults();
	}

	@AfterAll
	static void afterAll() {
		printBenchmarkResults();
	}

	@BeforeEach
	void setUp() throws Exception {
		initNetwork();
		startContainers();
		createCluster();
		waitForClusterReady();
		initTemplate();
	}

	@AfterEach
	void tearDown() {
		if (this.clientResources != null) {
			this.clientResources.shutdown();
		}
		for (RedisContainer c : this.redisContainers) {
			c.stop();
		}
		if (this.network != null) {
			this.network.close();
		}
	}

	@ParameterizedTest
	@MethodSource("itemSizes")
	void benchmark(int itemSize) throws Exception {
		// write
		RedisItemWriter<String, Person> writer = new RedisItemWriter<>();
		writer.setRedisTemplate(this.template);
		writer.setItemKeyMapper(p -> "person:" + p.getId());
		writer.setDelete(false);

		long writeDuration = benchmarkWrite(writer, itemSize);

		// read
		ScanOptions scanOptions = ScanOptions.scanOptions().match("person:*").count(10).build();
		RedisItemReader<String, Person> reader = new RedisItemReader<>(this.template, scanOptions);
		reader.open(new ExecutionContext());

		long readDuration = benchmarkRead(reader);

		reader.close();

		// delete
		writer.setDelete(true);

		long deleteDuration = benchmarkDelete(writer, itemSize);

		saveBenchmarkResults(itemSize, false, writeDuration, readDuration, deleteDuration);
	}

	@ParameterizedTest
	@MethodSource("itemSizes")
	void benchmarkUsingPipeline(int itemSize) throws Exception {
		// write
		RedisPipelineItemWriter<String, Person> writer = new RedisPipelineItemWriter<>();
		writer.setRedisTemplate(this.template);
		writer.setItemKeyMapper(p -> "person:" + p.getId());
		writer.setDelete(false);

		long writeDuration = benchmarkWrite(writer, itemSize);

		// read
		ScanOptions scanOptions = ScanOptions.scanOptions().match("person:*").count(10).build();
		RedisPipelineItemReader<String, Person> reader = new RedisPipelineItemReader<>(this.template, scanOptions,
				CHUNK_SIZE);
		reader.open(new ExecutionContext());

		long readDuration = benchmarkRead(reader);

		reader.close();

		// delete
		writer.setDelete(true);

		long deleteDuration = benchmarkDelete(writer, itemSize);

		saveBenchmarkResults(itemSize, true, writeDuration, readDuration, deleteDuration);
	}

	private long benchmarkWrite(KeyValueItemWriter<String, Person> writer, int itemSize) throws Exception {
		long start = Clock.systemUTC().millis();

		for (int offset = 0; offset < itemSize; offset += CHUNK_SIZE) {
			int end = Math.min(offset + CHUNK_SIZE, itemSize);
			List<Person> chunk = new ArrayList<>(end - offset);
			for (int id = offset; id < end; id++) {
				chunk.add(new Person(id, "person-" + id));
			}
			writer.write(new Chunk<>(chunk));
		}

		return Clock.systemUTC().millis() - start;
	}

	private long benchmarkRead(ItemStreamReader<Person> reader) throws Exception {
		long start = Clock.systemUTC().millis();

		while (reader.read() != null) {
		}

		return Clock.systemUTC().millis() - start;
	}

	private long benchmarkDelete(KeyValueItemWriter<String, Person> writer, int itemSize) throws Exception {
		long start = Clock.systemUTC().millis();

		for (int offset = 0; offset < itemSize; offset += CHUNK_SIZE) {
			int end = Math.min(offset + CHUNK_SIZE, itemSize);
			List<Person> chunk = new ArrayList<>(end - offset);
			for (int id = offset; id < end; id++) {
				chunk.add(new Person(id, "person-" + id));
			}
			writer.write(new Chunk<>(chunk));
		}

		return Clock.systemUTC().millis() - start;
	}

	private void saveBenchmarkResults(int itemSize, boolean pipeline, long writeDuration, long readDuration,
			long deleteDuration) {
		String suffix = pipeline ? " (Pipeline)" : "";

		benchmarkResults.get("write" + suffix).put(itemSize, writeDuration);
		benchmarkResults.get("read" + suffix).put(itemSize, readDuration);
		benchmarkResults.get("delete" + suffix).put(itemSize, deleteDuration);
	}

	private static void initBenchmarkResults() {
		benchmarkResults.put("read", new LinkedHashMap<>());
		benchmarkResults.put("read (Pipeline)", new LinkedHashMap<>());
		benchmarkResults.put("write", new LinkedHashMap<>());
		benchmarkResults.put("write (Pipeline)", new LinkedHashMap<>());
		benchmarkResults.put("delete", new LinkedHashMap<>());
		benchmarkResults.put("delete (Pipeline)", new LinkedHashMap<>());
	}

	private static void printBenchmarkResults() {
		List<Integer> sizes = itemSizes().boxed().toList();

		// header
		System.out.printf("%n| %-20s ", "Task");
		for (int size : sizes) {
			System.out.printf("| %11s ", sizeLabel(size));
		}
		System.out.println("|");

		// separator
		System.out.print("|----------------------");
		for (int ignored : sizes) {
			System.out.print("|-------------");
		}
		System.out.println("|");

		// rows
		for (Map.Entry<String, Map<Integer, Long>> entry : benchmarkResults.entrySet()) {
			System.out.printf("| %-20s ", entry.getKey());
			for (int size : sizes) {
				Long duration = entry.getValue().get(size);
				System.out.printf("| %9s s ", duration != null ? String.format("%.3f", duration / 1000.0) : "-");
			}
			System.out.println("|");
		}
	}

	private static String sizeLabel(int size) {
		if (size >= 1_000_000_000) {
			return size / 1_000_000_000L + "G items";
		}

		if (size >= 1_000_000) {
			return size / 1_000_000L + "M items";
		}

		return size / 1_000L + "k items";
	}

	private void initNetwork() {
		this.network = Network.newNetwork();
	}

	private void startContainers() {
		for (int i = 0; i < REDIS_NODE_COUNT; i++) {
			String alias = "network-" + i;

			RedisContainer redis = new RedisContainer(REDIS_IMAGE).withNetwork(this.network)
				.withNetworkAliases(alias)
				.withExposedPorts(6379)
				.withCommand("redis-server", "--port", "6379", "--cluster-enabled", "yes", "--cluster-config-file",
						"nodes.conf", "--cluster-node-timeout", "5000", "--appendonly", "no")
				.withStartupTimeout(Duration.ofSeconds(30));

			redis.start();
			this.redisContainers.add(redis);

			String ip = getContainerIpAddress(redis);
			int mapped = redis.getMappedPort(6379);
			this.hostToMappedPort.put(ip, mapped);
		}
	}

	private void createCluster() throws IOException, InterruptedException {
		RedisContainer firstNode = this.redisContainers.get(0);

		List<String> addresses = new ArrayList<>();
		for (int i = 0; i < REDIS_NODE_COUNT; i++) {
			addresses.add("network-" + i + ":6379");
		}

		List<String> cmd = new ArrayList<>();
		cmd.add("redis-cli");
		cmd.add("--cluster");
		cmd.add("create");
		cmd.addAll(addresses);
		cmd.add("--cluster-replicas");
		cmd.add("1");
		cmd.add("--cluster-yes");

		ExecResult res = firstNode.execInContainer(cmd.toArray(new String[0]));
		Assertions.assertEquals(0, res.getExitCode(), "Redis cluster creation failed");
	}

	private void waitForClusterReady() throws InterruptedException, IOException {
		RedisContainer firstNode = this.redisContainers.get(0);

		List<String> aliases = new ArrayList<>();
		for (int i = 0; i < REDIS_NODE_COUNT; i++) {
			String alias = "network-" + i;
			aliases.add(alias);
		}

		while (true) {
			boolean allOk = true;

			for (String alias : aliases) {
				ExecResult r = firstNode.execInContainer("redis-cli", "-h", alias, "-p", "6379", "CLUSTER", "INFO");
				if (r.getExitCode() != 0) {
					allOk = false;
					break;
				}
				String out = r.getStdout();
				if (!"ok".equalsIgnoreCase(extractRedisClusterInfo(out, "cluster_state"))
						|| REDIS_HASH_SLOTS != Integer.parseInt(extractRedisClusterInfo(out, "cluster_slots_assigned"))
						|| REDIS_HASH_SLOTS != Integer.parseInt(extractRedisClusterInfo(out, "cluster_slots_ok"))
						|| 0 != Integer.parseInt(extractRedisClusterInfo(out, "cluster_slots_fail"))
						|| REDIS_NODE_COUNT != Integer.parseInt(extractRedisClusterInfo(out, "cluster_known_nodes"))) {
					allOk = false;
					break;
				}
			}

			if (allOk) {
				break;
			}

			Thread.sleep(1_000);
		}
	}

	private void initTemplate() {
		this.clientResources = ClientResources.builder()
			.socketAddressResolver(MappingSocketAddressResolver.create(DnsResolvers.UNRESOLVED, hp -> {
				Integer mapped = this.hostToMappedPort.get(hp.getHostText());
				int port = mapped != null ? mapped : hp.getPort();
				return HostAndPort.of("localhost", port);
			}))
			.build();

		List<String> clusterNodes = new ArrayList<>();
		for (int i = 0; i < REDIS_NODE_COUNT; i++) {
			String clusterNode = this.redisContainers.get(i).getRedisHost() + ":"
					+ this.redisContainers.get(i).getMappedPort(6379);
			clusterNodes.add(clusterNode);
		}

		RedisClusterConfiguration redisClusterConfiguration = new RedisClusterConfiguration(clusterNodes);

		ClusterTopologyRefreshOptions topologyRefreshOptions = ClusterTopologyRefreshOptions.builder()
			.enableAllAdaptiveRefreshTriggers()
			.dynamicRefreshSources(true)
			.enablePeriodicRefresh(Duration.ofSeconds(30))
			.closeStaleConnections(true)
			.build();

		ClusterClientOptions clientOptions = ClusterClientOptions.builder()
			.autoReconnect(true)
			.topologyRefreshOptions(topologyRefreshOptions)
			.build();

		LettuceClientConfiguration lettuceClientConfiguration = LettuceClientConfiguration.builder()
			.clientResources(this.clientResources)
			.clientOptions(clientOptions)
			.commandTimeout(Duration.ofSeconds(10))
			.build();

		LettuceConnectionFactory connectionFactory = new LettuceConnectionFactory(redisClusterConfiguration,
				lettuceClientConfiguration);
		connectionFactory.afterPropertiesSet();

		this.template = new RedisTemplate<>();
		this.template.setConnectionFactory(connectionFactory);
		this.template.setKeySerializer(new StringRedisSerializer());
		this.template.setValueSerializer(new JdkSerializationRedisSerializer());
		this.template.afterPropertiesSet();
	}

	private String getContainerIpAddress(RedisContainer redisContainer) {
		return redisContainer.getContainerInfo()
			.getNetworkSettings()
			.getNetworks()
			.values()
			.iterator()
			.next()
			.getIpAddress();
	}

	private String extractRedisClusterInfo(String text, String key) {
		int s = text.indexOf(key + ":");
		if (s < 0) {
			return "";
		}
		int e = text.indexOf('\n', s);
		return (e < 0 ? text.substring(s) : text.substring(s, e)).split(":", 2)[1].trim();
	}

}
