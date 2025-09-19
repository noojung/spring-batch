package org.springframework.batch.core;

import com.mysql.cj.jdbc.MysqlDataSource;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.configuration.annotation.EnableBatchProcessing;
import org.springframework.batch.core.configuration.annotation.EnableJdbcJobRepository;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobExecutionException;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.repository.ExecutionContextSerializer;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.repository.dao.Jackson2ExecutionContextStringSerializer;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.test.timeout.LoggingItemWriter;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.kafka.KafkaItemReader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Testcontainers(disabledWithoutDocker = true)
@SpringJUnitConfig
class KafkaItemReaderJacksonErrorIntegrationTests {

	private static final DockerImageName KAFKA_IMAGE = DockerImageName.parse("apache/kafka:4.0.0");

	private static final DockerImageName MYSQL_IMAGE = DockerImageName.parse("mysql:9.2.0");

	@Container
	public static MySQLContainer<?> mysql = new MySQLContainer<>(MYSQL_IMAGE);

	@Container
	public static KafkaContainer kafka = new KafkaContainer(KAFKA_IMAGE);

	@Autowired
	private DataSource dataSource;

	@Autowired
	private JobOperator jobOperator;

	@Autowired
	private Job job;

	private KafkaTemplate<String, String> template;

	@BeforeAll
	static void setUpTopics() {
		Properties properties = new Properties();
		properties.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
		try (AdminClient adminClient = AdminClient.create(properties)) {
			adminClient.createTopics(List.of(new NewTopic("topic1", 2, (short) 1)));
		}
	}

	@BeforeEach
	void setUp() {
		// setup mysql
		ResourceDatabasePopulator databasePopulator = new ResourceDatabasePopulator();
		databasePopulator.addScript(new ClassPathResource("/org/springframework/batch/core/schema-mysql.sql"));
		databasePopulator.execute(this.dataSource);

		// setup KafkaTemplate
		Map<String, Object> producerProperties = KafkaTestUtils.producerProps(kafka.getBootstrapServers());
		ProducerFactory<String, String> producerFactory = new DefaultKafkaProducerFactory<>(producerProperties);
		this.template = new KafkaTemplate<>(producerFactory);
	}

	@Test
	void testJobExecution() throws JobExecutionException, ExecutionException, InterruptedException {
		var futures = new ArrayList<CompletableFuture<?>>();
		futures.add(this.template.send("topic1", 0, null, "val0"));
		futures.add(this.template.send("topic1", 0, null, "val2"));
		futures.add(this.template.send("topic1", 0, null, "val4"));
		futures.add(this.template.send("topic1", 0, null, "val6"));
		futures.add(this.template.send("topic1", 1, null, "val1"));
		futures.add(this.template.send("topic1", 1, null, "val3"));
		futures.add(this.template.send("topic1", 1, null, "val5"));
		futures.add(this.template.send("topic1", 1, null, "val7"));

		for (var future : futures) {
			future.get();
		}

		JobParameters jobParameters = new JobParametersBuilder().toJobParameters();

		JobExecution jobExecution1 = this.jobOperator.start(this.job, jobParameters);
		assertEquals(ExitStatus.FAILED.getExitCode(), jobExecution1.getExitStatus().getExitCode());

		JobExecution jobExecution2 = this.jobOperator.start(this.job, jobParameters);
		assertEquals(ExitStatus.FAILED.getExitCode(), jobExecution2.getExitStatus().getExitCode());
		List<StepExecution> stepExecutions = jobExecution2.getStepExecutions().stream().toList();
		assertEquals(ClassCastException.class, stepExecutions.get(0).getFailureExceptions().get(0).getClass());
	}

	@Configuration
	@EnableBatchProcessing
	@EnableJdbcJobRepository
	static class TestConfiguration {

		@Bean
		public DataSource dataSource() throws Exception {
			MysqlDataSource datasource = new MysqlDataSource();
			datasource.setURL(mysql.getJdbcUrl());
			datasource.setUser(mysql.getUsername());
			datasource.setPassword(mysql.getPassword());
			datasource.setUseSSL(false);
			return datasource;
		}

		@Bean
		public JdbcTransactionManager transactionManager(DataSource dataSource) {
			return new JdbcTransactionManager(dataSource);
		}

		@Bean
		public ExecutionContextSerializer executionContextSerializer() {
			return new Jackson2ExecutionContextStringSerializer();
		}

		@Bean
		public KafkaItemReader<String, String> reader() {
			Properties consumerProperties = new Properties();
			consumerProperties.setProperty(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
			consumerProperties.setProperty(ConsumerConfig.GROUP_ID_CONFIG, "1");
			consumerProperties.setProperty(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
					StringDeserializer.class.getName());
			consumerProperties.setProperty(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
					StringDeserializer.class.getName());

			KafkaItemReader<String, String> reader = new KafkaItemReader<>(consumerProperties, "topic1", 0, 1);
			reader.setPollTimeout(Duration.ofSeconds(1));

			return reader;
		}

		@Bean
		public FailingItemProcessor processor() {
			return new FailingItemProcessor(4);
		}

		@Bean
		public LoggingItemWriter<String> writer() {
			return new LoggingItemWriter<>();
		}

		@Bean
		public Job job(JobRepository jobRepository, PlatformTransactionManager transactionManager,
				KafkaItemReader<String, String> reader, FailingItemProcessor processor,
				LoggingItemWriter<String> writer) {
			return new JobBuilder("job", jobRepository)
				.start(new StepBuilder("step", jobRepository).<String, String>chunk(1)
					.reader(reader)
					.processor(processor)
					.writer(writer)
					.transactionManager(transactionManager)
					.build())
				.build();
		}

	}

	static class FailingItemProcessor implements ItemProcessor<String, String> {

		private final int failingAt;

		private int count = 0;

		public FailingItemProcessor(int failingAt) {
			this.failingAt = failingAt;
		}

		@Override
		public String process(String item) throws Exception {
			if (this.count++ == this.failingAt) {
				throw new RuntimeException("Failed to process item " + item);
			}
			return item;
		}

	}

}
