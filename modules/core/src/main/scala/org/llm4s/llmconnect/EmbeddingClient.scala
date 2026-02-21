package org.llm4s.llmconnect

import org.llm4s.llmconnect.config.{ EmbeddingModelConfig, EmbeddingProviderConfig, LocalEmbeddingModels }
import org.llm4s.llmconnect.encoding.UniversalEncoder
import org.llm4s.llmconnect.model.{ EmbeddingError, EmbeddingRequest, EmbeddingResponse, EmbeddingVector }
import org.llm4s.llmconnect.provider.{
  EmbeddingProvider,
  OllamaEmbeddingProvider,
  OpenAIEmbeddingProvider,
  VoyageAIEmbeddingProvider,
  OnnxEmbeddingProvider
}
import org.llm4s.model.ModelRegistry
import org.llm4s.trace.Tracing
import org.llm4s.types.Result
import org.slf4j.LoggerFactory

import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

class EmbeddingClient(
  provider: EmbeddingProvider,
  tracer: Option[Tracing],
  operation: String,
  private val lifecycle: EmbeddingClient.Lifecycle
) extends AutoCloseable {
  def this(provider: EmbeddingProvider, tracer: Option[Tracing] = None, operation: String = "embedding") =
    this(provider, tracer, operation, new EmbeddingClient.Lifecycle(provider))

  private val logger = LoggerFactory.getLogger(getClass)

  /** Text embeddings via the configured provider. */
  def embed(request: EmbeddingRequest): Result[EmbeddingResponse] =
    if (lifecycle.isClosed) {
      Left(EmbeddingError(code = None, message = "Embedding client is already closed", provider = "embedding-client"))
    } else {
      logger.debug(s"[EmbeddingClient] Embedding with model=${request.model.name}, inputs=${request.input.size}")

      val result = provider.embed(request)

      // Emit trace events if tracing is enabled and we got a response with usage
      result.foreach { response =>
        tracer.foreach { t =>
          response.usage.foreach { usage =>
            // Emit embedding usage event
            t.traceEmbeddingUsage(
              usage = usage,
              model = request.model.name,
              operation = operation,
              inputCount = request.input.size
            )

            // Try to calculate and emit cost
            ModelRegistry.lookup(request.model.name).foreach { meta =>
              meta.pricing.estimateCost(usage.promptTokens, 0).foreach { cost =>
                t.traceCost(
                  costUsd = cost,
                  model = request.model.name,
                  operation = operation,
                  tokenCount = usage.totalTokens,
                  costType = "embedding"
                )
              }
            }
          }
        }
      }

      result
    }

  /** Create a new client with tracing enabled. */
  def withTracing(tracer: Tracing): EmbeddingClient =
    new EmbeddingClient(provider, Some(tracer), operation, lifecycle)

  /** Create a new client with a specific operation label for tracing. */
  def withOperation(op: String): EmbeddingClient =
    new EmbeddingClient(provider, tracer, op, lifecycle)

  override def close(): Unit =
    lifecycle.close()

  /** Unified API to encode any supported file into vectors, given text model + chunking. */
  def encodePath(
    path: Path,
    textModel: EmbeddingModelConfig,
    chunking: UniversalEncoder.TextChunkingConfig,
    experimentalStubsEnabled: Boolean,
    localModels: LocalEmbeddingModels
  ): Result[Seq[EmbeddingVector]] =
    UniversalEncoder.encodeFromPath(path, this, textModel, chunking, experimentalStubsEnabled, localModels)
}

object EmbeddingClient {

  final private[llmconnect] class Lifecycle(private val provider: EmbeddingProvider) {
    private val closed = new AtomicBoolean(false)

    def isClosed: Boolean = closed.get()

    def close(): Unit =
      if (closed.compareAndSet(false, true)) {
        provider.close()
      }
  }

  /**
   * Typed factory: build client from resolved provider name and typed provider config.
   * Avoids reading any additional configuration at runtime.
   */
  def from(provider: String, cfg: EmbeddingProviderConfig): Result[EmbeddingClient] = {
    val p = provider.toLowerCase
    p match {
      case "openai" => Right(new EmbeddingClient(OpenAIEmbeddingProvider.fromConfig(cfg)))
      case "voyage" => Right(new EmbeddingClient(VoyageAIEmbeddingProvider.fromConfig(cfg)))
      case "ollama" => Right(new EmbeddingClient(OllamaEmbeddingProvider.fromConfig(cfg)))
      case "onnx"   => createOnnxClient(cfg)
      case other =>
        Left(
          EmbeddingError(
            code = Some("400"),
            message = s"Unsupported embedding provider: $other",
            provider = "config"
          )
        )
    }
  }

  private def createOnnxClient(cfg: EmbeddingProviderConfig): Result[EmbeddingClient] =
    try
      Right(new EmbeddingClient(OnnxEmbeddingProvider.fromConfig(cfg)))
    catch {
      case e: NoClassDefFoundError =>
        Left(
          EmbeddingError(
            code = Some("500"),
            message =
              s"ONNX provider is optional and requires dependency 'com.microsoft.onnxruntime:onnxruntime' on the application classpath (missing: ${e.getMessage})",
            provider = "onnx"
          )
        )
      case e: ExceptionInInitializerError if Option(e.getCause).exists(_.isInstanceOf[NoClassDefFoundError]) =>
        Left(
          EmbeddingError(
            code = Some("500"),
            message =
              "ONNX provider initialization failed because ONNX runtime classes are unavailable. Add dependency 'com.microsoft.onnxruntime:onnxruntime' to your application.",
            provider = "onnx"
          )
        )
      case e: LinkageError =>
        Left(
          EmbeddingError(
            code = Some("500"),
            message =
              s"ONNX provider linkage error (${e.getClass.getSimpleName}): ${Option(e.getMessage).getOrElse("unknown error")}",
            provider = "onnx"
          )
        )
    }

}
