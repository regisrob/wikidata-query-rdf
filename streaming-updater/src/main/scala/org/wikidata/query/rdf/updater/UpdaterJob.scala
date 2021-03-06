package org.wikidata.query.rdf.updater

import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.util.Properties
import java.util.concurrent.TimeUnit.MILLISECONDS

import scala.concurrent.duration.MINUTES
import org.apache.flink.api.common.restartstrategy.RestartStrategies.NoRestartStrategyConfiguration
import org.apache.flink.api.common.serialization.Encoder
import org.apache.flink.api.java.utils.ParameterTool
import org.apache.flink.core.fs.Path
import org.apache.flink.streaming.api.{CheckpointingMode, TimeCharacteristic}
import org.apache.flink.streaming.api.environment.CheckpointConfig.ExternalizedCheckpointCleanup
import org.apache.flink.streaming.api.functions.sink.SinkFunction
import org.apache.flink.streaming.api.functions.sink.filesystem.StreamingFileSink
import org.apache.flink.streaming.api.functions.sink.filesystem.rollingpolicies.OnCheckpointRollingPolicy
import org.apache.flink.streaming.api.scala.{DataStream, StreamExecutionEnvironment}
import org.apache.flink.streaming.connectors.kafka.FlinkKafkaProducer
import org.wikidata.query.rdf.tool.wikibase.WikibaseRepository
import org.wikidata.query.rdf.tool.wikibase.WikibaseRepository.Uris
import org.wikidata.query.rdf.tool.change.events.{PageDeleteEvent, RevisionCreateEvent}

object UpdaterJob {
  val DEFAULT_CLOCK = Clock.systemUTC()
  // scalastyle:off method.length
  def main(args: Array[String]): Unit = {
    val params = ParameterTool.fromArgs(args)

    val hostName: String = params.get("hostname")

    // FIXME: proper options handling
    val pipelineOptions = UpdaterPipelineOptions(
      hostname = hostName,
      reorderingWindowLengthMs = params.getInt("reordering_window_length", 60000),
      reorderingOpParallelism = optionalIntArg(params, "reordering_parallelism"),
      decideMutationOpParallelism = optionalIntArg(params, "decide_mut_op_parallelism"),
      generateDiffParallelism = params.getInt("generate_diff_parallelism", 2),
      generateDiffTimeout = params.getLong("generate_diff_timeout", MILLISECONDS.convert(5, MINUTES)),
      wikibaseRepoThreadPoolSize = params.getInt("wikibase_repo_thread_pool_size", 30) // at most 60 concurrent requests to wikibase
    )
    val inputKafkaBrokers: String = params.get("brokers")
    val outputKafkaBrokers: String = params.get("output_brokers", inputKafkaBrokers)
    val outputTopic: String = params.get("output_topic")
    val outputPartition: Int = params.getInt("output_topic_partition")

    val pipelineInputEventStreamOptions = UpdaterPipelineInputEventStreamOptions(kafkaBrokers = inputKafkaBrokers,
      revisionCreateTopicName = params.get("rev_create_topic"),
      pageDeleteTopicName = params.get("page_delete_topic"),
      consumerGroup = params.get("consumer_group", "wdqs_streaming_updater"),
      maxLateness = params.getInt("max_lateness", 60000))

    val checkpointDir: String = params.get("checkpoint_dir")
    val spuriousEventsDir: String = params.get("spurious_events_dir")
    val failedOpsDir: String = params.get("failed_ops_dir")
    val lateEventsDir: String = params.get("late_events_dir")
    val networkBufferTimeout: Int = params.getInt("network_buffer_timeout", 100)
    val checkPointInterval: Int = params.getInt("checkpoint_interval", 3*60*1000)
    val minPauseBetweenCheckpoints: Int = params.getInt("min_pause_between_checkpoints", 2000)
    val autoWMInterval: Int = params.getInt("auto_wm_interval", 200)
    val latencyTrackingInterval: Option[Int] = optionalIntArg(params, "latency_tracking_interval")
    val checkpointTimeout: Int = params.getInt("checkpoint_timeout", 10*60*1000)
    val checkpointingMode: CheckpointingMode = if (params.getBoolean("exactly_once", true)) {
      CheckpointingMode.EXACTLY_ONCE
    } else {
      CheckpointingMode.AT_LEAST_ONCE
    }

    val outputStreamOption = UpdaterPipelineOutputStreamOption(outputKafkaBrokers, outputTopic, outputPartition, checkpointingMode)
    val outputSink: SinkFunction[MutationDataChunk] = prepareKafkaSink(outputStreamOption)

    val uris: Uris = WikibaseRepository.Uris.fromString(s"https://$hostName")
    implicit val env: StreamExecutionEnvironment = prepareEnv(checkpointDir, checkPointInterval, checkpointTimeout, minPauseBetweenCheckpoints,
      autoWMInterval, checkpointingMode, networkBufferTimeout, latencyTrackingInterval)

    UpdaterPipeline.build(pipelineOptions, buildIncomingStreams(pipelineInputEventStreamOptions, pipelineOptions, clock = DEFAULT_CLOCK),
      rc => WikibaseEntityRevRepository(uris, rc.getMetricGroup))
      .saveLateEventsTo(prepareFileDebugSink(lateEventsDir))
      .saveSpuriousEventsTo(prepareFileDebugSink(spuriousEventsDir))
      .saveFailedOpsTo(prepareFileDebugSink(failedOpsDir))
      .saveTo(outputSink)
      .execute("WDQS Streaming Updater POC")
  }

  private def optionalIntArg(params: ParameterTool, paramName: String) = {
    if (params.has(paramName)) {
      Some(params.getInt(paramName))
    } else {
      None
    }
  }

  private def prepareEnv(checkpointDir: String,
                         checkpointInterval: Int,
                         checkpointTimeout: Int,
                         minPauseBetweenCheckpoints: Int,
                         autoWMInterval: Int,
                         checkpointingMode: CheckpointingMode,
                         networkBufferTimeout: Int,
                         latencyTrackingInterval: Option[Int]): StreamExecutionEnvironment = {
    implicit val env: StreamExecutionEnvironment = StreamExecutionEnvironment.getExecutionEnvironment
    env.setStreamTimeCharacteristic(TimeCharacteristic.EventTime)
    env.setStateBackend(UpdaterStateConfiguration.newStateBackend(checkpointDir))
    env.enableCheckpointing(checkpointInterval, checkpointingMode) // checkpoint every 2secs, checkpoint timeout is 10m by default
    env.getCheckpointConfig.setCheckpointTimeout(checkpointTimeout)
    env.getCheckpointConfig.setMinPauseBetweenCheckpoints(minPauseBetweenCheckpoints)
    env.getCheckpointConfig.setTolerableCheckpointFailureNumber(0)
    // Disable restarts for now, this is way easier to debug this way
    env.setRestartStrategy(new NoRestartStrategyConfiguration())
    env.getCheckpointConfig.enableExternalizedCheckpoints(ExternalizedCheckpointCleanup.RETAIN_ON_CANCELLATION)
    env.getConfig.setAutoWatermarkInterval(autoWMInterval)
    env.setBufferTimeout(networkBufferTimeout)
    latencyTrackingInterval.foreach(l => env.getConfig.setLatencyTrackingInterval(l))
    env
  }

  private def buildIncomingStreams(ievops: UpdaterPipelineInputEventStreamOptions,
                                   opts: UpdaterPipelineOptions, clock: Clock)
                                  (implicit env: StreamExecutionEnvironment): List[DataStream[InputEvent]] = {
    List(
      IncomingStreams.fromKafka(
        KafkaConsumerProperties(ievops.revisionCreateTopicName, ievops.kafkaBrokers, ievops.consumerGroup,
          DeserializationSchemaFactory.getDeserializationSchema(classOf[RevisionCreateEvent])),
        opts.hostname,
        IncomingStreams.REV_CREATE_CONV,
        ievops.maxLateness,
        clock
      ),
        IncomingStreams.fromKafka(
        KafkaConsumerProperties(ievops.pageDeleteTopicName, ievops.kafkaBrokers, ievops.consumerGroup,
          DeserializationSchemaFactory.getDeserializationSchema(classOf[PageDeleteEvent])),
        opts.hostname,
        IncomingStreams.PAGE_DEL_CONV,
        ievops.maxLateness,
        clock
      )
    )
  }


  private def prepareFileDebugSink[O](outputPath: String): SinkFunction[O] = {
    StreamingFileSink.forRowFormat(new Path(outputPath),
      new Encoder[O] {
        override def encode(element: O, stream: OutputStream): Unit = {
          stream.write(s"$element\n".getBytes(StandardCharsets.UTF_8))
        }
      })
      .withRollingPolicy(OnCheckpointRollingPolicy.build())
      .build()
  }

  private def prepareKafkaSink(options: UpdaterPipelineOutputStreamOption): SinkFunction[MutationDataChunk] = {
    val producerConfig = new Properties()
    producerConfig.setProperty("bootstrap.servers", options.kafkaBrokers)
    producerConfig.setProperty("transaction.timeout.ms", "900000")
    producerConfig.setProperty("timeout.ms", "900000")
    producerConfig.setProperty("delivery.timeout.ms", "900000")
    producerConfig.setProperty("batch.size", "250000")
    producerConfig.setProperty("linger.ms", "1")
    producerConfig.setProperty("compression.type", "gzip")
    new FlinkKafkaProducer[MutationDataChunk](
      options.topic,
      new MutationEventDataSerializationSchema(options.topic, options.partition),
      producerConfig,
      options.checkpointingMode match {
        case CheckpointingMode.EXACTLY_ONCE => FlinkKafkaProducer.Semantic.EXACTLY_ONCE
        case CheckpointingMode.AT_LEAST_ONCE => FlinkKafkaProducer.Semantic.AT_LEAST_ONCE
      })
  }
}

