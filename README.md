# Kotlin LSP `--isolated-documents` mode load test

> Note: currently only RESTful APIs tests have been conducted due to a necessary
  performance improvement in WS tests.

This load tests relies on the [Kotlin compiler
server](https://github.com/JetBrains/kotlin-compiler-server) and the
[Kotlin-lsp](https://github.com/Kotlin/kotlin-lsp) in `--isolated-documents`
mode (not yet public), while testing an experimental proxy of the compiler
server in order to provide completions (available in
[this](https://github.com/S-furi/kotlin-compiler-server/tree/exp/isolated-documents)
fork).

The load test is simple: N clients performs completions requests in short time
intervals, exploiting limitations of both the [RESTful
approach](https://github.com/S-furi/kotlin-compiler-server/blob/9af2c20cb710bc50fcf2f99bb99685e5a9c2277b/src/main/kotlin/com/compiler/server/controllers/CompilerRestController.kt#L49)
and the one with
[WebSockets](https://github.com/S-furi/kotlin-compiler-server/blob/exp/isolated-documents/src/main/kotlin/com/compiler/server/controllers/LspCompletionWebSocketHandler.kt).
Moreover, the test aims in understanding workload limitations of the kotlin-lsp.

The following metrics have been analyzed, both in terms of QoS and resource usage of the LS:
**QoS**
1. Latency analysis (avg, max and over time latency)
2. Failure analysis (number of failures and overall failure rate)
  - Failures could be defined as both an error in the response from the proxy or empty completions.

**Lsp** related metrics
1. Memory usage
2. Garbage collector analysis (time and counts)


## Visualizing Data

Inside the directory `./data_visualization` there's a `compose.yaml` file that fires up a local instance of grafana with
influxDB. The main dashboard is under `Dashboards -> LoadTest -> Aggregate` (default account and pwd: `admin`).

## How tests are run

Tests leverage [k6](https://k6.io/) in a local environment. Tests can be found under `k6loadtests`.
In general, tests are run in the following simplest way:

```js
scenarios: {
  loading_test: {
    executor: "ramping-vus",
    stages: [
      { duration: "10s", target: minClients },
      { duration: "1m", target: minClients },
      { duration: "5s", target: maxClients },
      { duration: "1m", target: maxClients },
      { duration: "10s", target: 0 },
    ],
    gracefulRampDown: "10s",
  },
},
```

Where this can be interpreted as:
1. Ramp the system up in 10s to a number of clients equals to `minClients`;
2. Keep the system running for 1m with a number of clients equals to `minClients`;
3. Ramp the system up in 5s to a number of clients equals to `maxClients`;
4. Keep the system running for 1m with a number of clients equals to `maxClients;
5. In 10s shut down every clients;

The body of the clients can be found at [loadtest-rest.js](./k6loadtest/loadtest-rest.js) and [loadtest-ws.js](k6loadtest/loadtest-ws.js).
