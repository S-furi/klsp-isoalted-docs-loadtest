import http from "k6/http";
import { check } from "k6";
import {
  checkCompletionResponse,
  getRandomCompletionScenario,
  MAX_LATENCY_MS,
  REST_COMPILER_HOST,
  REST_LSP_HOST,
  retrieveOptions,
  randomSleep,
} from "./testUtils.js";

export const options = retrieveOptions(100, 150, {
  http_req_failed: ["rate<0.05"],
  http_req_duration: [`p(95)<${MAX_LATENCY_MS}`],
});

export default function () {
  const completionScenario = getRandomCompletionScenario();
  const payload = JSON.stringify({
    args: "",
    files: [
      { name: completionScenario.filename, text: completionScenario.snippet },
    ],
    confType: "java",
  });

  const params = { headers: { "Content-Type": "application/json" } };

  const res = http.post(
    `${REST_LSP_HOST}?line=${completionScenario.line}&ch=${completionScenario.ch}`,
    payload,
    params,
  );
  check(res, { "status 200": (r) => r.status === 200 });
  checkCompletionResponse(
    res.body,
    completionScenario.expected,
    completionScenario.snippet,
    null,
  );
  randomSleep(0.1, 0.4);
}
