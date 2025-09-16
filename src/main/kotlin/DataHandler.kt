import MemoryMetricsCollector.MemorySample
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
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

    @Serializable
    data class K6Point(val type: String, val data: K6Data)

    @Serializable
    data class K6Data(
        val time: String,
        val type: String,
        val metric: String,
        val value: Double,
    )
}