import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import local.LoadTest
import kotlin.time.measureTime

object TestRunner {
    private val scope = CoroutineScope(context = SupervisorJob() + Dispatchers.IO)

    suspend fun simpleJvmTest() {
        scope.launch {
            MemoryMetricsCollector.runCollectingJvmMetricsStdout {
                val time = measureTime {
                    LoadTest.performNParallelCompletionsChunked(10_000, chunkSize = 1_000)
                }
                println("Total time: $time")
            }
        }.join()
    }

    suspend fun k6MassiveTest() {
        val outFile = "results-jvm-rest.json"
        scope.launch {
            MemoryMetricsCollector.runK6CollectingJvmMetricsJson(outFile)
        }.join()
        DataHandler.plotMemoryMetrics(outFile)
    }
}

fun main() = runBlocking {
    TestRunner.k6MassiveTest()
}
