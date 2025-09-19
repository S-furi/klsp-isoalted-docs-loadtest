import ws from "k6/ws";
import { check } from "k6";
import {
    checkCompletionResponse, getRandomCompletionScenario, MAX_LATENCY_MS, WS_HOST, retrieveOptions, randomInRange, latency,
} from "./testUtils.js";

export const options = retrieveOptions(50, 100, {
    latency: [`p(95)<${MAX_LATENCY_MS}`],
});

export default function () {
    const params = {headers: {"Content-Type": "application/json"}};

    const res = ws.connect(WS_HOST, params, function (socket) {
        socket.on("open", () => {
            let gotResponse = false
            let start = null
            let completionScenario = null

            const sendMessage = () => {
                completionScenario = getRandomCompletionScenario();
                const payload = JSON.stringify({
                    project: {
                        args: "", files: [{
                            name: completionScenario.filename, text: completionScenario.snippet,
                        },], confType: "java",
                    }, line: completionScenario.line, ch: completionScenario.ch,
                });

                start = Date.now()
                socket.send(payload);
            }

            const scheduleNext = () => {
                const delayMs = Math.floor(randomInRange(0.1, 0.4) * 1000);
                socket.setTimeout(() => {
                    if (gotResponse) {
                        gotResponse = false;
                        sendMessage();
                    } else {
                        scheduleNext();
                    }
                }, delayMs);
            }

            socket.on("message", (message) => {
                if (JSON.parse(message)["sessionId"] !== undefined) { // init msg
                    gotResponse = true
                    scheduleNext();
                    return
                }
                const elapsed = Date.now() - start;
                latency.add(elapsed);
                checkCompletionResponse(message, completionScenario.expected, completionScenario.snippet, elapsed);
                gotResponse = true;
                scheduleNext();
            });
        });


        socket.on("error", (e) => {
            console.error(`WebSocket error: ${e.error()}`);
        });

        socket.setTimeout(() => {
            console.log("Closing persistent WS after max duration");
            socket.close();
        }, 300000); // each VU keeps connection open for up to 5 minutes
    });

    check(res, {connected: (r) => r && r.status === 101});
}
