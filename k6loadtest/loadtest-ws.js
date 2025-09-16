import ws from "k6/ws";
import { check, sleep } from "k6";

export const options = {
  scenarios: {
    websocket_load: {
      executor: "ramping-vus",
      stages: [
        { duration: "30s", target: 50 }, // ramp up
        { duration: "1m", target: 50 },  // hold
        { duration: "30s", target: 100 }, // ramp further
        { duration: "2m", target: 100 }, // hold
        { duration: "30s", target: 0 },  // ramp down
      ],
      gracefulRampDown: "10s",
    },
  },
  thresholds: {
    ws_connecting: ["p(95)<1000"], // connections should succeed quickly
    ws_msgs_sent: ["count>0"],      // at least one message sent per VU
    ws_msgs_received: ["count>0"],  // at least one message received per VU
  },
};

const HOST = "ws://localhost:8080/lsp/complete";

export default function () {
  const url = HOST;
  const params = { headers: { "Content-Type": "application/json" } };

  ws.connect(url, params, function (socket) {
    socket.on("open", function () {
      console.log("Connected");

      // repeatedly send completion requests
      const sendLoop = () => {
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

        socket.send(payload);
        socket.setTimeout(sendLoop, randomDelay(100, 600)); // schedule next
      };

      sendLoop();
    });

    socket.on("message", function (msg) {
      check(msg, {
        "response contains completion": (m) =>
          m && m.includes("toInt"),
      });
    });

    socket.on("close", function () {
      console.log("Disconnected");
    });

    socket.setTimeout(function () {
      socket.close();
    }, 5000); // keep connection open per VU iteration
  });

  // tiny sleep so VUs cycle predictably
  sleep(1);
}

function randomDelay(minMs, maxMs) {
  return Math.floor(Math.random() * (maxMs - minMs + 1)) + minMs;
}

