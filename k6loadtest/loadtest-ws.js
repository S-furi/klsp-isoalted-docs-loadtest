import ws from "k6/ws";
ws;
import { Trend } from "k6/metrics";
import { check, sleep } from "k6";
import {
  retrieveOptions,
  latency,
  WS_HOST,
  MAX_LATENCY_MS,
  checkCompletionResponse,
  getRandomCompletionScenario,
} from "./testUtils.js";

export const options = retrieveOptions(30, 50, {
  ws_connecting: ["p(95)<1000"], // connections should succeed quickly
});

export default function () {
  const params = { headers: { "Content-Type": "application/json" } };
  let intervalId;
  let isVUActive = true;

  const res = ws.connect(WS_HOST, params, function (socket) {
    socket.on("open", function () {
      console.log(`VU ${__VU}: WebSocket connection opened`);

      const sendMessage = () => {
        if (!isVUActive || socket.readyState !== ws.OPEN) {
          return;
        }

        const completionScenario = getRandomCompletionScenario();

        const payload = JSON.stringify({
          project: {
            args: "",
            files: [
              {
                name: completionScenario.filename,
                text: completionScenario.snippet,
              },
            ],
            confType: "java",
          },
          line: completionScenario.line,
          ch: completionScenario.ch,
        });

        const start = Date.now();
        socket.send(payload);

        socket.on("message", function messageHandler(msg) {
          const elapsed = Date.now() - start;
          latency.add(elapsed);
          checkCompletionResponse(
            msg,
            completionScenario.expected,
            completionScenario.snippet,
            elapsed,
          );
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
    // Sleep for a very long time K6 will interrupt this when ramping down
    sleep(10000);
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
