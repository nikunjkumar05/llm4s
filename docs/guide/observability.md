---
layout: page
title: Observability
parent: Guide
nav_order: 3
---

# Observability in LLM4S
{: .no_toc }

Observability is critical for understanding the behavior of LLM-powered applications in production. LLM4S provides comprehensive tracing and monitoring capabilities through multiple backends including Langfuse, OpenTelemetry, and console-based logging.
{: .fs-6 .fw-300 }

## Overview

LLM4S observability covers:
- **Tracing**: Track LLM calls, tool executions, agent steps, and guardrail evaluations
- **Metrics**: Monitor latency, token usage, cost, and error rates
- **Logs**: Structured logging for debugging and analysis
- **Multiple Backends**: Support for Langfuse, OpenTelemetry, and console output

## Quick Start

Set the `TRACING_MODE` environment variable to enable tracing:

```bash
# Langfuse (recommended for production)
export TRACING_MODE=langfuse
export LANGFUSE_PUBLIC_KEY=pk-lf-...
export LANGFUSE_SECRET_KEY=sk-lf-...

# OpenTelemetry (for enterprise observability platforms)
export TRACING_MODE=opentelemetry
export OTEL_SERVICE_NAME=llm4s-agent
export OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4317

# Console (development)
export TRACING_MODE=console

# Disabled
export TRACING_MODE=none
```

## Langfuse Integration

[Langfuse](https://langfuse.com) is a dedicated observability platform for LLM applications, offering a user-friendly dashboard for monitoring and debugging.

### Setup

1. **Create a Langfuse account** at https://langfuse.com
2. **Get your API keys** from the Langfuse dashboard (Project Settings → API Keys)
3. **Configure environment variables**:

```bash
export TRACING_MODE=langfuse
export LANGFUSE_PUBLIC_KEY=pk-lf-your-public-key
export LANGFUSE_SECRET_KEY=sk-lf-your-secret-key
```

### Features

- **Trace Explorer**: View all LLM calls, agent steps, and tool executions
- **Cost Tracking**: Monitor spending across different models and providers
- **Performance Analytics**: Analyze latency, token usage, and success rates
- **Custom Metrics**: Attach business metrics to traces
- **Team Collaboration**: Share traces and insights with your team

### Example Usage

Traces are automatically captured when using the LLM4S agent framework:

```scala
import org.llm4s.agent.Agent
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.config.Llm4sConfig

for {
  providerConfig <- Llm4sConfig.provider()
  client <- LLMConnect.getClient(providerConfig)
  agent = new Agent(client)
  tools = ToolRegistry(Seq(...))
  // Traces are automatically sent to Langfuse
  state <- agent.run("Your query", tools)
} yield state
```

View your traces in the Langfuse dashboard under Project → Traces.

### Self-Hosted Langfuse

For on-premise deployments, configure the Langfuse endpoint:

```bash
export TRACING_MODE=langfuse
export LANGFUSE_ENDPOINT=https://your-langfuse-instance.com
export LANGFUSE_PUBLIC_KEY=pk-lf-...
export LANGFUSE_SECRET_KEY=sk-lf-...
```

## OpenTelemetry Integration

OpenTelemetry is an open standard for collecting traces and metrics, compatible with enterprise observability platforms like Datadog, New Relic, Honeycomb, and others.

### Setup

1. **Start an OpenTelemetry collector** (or use a managed service)
2. **Configure environment variables**:

```bash
export TRACING_MODE=opentelemetry
export OTEL_SERVICE_NAME=llm4s-agent
export OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4317
export OTEL_RESOURCE_ATTRIBUTES=deployment.environment=production
```

### Local OpenTelemetry Collector

For development, run a local OpenTelemetry collector:

```bash
docker run -p 4317:4317 otel/opentelemetry-collector:latest
```

### Common Exporters

Configure your collector to export to:

**Datadog**:
```yaml
exporters:
  datadog:
    api:
      key: ${DD_API_KEY}
      site: datadoghq.com
```

**New Relic**:
```yaml
exporters:
  otlp:
    headers:
      api-key: ${NEW_RELIC_API_KEY}
```

**Honeycomb**:
```yaml
exporters:
  otlp:
    headers:
      "x-honeycomb-team": ${HONEYCOMB_API_KEY}
```

## Console Tracing

Console tracing is ideal for development and debugging, printing traces directly to stdout:

```bash
export TRACING_MODE=console
```

### Example Output

```
[AGENT] Starting agent execution
  Query: "What is the weather in San Francisco?"
  Tools: 4 available
[STEP] Step 1: Model completion
  Model: openai/gpt-4o
  Input tokens: 150
  Output tokens: 45
  Duration: 1.2s
[TOOL_CALL] Calling tool: GetWeather
  Input: {"city": "San Francisco"}
  Output: {"temp": 72, "condition": "sunny"}
  Duration: 0.5s
[AGENT] Completed
  Total duration: 2.1s
  Final tokens: 195
```

## Tracing Modes Comparison

| Feature | Langfuse | OpenTelemetry | Console | None |
|---------|----------|---------------|---------|------|
| **Cloud Dashboard** | ✓ | Optional | ✗ | ✗ |
| **Cost Tracking** | ✓ | ✗ | ✗ | ✗ |
| **Team Collaboration** | ✓ | ✓ | ✗ | ✗ |
| **Enterprise Integration** | ✗ | ✓ | ✗ | ✗ |
| **Development Speed** | Medium | Medium | Fast | Fastest |
| **Setup Complexity** | Low | Medium | None | None |
| **Retention** | Long-term | Configurable | Session-only | None |
| **Custom Metrics** | ✓ | ✓ | Limited | ✗ |

### Recommendation by Use Case

- **Hobby projects**: `console`
- **Startups**: `langfuse`
- **Enterprises**: `opentelemetry`
- **Production**: `langfuse` or `opentelemetry`
- **Testing**: `none`

## Production Monitoring

### Best Practices

**1. Enable Langfuse or OpenTelemetry in production**

```bash
export TRACING_MODE=langfuse  # or opentelemetry
export OTEL_RESOURCE_ATTRIBUTES=deployment.environment=production,service.version=1.0.0
```

**2. Monitor Key Metrics**

Set up alerts for:
- **LLM API errors**: Track failures and rate limits
- **Latency**: Monitor response times
- **Token usage**: Watch for cost spikes
- **Tool failures**: Alert on tool execution errors
- **Guardrail rejections**: Track blocked inputs/outputs

**3. Implement Custom Traces**

Attach business context to traces:

```scala
for {
  state <- agent.run("Query", tools)
  _ <- recordBusinessMetric("feature_used", "recommendation_engine")
} yield state
```

**4. Use Sampling for High Traffic**

Configure sampling in OpenTelemetry to reduce overhead:

```bash
export OTEL_TRACES_SAMPLER=parentbased_always_on
export OTEL_TRACES_SAMPLER_ARG=0.1  # 10% sampling
```

**5. Set Up Dashboards**

Create dashboards tracking:
- Total LLM calls and success rate
- Average latency by model
- Token usage and costs
- Error rate by provider
- Agent completion rate

### Example Alerting Rules

**Langfuse**: Set up alerts in the dashboard for:
- Error rate > 5%
- Average latency > 5s
- Monthly costs > budget

**OpenTelemetry**: Configure alerting in your backend:

```prometheus
alert: LLMHighErrorRate
expr: rate(llm_calls_failed[5m]) > 0.05
for: 5m
```

## Metrics Collection and Analysis

### Available Metrics

LLM4S automatically tracks:

**LLM Metrics**:
- `llm_calls` - Total number of LLM calls
- `llm_tokens_input` - Input tokens used
- `llm_tokens_output` - Output tokens generated
- `llm_latency` - Time to complete LLM call
- `llm_cost` - Cost of LLM call
- `llm_errors` - Failed LLM calls

**Agent Metrics**:
- `agent_steps` - Number of steps in agent execution
- `agent_latency` - Total agent execution time
- `agent_completions` - Successful agent completions
- `agent_failures` - Failed agent executions

**Tool Metrics**:
- `tool_calls` - Total tool invocations
- `tool_success_rate` - Percentage of successful tool calls
- `tool_latency` - Tool execution time

**Guardrail Metrics**:
- `guardrail_checks` - Total guardrail evaluations
- `guardrail_rejections` - Inputs/outputs rejected by guardrails

### Querying Metrics

**In Langfuse**:
1. Go to Project → Analytics
2. Select the metric and time range
3. Filter by model, tool, or other attributes

**In OpenTelemetry**:
```promql
# Total LLM calls
increase(llm_calls_total[1h])

# Average latency
avg(llm_latency_seconds)

# Error rate
rate(llm_errors_total[5m]) / rate(llm_calls_total[5m])
```

### Cost Analysis

Monitor LLM spending per model:

```sql
-- Langfuse SQL Analytics
SELECT 
  model,
  SUM(input_tokens) as total_input_tokens,
  SUM(output_tokens) as total_output_tokens,
  SUM(cost) as total_cost,
  COUNT(*) as total_calls
FROM traces
WHERE timestamp > NOW() - INTERVAL '7 days'
GROUP BY model
ORDER BY total_cost DESC
```

## Structured Logging

LLM4S uses SLF4J for logging. Configure your logger for detailed traces:

```bash
# logback.xml
<logger name="org.llm4s.agent" level="DEBUG" />
<logger name="org.llm4s.llmconnect" level="INFO" />
```

## Troubleshooting

### Traces Not Appearing in Langfuse

1. Verify API keys are correct:
   ```bash
   echo $LANGFUSE_PUBLIC_KEY
   echo $LANGFUSE_SECRET_KEY
   ```

2. Check network connectivity:
   ```bash
   curl https://cloud.langfuse.com/health
   ```

3. Enable debug logging:
   ```bash
   export LOG_LEVEL=DEBUG
   ```

### High OpenTelemetry Overhead

1. Enable sampling to reduce traffic:
   ```bash
   export OTEL_TRACES_SAMPLER=parentbased_always_on
   export OTEL_TRACES_SAMPLER_ARG=0.1
   ```

2. Use batching to reduce network calls:
   ```bash
   export OTEL_BSP_MAX_QUEUE_SIZE=2048
   export OTEL_BSP_SCHEDULE_DELAY=5000
   ```

### Missing Metrics

1. Verify tracing mode is enabled:
   ```bash
   echo $TRACING_MODE
   ```

2. Check that metrics are being exported to the correct endpoint:
   ```bash
   echo $OTEL_EXPORTER_OTLP_ENDPOINT
   ```
