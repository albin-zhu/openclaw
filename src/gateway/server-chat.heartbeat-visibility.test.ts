import { describe, expect, it, vi } from "vitest";

vi.mock("../config/config.js", () => ({
  loadConfig: vi.fn(() => ({
    channels: {
      defaults: {
        heartbeat: {
          showOk: false,
        },
      },
    },
  })),
}));

vi.mock("../infra/heartbeat-visibility.js", () => ({
  resolveHeartbeatVisibility: vi.fn(() => ({
    showOk: false,
    showAlerts: true,
    useIndicator: true,
  })),
}));

describe("server-chat heartbeat visibility", () => {
  it("suppresses webchat chat broadcast for heartbeat runs (even when clientRunId differs)", async () => {
    const { createAgentEventHandler, createChatRunState } = await import("./server-chat.js");
    const { registerAgentRunContext } = await import("../infra/agent-events.js");

    const broadcast = vi.fn();
    const nodeSendToSession = vi.fn();
    const agentRunSeq = new Map<string, number>();
    const chatRunState = createChatRunState();

    // runId is the agent run id; clientRunId is what webchat uses in payloads.
    chatRunState.registry.add("run-1", { sessionKey: "session-1", clientRunId: "client-1" });
    registerAgentRunContext("run-1", { isHeartbeat: true });

    const handler = createAgentEventHandler({
      broadcast,
      nodeSendToSession,
      agentRunSeq,
      chatRunState,
      resolveSessionKeyForRun: () => undefined,
      clearAgentRunContext: vi.fn(),
    });

    handler({
      runId: "run-1",
      seq: 1,
      stream: "assistant",
      ts: Date.now(),
      data: { text: "HEARTBEAT_OK" },
    });

    const chatCalls = broadcast.mock.calls.filter(([event]) => event === "chat");
    expect(chatCalls).toHaveLength(0);

    const sessionChatCalls = nodeSendToSession.mock.calls.filter(([, event]) => event === "chat");
    expect(sessionChatCalls).toHaveLength(1);
  });
});
