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
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.measureTime

object LoadTest {
    const val HOST = "localhost"
    const val PORT = 8080
    const val REST_API_PATH = "/api/compiler/lsp/complete"

    fun restCompletion(client: HttpClient, code: String, line: Int, ch: Int): String = runBlocking {
         client.post("http://$HOST:$PORT$REST_API_PATH") {
            url {
                parameters.append("line", line.toString())
                parameters.append("ch", ch.toString())
            }
            contentType(ContentType.Application.Json)
            setBody(Project(args = "", files = listOf(ProjectFile("file.kt", code))))
        }.bodyAsText()
    }

    fun testCompletion() = runBlocking {
        val code = "fun main() {\n    3.0.toIn\n}"
        val line = 1
        val ch = 12
    }

    suspend fun performNCompletions(n: Int, parallel: Boolean = false) {
        val clients = List(n) {
            HttpClient(CIO) {
                install(ContentNegotiation) {
                    json()
                }
            }
        }
        val code = "fun main() {\n    3.0.toIn\n}"
        val line = 1
        val ch = 12

        val times: List<Duration> = if (parallel) {
            coroutineScope {
                clients.chunked(1).flatMap { chunk ->
                    chunk.map { client ->
                        async {
                            restCompletion(client, code, line, ch)
                        }
                    }.awaitAll().map { Duration.parse(it) }
                }
            }
        } else {
            clients.mapIndexed { idx, client ->
                measureTime {
                    restCompletion(client, code, line, ch)
                }.also { println("[client-$idx] Completed in $it") }
            }
        }

        val avg = times.reduce { acc, time -> acc + time } / times.size
        println("Average time: $avg")
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
            LoadTest.performNCompletions(10_000, parallel = true)
        }.join()
    }
}