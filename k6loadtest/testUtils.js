import { check, sleep } from "k6";
import { Counter, Rate, Trend } from "k6/metrics";
import { WebSocketError } from "k6/ws";

export const MAX_LATENCY_MS = 2000;
export const MAX_COMPLETION_FAILURE_RATE = 0.1;

export function retrieveOptions(minClients, maxClients, thresholds) {
  return {
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
    thresholds: {
      completion_failure_rate: [`rate<${MAX_COMPLETION_FAILURE_RATE}`],
      ...thresholds,
    },
  };
}

export const REST_HOST = "http://localhost:8080/api/compiler/lsp/complete";
export const WS_HOST = "ws://localhost:8080/lsp/complete";
export const latency = new Trend("latency");
export const completionFailures = new Counter("completion_failures");
export const completionFailureRate = new Rate("completion_failure_rate");

const completionScenarios = [
  {
    snippet: "fun main() {\n    3.0.toIn\n}",
    line: 1,
    ch: 12,
    expected: ["toInt", "toUInt"],
  },
  {
    snippet: 'fun main() {\n    "hello".lower\n}',
    line: 1,
    ch: 15,
    expected: ["lowercase"],
  },
  {
    snippet: "fun main() {\n    listOf(1,2,3).fir\n}",
    line: 1,
    ch: 21,
    expected: ["first", "firstOrNull"],
  },
  {
    snippet: "fun main() {\n    val sb = StringBuilder()\n    sb.app\n}",
    line: 2,
    ch: 10,
    expected: ["append", "appendLine", "appendRange"],
  },
  {
    snippet: 'fun main() {\n    val m = mapOf(1 to "a")\n    m.con\n}',
    line: 2,
    ch: 9,
    expected: ["containsKey", "containsValue", "contains"],
  },
  {
    snippet: "fun main() {\n    sequenceOf(1,2,3).map\n}",
    line: 1,
    ch: 25,
    expected: ["map", "mapIndexed", "mapNotNull"],
  },
  {
    snippet: "fun main() {\n    val s: String? = null\n    s?.let\n}",
    line: 2,
    ch: 9,
    expected: ["let"],
  },
  {
    snippet: "fun main() {\n    val xs = listOf(1,2,3)\n    xs.fil\n}",
    line: 2,
    ch: 10,
    expected: ["filter", "filterNot", "filterIndexed"],
  },
];

export function getRandomCompletionScenario() {
  return {
    filename: `${crypto.randomUUID()}.kt`,
    ...completionScenarios[
      Math.floor(Math.random() * completionScenarios.length)
    ],
  };
}

/**
 * Check if at least one expected completion is present in the response.
 * Logs missing completions for debugging.
 *
 * @param {string} response - raw completion response
 * @param {string[]} expected - list of expected completions
 * @param {string} codeSnippet - the code snippet tested (for logging)
 * @param {number} elapsedTime - the time passed for tests that do not include request time (e.g. WS)
 * @returns {boolean} true if at least one expected completion found
 */
export function checkCompletionResponse(
  response,
  expected,
  codeSnippet,
  elapsedTime,
) {
  if (!response) {
    console.error(`Empty response for ${codeSnippet}`);
    completionFailures.add(1);
    completionFailureRate.add(1);
    return;
  }

  const found = expected.filter((exp) => response.includes(exp));
  const missing = expected.filter((exp) => !response.includes(exp));

  let ok = found.length > 0; // at least one completion is ok

  check(response, {
    "response contains at least one expected completion": () => ok,
  });

  if (elapsedTime) {
    check(response, {
      [`response < ${MAX_LATENCY_MS}ms`]: () => elapsed < MAX_LATENCY_MS,
    });
  }

  if (!ok) {
    console.error(
      `No expected completions found!\n` +
        `expected: ${expected.join(", ")}\n` +
        `got: ${response}\n` +
        `for code:\n${codeSnippet}`,
    );
    completionFailures.add(1);
    completionFailureRate.add(1);
  } else if (missing.length > 0) {
    console.warn(
      `Missing some completions.\n` +
        `expected: ${expected.join(", ")}\n` +
        `found: ${found.join(", ")}\n` +
        `missing: ${missing.join(", ")}\n` +
        `for code:\n${codeSnippet}`,
    );
  }

  return ok;
}
