import com.sun.tools.attach.VirtualMachine
import com.sun.tools.attach.VirtualMachineDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.lang.management.GarbageCollectorMXBean
import java.lang.management.ManagementFactory
import java.lang.management.MemoryMXBean
import java.time.Instant
import javax.management.remote.JMXConnectorFactory
import javax.management.remote.JMXServiceURL
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

object MemoryMetrics {

    suspend fun runCollectingJvmMetrics(body: suspend () -> Unit) = coroutineScope {
        val jvms: List<VirtualMachineDescriptor> = VirtualMachine.list()
        jvms.forEach { println("PID: ${it.id()} - ${it.displayName()}") }
        val lspJvm = jvms.firstOrNull { it.displayName().contains("com.jetbrains.ls.kotlinLsp.KotlinLspServerKt --multi-client --isolated-documents") } ?: error("LSP is not running")

        val samplesFlow = monitorJvm(lspJvm, 500.milliseconds)

        val monitorJob = launch {
            showSamples(samplesFlow)
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

    private suspend fun showSamples(samples: Flow<MemorySample>) {
        samples
            .onCompletion { println("Sample flow completed.") }
            .collect { sample ->
                println(
                    "${sample.timestamp} | Heap: ${sample.heapUsed / 1024 / 1024} MB / ${sample.heapCommitted / 1024 / 1024} MB | " +
                            "NonHeap: ${sample.nonHeapUsed / 1024 / 1024} MB | GCs: ${sample.gcCounts}"
                )
            }
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
                emit(MemorySample(Instant.now(), heap.used, heap.committed, nonHeap.used, gcCounts, gcTimes))
                delay(every.inWholeMilliseconds)
            }
        } finally {
            jmxc.close()
            vm.detach()
        }
    }

    data class MemorySample(
        val timestamp: Instant,
        val heapUsed: Long,
        val heapCommitted: Long,
        val nonHeapUsed: Long,
        val gcCounts: Map<String, Long>,
        val gcTimes: Map<String, Long>,
    )
}