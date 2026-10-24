"""Writes the Messages request bench/proxy-messages.hf.yaml sends: shaped like one of Claude Code's.

A system prompt in cached blocks, two dozen tool definitions (some `strict`, which the Vertex
translation drops), and a conversation with tool calls and results, streamed. About 45 KiB, the
same bytes on every run, so two builds are measured on the same body.

Usage: python3 -I bench/messages-body.py <output.json>
"""
import json, sys

WORDS = ("the proxy injects the account credential and relays the stream while the agent "
         "edits files runs tests and reads the build output before it answers ").split()


def prose(seed, words):
    return " ".join(WORDS[(seed * 7 + i) % len(WORDS)] for i in range(words))


tools = [{
    "name": f"Tool{n:02d}",
    "description": prose(n, 60),
    "input_schema": {
        "type": "object",
        "properties": {
            "path": {"type": "string", "description": prose(n + 1, 12)},
            "pattern": {"type": "string", "description": prose(n + 2, 10)},
            "limit": {"type": "integer", "minimum": 1, "maximum": 2000},
            "flags": {"type": "array", "items": {"type": "string", "enum": ["a", "b", "c"]}},
        },
        "required": ["path"],
        "additionalProperties": False,
    },
    **({"strict": True} if n % 3 == 0 else {}),
} for n in range(24)]

messages = []
for turn in range(8):
    messages.append({"role": "user", "content": [{"type": "text", "text": prose(turn, 120)}]})
    messages.append({"role": "assistant", "content": [
        {"type": "text", "text": prose(turn + 3, 40)},
        {"type": "tool_use", "id": f"toolu_{turn:04d}", "name": f"Tool{turn:02d}",
         "input": {"path": f"src/main/java/Example{turn}.java", "limit": 200}},
    ]})
    messages.append({"role": "user", "content": [
        {"type": "tool_result", "tool_use_id": f"toolu_{turn:04d}", "content": prose(turn + 5, 160)},
    ]})
messages.append({"role": "user", "content": [
    {"type": "text", "text": prose(99, 30), "cache_control": {"type": "ephemeral"}}]})

body = {
    "model": "claude-sonnet-4-6-20250929",
    "max_tokens": 32000,
    "system": [
        {"type": "text", "text": prose(1, 900), "cache_control": {"type": "ephemeral", "scope": "global"}},
        {"type": "text", "text": prose(2, 500), "cache_control": {"type": "ephemeral"}},
    ],
    "tools": tools,
    "messages": messages,
    "metadata": {"user_id": "isx-bench"},
    "stream": True,
}
with open(sys.argv[1], "w") as f:
    json.dump(body, f, separators=(",", ":"))
