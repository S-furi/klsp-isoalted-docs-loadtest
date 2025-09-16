import com.sun.tools.attach.VirtualMachine
import com.sun.tools.attach.VirtualMachineDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.lang.management.GarbageCollectorMXBean
import java.lang.management.ManagementFactory
import java.lang.management.MemoryMXBean
import DataHandler.streamToJsonFile
import kotlinx.serialization.Serializable
import java.time.Instant
import javax.management.remote.JMXConnectorFactory
import javax.management.remote.JMXServiceURL
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

object MemoryMetricsCollector {

    suspend fun runCollectingJvmMetricsStdout(body: suspend () -> Unit) = coroutineScope {
        runCollectingJvmMetrics(samplesConsumer = { DataHandler.showSamples(it) }, body)
    }

    suspend fun runK6CollectingJvmMetricsJson(outputJson: String = "resultsJvm.json") = coroutineScope {
        runCollectingJvmMetrics(samplesConsumer = { it.streamToJsonFile(File(outputJson), MemorySample.serializer()) }) {
            ProcessBuilder("k6", "run", "--out", "influxdb=http://localhost:8086/k6", "k6loadtest/loadtest-rest.js" )
                .inheritIO()
                .start()
                .waitFor()
        }
    }

    suspend fun runCollectingJvmMetrics(samplesConsumer: suspend (Flow<MemorySample>) -> Unit, body: suspend () -> Unit) = coroutineScope {
        val jvms: List<VirtualMachineDescriptor> = VirtualMachine.list()
        val lspJvm = jvms.firstOrNull { it.displayName().contains("com.jetbrains.ls.kotlinLsp.KotlinLspServerKt --multi-client --isolated-documents") } ?: error("LSP is not running")
        val samplesFlow = monitorJvm(lspJvm, 500.milliseconds)

        val monitorJob = launch {
            samplesConsumer(samplesFlow)
        }

        val loadJob = launch(Dispatchers.IO) {
            body()
        }

        loadJob.join()
        println("Test finished, keeping monitoring for 30s ...")
        delay(30.seconds)
        monitorJob.cancelAndJoin()
        println("Monitoring finished.")
    }

    private fun monitorJvm(vmDescriptor: VirtualMachineDescriptor, every: Duration): Flow<MemorySample> = flow {
        val vm = VirtualMachine.attach(vmDescriptor)

        val connectorAddress = vm.agentProperties.getProperty("com.sun.management.jmxremote.localConnectorAddress")
            ?: vm.startLocalManagementAgent()

        val url = JMXServiceURL(connectorAddress)
        val jmxc = JMXConnectorFactory.connect(url)
        val mbeanServer = jmxc.mBeanServerConnection

        val memoryMXBean: MemoryMXBean = ManagementFactory.newPlatformMXBeanProxy(
            mbeanServer, ManagementFactory.MEMORY_MXBEAN_NAME, MemoryMXBean::class.java
        )

        val gcBeans: List<GarbageCollectorMXBean> = ManagementFactory.getGarbageCollectorMXBeans().map { gc ->
            ManagementFactory.newPlatformMXBeanProxy(
                mbeanServer, gc.objectName.toString(), GarbageCollectorMXBean::class.java
            )
        }

        try {
            while(currentCoroutineContext().isActive) {
                val heap = memoryMXBean.heapMemoryUsage
                val nonHeap = memoryMXBean.nonHeapMemoryUsage
                val gcCounts = gcBeans.associate { it.name to it.collectionCount }
                val gcTimes = gcBeans.associate { it.name to it.collectionTime }
                emit(MemorySample(Instant.now().toString(), heap.used, heap.committed, nonHeap.used, gcCounts, gcTimes))
                delay(every.inWholeMilliseconds)
            }
        } finally {
            jmxc.close()
            vm.detach()
        }
    }

    @Serializable
    data class MemorySample(
        val timestamp: String,
        val heapUsed: Long,
        val heapCommitted: Long,
        val nonHeapUsed: Long,
        val gcCounts: Map<String, Long>,
        val gcTimes: Map<String, Long>,
    )
}