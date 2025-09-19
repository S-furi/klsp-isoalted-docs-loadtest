import ws from "k6/ws";
import {check, sleep} from "k6";
import {
    checkCompletionResponse, getRandomCompletionScenario, MAX_LATENCY_MS, WS_HOST, retrieveOptions, randomInRange,
} from "./testUtils.js";

export const options = retrieveOptions(5, 15, {
    ws_session_duration: [`avg<${MAX_LATENCY_MS}`],
});

export default function () {
    const params = {headers: {"Content-Type": "application/json"}};

    const res = ws.connect(WS_HOST, params, function (socket) {
        socket.on("open", () => {
            console.log(`VU ${__VU} connected`);

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
                // randomInRange returns seconds; convert to ms for k6 timers
                const delayMs = Math.floor(randomInRange(0.1, 0.4) * 1000);
                socket.setTimeout(() => {
                    // Only send when we're ready for a new request
                    if (gotResponse) {
                        gotResponse = false;
                        sendMessage();
                    } else {
                        // If somehow still waiting, reschedule shortly
                        scheduleNext();
                    }
                }, delayMs);
            }

            socket.on("message", (message) => {
                if (JSON.parse(message)["sessionId"] !== undefined) {
                    gotResponse = true
                    // Start the message cycle after init
                    scheduleNext();
                    return
                }
                const elapsed = Date.now() - start;
                checkCompletionResponse(message, completionScenario.expected, completionScenario.snippet, elapsed,);
                gotResponse = true;
                // Schedule the next message after receiving and checking the response
                scheduleNext();
            });

            // ... existing code ...
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
