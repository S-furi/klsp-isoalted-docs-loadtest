import ws from "k6/ws";
ws;
import { Trend } from "k6/metrics";
import { check, sleep } from "k6";

export const options = {
  scenarios: {
    websocket_load: {
      executor: "ramping-vus",
      stages: [
        { duration: "10s", target: 30 },
        { duration: "1m", target: 30 },
        { duration: "5s", target: 50 },
        { duration: "1m", target: 50 },
        { duration: "10s", target: 0 },
      ],
      gracefulRampDown: "10s",
    },
  },
  thresholds: {
    ws_connecting: ["p(95)<1000"], // connections should succeed quickly
    ws_msgs_sent: ["count>0"], // at least one message sent per VU
    ws_msgs_received: ["count>0"], // at least one message received per VU
  },
};

const HOST = "ws://localhost:8080/lsp/complete";
const MAX_LATENCY_MS = 2000;

const ws_latency = new Trend("ws_latency");

export default function () {
  const params = { headers: { "Content-Type": "application/json" } };
  let intervalId;
  let isVUActive = true;

  const res = ws.connect(HOST, params, function (socket) {
    socket.on("open", function () {
      console.log(`VU ${__VU}: WebSocket connection opened`);

      const sendMessage = () => {
        if (!isVUActive || socket.readyState !== ws.OPEN) {
          return;
        }

        const payload = JSON.stringify({
          project: {
            args: "",
            files: [
              {
                name: "file.kt",
                text: "fun main() {\n    3.0.toIn\n}",
              },
            ],
            confType: "java",
          },
          line: 1,
          ch: 12,
        });

        const start = Date.now();
        socket.send(payload);

        socket.on("message", function messageHandler(msg) {
          const elapsed = Date.now() - start;
          ws_latency.add(elapsed);

          check(msg, {
            "response contains completion": (m) => m && m.includes("toInt"),
            [`response < ${MAX_LATENCY_MS}ms`]: () => elapsed < MAX_LATENCY_MS,
          });
        });
      };

      const scheduleNextMessage = () => {
        if (!isVUActive) return;

        intervalId = socket.setTimeout(
          () => {
            sendMessage();
            scheduleNextMessage();
          },
          randomIn(100, 500),
        );
      };

      scheduleNextMessage();
    });

    socket.on("close", function (code, reason) {
      console.log(
        `VU ${__VU}: WebSocket connection closed - Code: ${code}, Reason: ${reason}`,
      );
      if (intervalId) {
        socket.clearTimeout(intervalId);
      }
    });

    socket.on("error", function (error) {
      console.log(`VU ${__VU}: WebSocket error:`, error);
      isVUActive = false;
    });

    socket.setTimeout(function () {
      console.log(`VU ${__VU}: VU iteration ending, closing WebSocket`);
      isVUActive = false;
      if (intervalId) {
        socket.clearTimeout(intervalId);
      }
      socket.close();
    }, Number.MAX_SAFE_INTEGER); // This timeout will be cleared by the sleep below
  });

  check(res, { "status is 101": (r) => r && r.status === 101 });

  try {
    // Sleep for a very long time - K6 will interrupt this when ramping down
    sleep(3600);
  } catch (e) {
    console.log(`VU ${__VU}: Sleep interrupted - likely due to ramp down`);
  } finally {
    console.log(`VU ${__VU}: Cleaning up WebSocket connection`);
    isVUActive = false;
    if (intervalId) {
      try {
        socket.clearTimeout(intervalId);
      } catch (e) {}
    }
  }
}

export function teardown() {
  console.log("Test teardown - all connections should be closed");
}

function randomIn(min, max) {
  return Math.floor(Math.random() * (max - min + 1)) + min;
}
