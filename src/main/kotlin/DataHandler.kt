import MemoryMetricsCollector.MemorySample
import com.influxdb.client.domain.WritePrecision
import com.influxdb.client.kotlin.InfluxDBClientKotlinFactory
import com.influxdb.client.write.Point
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import org.jetbrains.letsPlot.export.ggsave
import org.jetbrains.letsPlot.geom.geomLine
import org.jetbrains.letsPlot.label.ggtitle
import org.jetbrains.letsPlot.letsPlot
import java.io.File
import java.time.Instant

object DataHandler {
    private val json = Json { prettyPrint = true }

    suspend fun showSamples(samples: Flow<MemorySample>) {
        samples
            .onCompletion { println("Sample flow completed.") }
            .collect { sample ->
                println(
                    "${sample.timestamp} | Heap: ${sample.heapUsed / 1024 / 1024} MB / ${sample.heapCommitted / 1024 / 1024} MB | " +
                            "NonHeap: ${sample.nonHeapUsed / 1024 / 1024} MB | GCs: ${sample.gcCounts}"
                )
            }
    }

    suspend fun <T> Flow<T>.streamToJsonFile(
        file: File,
        serializer: KSerializer<T>,
        json: Json = this@DataHandler.json
    ) {
        withContext(Dispatchers.IO) {
            file.parentFile?.mkdirs()
            file.bufferedWriter().use { writer ->
                var first = true
                writer.append('[')
                try {
                    collect { item ->
                        if (!first) writer.append(',')
                        writer.append('\n')
                        writer.append(json.encodeToString(serializer, item))
                        first = false
                    }
                } finally {
                    withContext(NonCancellable) {
                        if (!first) writer.append('\n')
                        writer.append(']')
                        writer.flush()
                    }
                }
            }
        }
    }

    suspend fun Flow<MemorySample>.dumpToInfluxDb() {
        collect { InfluxDbConnection.writeMemorySample(it) }
    }

    fun plotMemoryMetrics(jsonFile: String) {
        val text = File(jsonFile).readText()
        val samples = Json.decodeFromString<List<MemorySample>>(text)

        val data = mapOf(
            "time" to samples.map { Instant.parse(it.timestamp).toEpochMilli() / 1000.0 },
            "heapUsedMB" to samples.map { it.heapUsed / 1024.0 / 1024.0 },
            "nonHeapMB" to samples.map { it.nonHeapUsed / 1024.0 / 1024.0 }
        )

        val p = letsPlot(data) +
                geomLine { x = "time"; y = "heapUsedMB" } +
                geomLine(color = "red") { x = "time"; y = "nonHeapMB" } +
                ggtitle("Heap & Non-Heap Usage Over Time (MB)")

        ggsave(p, "memory_plot.png")
    }
}

internal object InfluxDbConnection {
    private val client by lazy { InfluxDBClientKotlinFactory.create("http://localhost:8086", "my-token".toCharArray(), "my-org", "k6") }
    private val writeApi = client.getWriteKotlinApi()

    private fun MemorySample.toPoints(): Iterable<Point> = buildList {
        addAll(
            listOf(
                Point.measurement("heap_used").addField("value", heapUsed.toMB())
                    .time(Instant.parse(timestamp), WritePrecision.MS),
                    Point.measurement("non_heap_used").addField("value", nonHeapUsed.toMB())
                .time(Instant.parse(timestamp), WritePrecision.MS),
                    Point.measurement("heap_committed").addField("value", heapCommitted.toMB())
                .time(Instant.parse(timestamp), WritePrecision.MS),
            ),
        )

        addAll(gcCounts.map { (name, count) ->
            Point.measurement("gc_count_$name").addField("value", count)
                .time(Instant.parse(timestamp), WritePrecision.MS)
        })
        addAll(gcTimes.map { (name, time) ->
            Point.measurement("gc_time_$name").addField("value", time)
                .time(Instant.parse(timestamp), WritePrecision.MS)
        })
    }

    private fun Long.toMB() = this.toDouble() / 1024.0 / 1024.0

    suspend fun writeMemorySample(sample: MemorySample) {
        writeApi.writePoints(sample.toPoints())
    }
}