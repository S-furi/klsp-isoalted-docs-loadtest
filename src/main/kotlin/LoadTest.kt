import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.measureTime

object LoadTest {
    const val HOST = "localhost"
    const val PORT = 8080
    const val REST_API_PATH = "/api/compiler/lsp/complete"

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json()
        }
    }

    private const val CODE = "fun main() {\n    3.0.toIn\n}"
    private const val LINE = 1
    private const val CH = 12


    suspend fun performNParallelCompletionsChunked(n: Int, chunkSize: Int = 100) = client.use {
        val times = (0 until n).chunked(chunkSize).flatMap { chunk ->
            println("Running chunk ${chunk.first()}-${chunk.last()} ...")
            chunk.map { idx ->
                coroutineScope {
                    async {
                        measureTime {
                            simulateClient(idx, chunkSize)
                        }
                    }
                }
            }.awaitAll()
        }

        val avg = times.reduce { acc, time -> acc + time } / times.size
        println("Average time: $avg with $chunkSize parallel batches")
    }

    suspend fun simulateClient(id: Int, requests: Int) {
        repeat(requests) {
            getCompletionAnalytics(client, id).onFailure {
                println("[client-$id] Failure: $it")
            }
            delay(Random.nextLong(100, 600))
        }
    }

    suspend fun getCompletionAnalytics(client: HttpClient, idx: Int): Result<Duration> =
        runCatching {
            coroutineScope {
                measureTime {
                    restCompletion(LoadTest.client, CODE, LINE, CH).takeIf { it.isNotEmpty() || !it.contains("[]") }
                        ?: throw Exception("Empty response")
                }
            }
        }

    suspend fun restCompletion(client: HttpClient, code: String, line: Int, ch: Int): String {
        return client.post("http://$HOST:$PORT$REST_API_PATH") {
            url {
                parameters.append("line", line.toString())
                parameters.append("ch", ch.toString())
            }
            contentType(ContentType.Application.Json)
            setBody(Project(args = "", files = listOf(ProjectFile("file.kt", code))))
        }.bodyAsText()
    }
}

@Serializable
data class Project(
    val args: String = "",
    val files: List<ProjectFile>,
    val confType: String = "java",
)

@Serializable
data class ProjectFile(val name: String, val text: String)

fun main() {
    runBlocking {
        val scope = CoroutineScope(context = SupervisorJob() + Dispatchers.IO)
        scope.launch {
            MemoryMetrics.runCollectingJvmMetrics {
                val time = measureTime {
                    LoadTest.performNParallelCompletionsChunked(10_000, chunkSize = 1_000)
                }
                println("Total time: $time")
            }
        }.join()
    }
}