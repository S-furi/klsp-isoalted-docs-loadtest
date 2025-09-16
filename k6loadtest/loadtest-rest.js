import http from "k6/http";
import { check, sleep } from "k6";

export const options = {
  scenarios: {
    massive_load: {
      executor: "ramping-vus",
      stages: [
        { duration: "30s", target: 50 }, // ramp to 2000 users
        { duration: "1m", target: 50 }, // hold
        { duration: "30s", target: 100 }, // ramp to 500 users
        { duration: "2m", target: 100 }, // hold
        { duration: "30s", target: 0 }, // ramp down
      ],
      gracefulRampDown: "10s",
    },
  },
  thresholds: {
    http_req_failed: ["rate<0.05"], // <5% failures
    http_req_duration: ["p(95)<1000"], // 95% of requests <1s
  },
};

const HOST = "http://localhost:8080/api/compiler/lsp/complete";

export default function () {
  const payload = JSON.stringify({
    args: "",
    files: [{ name: "file.kt", text: "fun main() {\n    3.0.toIn\n}" }],
    confType: "java",
  });

  const params = { headers: { "Content-Type": "application/json" } };

  const res = http.post(`${HOST}?line=1&ch=12`, payload, params);

  check(res, {
    "status 200": (r) => r.status === 200,
    "response contain a completion": (r) =>
      r.body && r.body.length > 0 && r.body.includes("toInt"),
  });

  randomSleep(0.1, 0.4);
}

const randomSleep = (min, max) => {
  sleep(Math.random() * (max - min + 1) + min);
};
