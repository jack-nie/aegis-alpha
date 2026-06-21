# Dify Integration Design

## Boundary

Aegis Alpha owns product data, permissions, audit records, portfolio data, and UI.
Dify is used as the execution engine for Agent chat and Workflow orchestration.

The frontend never calls Dify directly. It calls Spring Boot, and Spring Boot calls Dify with server-side API keys.

## Runtime Flow

```text
Next.js UI
  -> Spring Boot /_backend
    -> MySQL: users, agents, workflows, runs, audit
    -> Redis: short-lived status/cache
    -> Dify Service API
```

## Dify API Usage

- Workflow app: `POST {DIFY_BASE_URL}/workflows/run`
- Agent or Chatflow app: `POST {DIFY_BASE_URL}/chat-messages`
- Auth: `Authorization: Bearer {API_KEY}`
- Default response mode: `blocking`

## Configuration

```powershell
$env:MARKETMIND_DIFY_ENABLED = "true"
$env:MARKETMIND_DIFY_BASE_URL = "https://api.dify.ai/v1"
$env:MARKETMIND_DIFY_WORKFLOW_API_KEY = "..."
$env:MARKETMIND_DIFY_CHAT_API_KEY = "..."
$env:MARKETMIND_DIFY_CONSOLE_BASE_URL = "https://your-dify-host"
$env:MARKETMIND_DIFY_CONSOLE_TOKEN = "..."
$env:MARKETMIND_NODE_CALLBACK_BASE_URL = "https://your-public-marketmind-host"
$env:MARKETMIND_NODE_EXECUTION_TOKEN = "..."
```

## Backend Interfaces

- `POST /_backend/dify/workflows/{workflowKey}/run`
- `POST /_backend/dify/agents/{agentId}/chat`
- `GET /_backend/dify/workflows/{workflowKey}/dsl`
- `POST /_backend/dify/workflows/{workflowKey}/publish`

## Workflow Editing Model

The Aegis Alpha editor persists an editing-time graph that is close to Dify's workflow mental model but remains owned by this system:

- `nodes`: execution units such as `start`, `logic`, `agent`, and `end`.
- `edges`: directed dependencies from one node to the next.
- `position`: canvas layout used by the frontend only.
- `data.functionName`: the local handler or Dify-backed capability represented by the node.

Persistence endpoints:

- `GET /_backend/workflows/{workflowKey}/layout`
- `PUT /_backend/workflows/{workflowKey}/layout`

This keeps Dify API keys and execution concerns on the backend while allowing the frontend to provide a Dify-like draggable editor.

## Dify DSL Publishing

`POST /_backend/dify/workflows/{workflowKey}/publish` converts the saved Aegis Alpha workflow layout into a Dify YAML DSL.

The generated DSL maps:

- `start` nodes to Dify start nodes.
- `end` nodes to Dify end nodes.
- `logic` and `agent` nodes to Dify HTTP request nodes.

HTTP request nodes call back into:

- `POST /_backend/internal/workflow-nodes/execute`

This callback endpoint uses `X-Aegis Alpha-Workflow-Token` and lets Dify trigger the real Java-side business executor represented by `data.functionName`.

If Dify Console configuration is absent, the publish endpoint returns `published: false` plus the generated YAML. If `MARKETMIND_DIFY_CONSOLE_BASE_URL` and `MARKETMIND_DIFY_CONSOLE_TOKEN` are configured, the backend posts the YAML to Dify Console import APIs and then attempts to publish the imported workflow draft.

When Dify is disabled or keys are missing, the backend returns a local stub result and still records the request flow.
