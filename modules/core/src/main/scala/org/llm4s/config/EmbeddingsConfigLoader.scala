// scalafix:off DisableSyntax.NoPureConfigDefault
package org.llm4s.config

import org.llm4s.error.ConfigurationError
import org.llm4s.llmconnect.config.{ EmbeddingProviderConfig, LocalEmbeddingModels }
import org.llm4s.llmconnect.provider.OnnxEmbeddingProvider
import org.llm4s.types.Result
import pureconfig.{ ConfigReader => PureConfigReader, ConfigSource }

/**
 * Internal PureConfig-based loader for embeddings provider configuration.
 *
 * This is kept separate from Llm4sConfig to keep that façade slim; external
 * code should use Llm4sConfig.embeddings() and Llm4sConfig.textEmbeddingModel()
 * rather than this object directly.
 */
private[config] object EmbeddingsConfigLoader {

  final private case class EmbeddingsOpenAISection(
    baseUrl: Option[String],
    model: Option[String]
  )

  final private case class EmbeddingsVoyageSection(
    apiKey: Option[String],
    baseUrl: Option[String],
    model: Option[String]
  )

  final private case class EmbeddingsOllamaSection(
    apiKey: Option[String],
    baseUrl: Option[String],
    model: Option[String]
  )

  final private case class EmbeddingsOnnxSection(
    modelPath: Option[String],
    inputTensorName: Option[String],
    attentionMaskTensorName: Option[String],
    tokenTypeTensorName: Option[String],
    outputTensorName: Option[String],
    maxSequenceLength: Option[Int],
    vocabSize: Option[Int],
    intraOpNumThreads: Option[Int],
    interOpNumThreads: Option[Int],
    optimizationLevel: Option[String],
    executionMode: Option[String]
  )

  final private case class EmbeddingsSection(
    model: Option[String],    // Unified format: provider/model (e.g., openai/text-embedding-3-small)
    provider: Option[String], // Legacy fallback
    openai: Option[EmbeddingsOpenAISection],
    voyage: Option[EmbeddingsVoyageSection],
    ollama: Option[EmbeddingsOllamaSection],
    onnx: Option[EmbeddingsOnnxSection]
  )

  final private case class EmbeddingsRoot(embeddings: Option[EmbeddingsSection])

  // ---- PureConfig readers for internal shapes ----

  implicit private val embeddingsOpenAISectionReader: PureConfigReader[EmbeddingsOpenAISection] =
    PureConfigReader.forProduct2("baseUrl", "model")(EmbeddingsOpenAISection.apply)

  implicit private val embeddingsVoyageSectionReader: PureConfigReader[EmbeddingsVoyageSection] =
    PureConfigReader.forProduct3("apiKey", "baseUrl", "model")(EmbeddingsVoyageSection.apply)

  implicit private val embeddingsOllamaSectionReader: PureConfigReader[EmbeddingsOllamaSection] =
    PureConfigReader.forProduct3("apiKey", "baseUrl", "model")(EmbeddingsOllamaSection.apply)

  implicit private val embeddingsOnnxSectionReader: PureConfigReader[EmbeddingsOnnxSection] =
    PureConfigReader.forProduct11(
      "modelPath",
      "inputTensorName",
      "attentionMaskTensorName",
      "tokenTypeTensorName",
      "outputTensorName",
      "maxSequenceLength",
      "vocabSize",
      "intraOpNumThreads",
      "interOpNumThreads",
      "optimizationLevel",
      "executionMode"
    )(EmbeddingsOnnxSection.apply)

  implicit private val embeddingsSectionReader: PureConfigReader[EmbeddingsSection] =
    PureConfigReader.forProduct6("model", "provider", "openai", "voyage", "ollama", "onnx")(EmbeddingsSection.apply)

  implicit private val embeddingsRootReader: PureConfigReader[EmbeddingsRoot] =
    PureConfigReader.forProduct1("embeddings")(EmbeddingsRoot.apply)

  // ---- Public API used by Llm4sConfig ----

  /** Load active embeddings provider and its config from the given source under llm4s.embeddings.*. */
  def loadProvider(source: ConfigSource): Result[(String, EmbeddingProviderConfig)] = {
    val rootEither = source.at("llm4s").load[EmbeddingsRoot]

    rootEither.left
      .map { failures =>
        val msg = failures.toList.map(_.description).mkString("; ")
        ConfigurationError(s"Failed to load llm4s embeddings config via PureConfig: $msg")
      }
      .flatMap(buildEmbeddingsConfig(_, source))
  }

  /** Load configured local model names for non-text modalities from llm4s.embeddings.localModels. */
  def loadLocalModels(source: ConfigSource): Result[LocalEmbeddingModels] = {
    final case class LocalModelsSection(
      imageModel: String,
      audioModel: String,
      videoModel: String
    )

    implicit val localModelsSectionReader: PureConfigReader[LocalModelsSection] =
      PureConfigReader.forProduct3("imageModel", "audioModel", "videoModel")(LocalModelsSection.apply)

    source
      .at("llm4s.embeddings.localModels")
      .load[LocalModelsSection]
      .left
      .map { failures =>
        val msg = failures.toList.map(_.description).mkString("; ")
        ConfigurationError(s"Failed to load llm4s embeddings localModels via PureConfig: $msg")
      }
      .map(s => LocalEmbeddingModels(imageModel = s.imageModel, audioModel = s.audioModel, videoModel = s.videoModel))
  }

  // ---- Internal helpers ----

  private def buildEmbeddingsConfig(
    root: EmbeddingsRoot,
    source: ConfigSource,
  ): Result[(String, EmbeddingProviderConfig)] = {
    val emb = root.embeddings.getOrElse(EmbeddingsSection(None, None, None, None, None, None))

    // Check for unified model format first (e.g., "openai/text-embedding-3-small")
    emb.model.map(_.trim).filter(_.nonEmpty) match {
      case Some(modelSpec) =>
        // Parse provider/model format
        val parts = modelSpec.split("/", 2)
        if (parts.length == 2) {
          val (providerName, modelName) = (parts(0).toLowerCase, parts(1))
          providerName match {
            case "openai" =>
              buildOpenAIEmbeddings(emb.openai, source, Some(modelName)).map("openai" -> _)
            case "voyage" =>
              buildVoyageEmbeddings(emb.voyage, Some(modelName)).map("voyage" -> _)
            case "ollama" =>
              buildOllamaEmbeddings(emb.ollama, Some(modelName)).map("ollama" -> _)
            case "onnx" =>
              buildOnnxEmbeddings(emb.onnx, Some(modelName)).map("onnx" -> _)
            case other =>
              Left(ConfigurationError(s"Unknown embedding provider: $other in '$modelSpec'"))
          }
        } else {
          Left(
            ConfigurationError(
              s"Invalid embedding model format: '$modelSpec'. Expected 'provider/model' (e.g., openai/text-embedding-3-small)"
            )
          )
        }

      case None =>
        // Fall back to legacy EMBEDDING_PROVIDER approach
        emb.provider.map(_.trim.toLowerCase) match {
          case Some("openai") =>
            buildOpenAIEmbeddings(emb.openai, source, None).map("openai" -> _)
          case Some("voyage") =>
            buildVoyageEmbeddings(emb.voyage, None).map("voyage" -> _)
          case Some("ollama") =>
            buildOllamaEmbeddings(emb.ollama, None).map("ollama" -> _)
          case Some("onnx") =>
            buildOnnxEmbeddings(emb.onnx, None).map("onnx" -> _)
          case Some(other) =>
            Left(ConfigurationError(s"Unknown embedding provider: $other"))
          case None =>
            Left(
              ConfigurationError(
                "Missing embedding config: set EMBEDDING_MODEL (e.g., openai/text-embedding-3-small) or EMBEDDING_PROVIDER"
              )
            )
        }
    }
  }

  private val DefaultOpenAIEmbeddingBaseUrl = "https://api.openai.com/v1"
  private val DefaultVoyageEmbeddingBaseUrl = "https://api.voyageai.com/v1"
  private val DefaultOllamaEmbeddingBaseUrl = "http://localhost:11434"

  private def buildOpenAIEmbeddings(
    section: Option[EmbeddingsOpenAISection],
    source: ConfigSource,
    modelOverride: Option[String]
  ): Result[EmbeddingProviderConfig] = {
    // Use model from unified format, or fall back to section model
    val modelOpt = modelOverride.orElse(section.flatMap(_.model)).map(_.trim).filter(_.nonEmpty)

    // Use section baseUrl if provided, otherwise default
    val baseUrl = section
      .flatMap(_.baseUrl)
      .map(_.trim)
      .filter(_.nonEmpty)
      .getOrElse(DefaultOpenAIEmbeddingBaseUrl)

    val modelResult: Result[String] =
      modelOpt.toRight(
        ConfigurationError(
          "Missing OpenAI embeddings model (set EMBEDDING_MODEL=openai/model-name or OPENAI_EMBEDDING_MODEL)"
        )
      )

    for {
      model  <- modelResult
      apiKey <- ProviderConfigLoader.loadOpenAISharedApiKey(source)
    } yield EmbeddingProviderConfig(baseUrl = baseUrl, model = model, apiKey = apiKey)
  }

  private def buildOllamaEmbeddings(
    section: Option[EmbeddingsOllamaSection],
    modelOverride: Option[String]
  ): Result[EmbeddingProviderConfig] = {
    // Use model from unified format, or fall back to section model, or default
    val model = modelOverride
      .orElse(section.flatMap(_.model))
      .map(_.trim)
      .filter(_.nonEmpty)
      .getOrElse("nomic-embed-text")

    // Use section baseUrl if provided, otherwise default
    val baseUrl = section
      .flatMap(_.baseUrl)
      .map(_.trim)
      .filter(_.nonEmpty)
      .getOrElse(DefaultOllamaEmbeddingBaseUrl)

    val apiKey = section.flatMap(_.apiKey).getOrElse("not-required")
    Right(EmbeddingProviderConfig(baseUrl = baseUrl, model = model, apiKey = apiKey))
  }

  private def buildOnnxEmbeddings(
    section: Option[EmbeddingsOnnxSection],
    modelOverride: Option[String]
  ): Result[EmbeddingProviderConfig] = {
    val modelPathOpt = modelOverride
      .orElse(section.flatMap(_.modelPath))
      .map(_.trim)
      .filter(_.nonEmpty)

    val modelPathResult: Result[String] =
      modelPathOpt.toRight(
        ConfigurationError(
          "Missing ONNX embeddings model path (set EMBEDDING_MODEL=onnx/path/to/model.onnx or ONNX_EMBEDDING_MODEL_PATH)"
        )
      )

    modelPathResult.map { modelPath =>
      def clean(value: Option[String]): Option[String] =
        value.map(_.trim).filter(_.nonEmpty)

      val options = Seq(
        clean(section.flatMap(_.inputTensorName)).map(OnnxEmbeddingProvider.OptionInputTensorName -> _),
        clean(section.flatMap(_.attentionMaskTensorName)).map(OnnxEmbeddingProvider.OptionAttentionMaskTensorName -> _),
        clean(section.flatMap(_.tokenTypeTensorName)).map(OnnxEmbeddingProvider.OptionTokenTypeTensorName -> _),
        clean(section.flatMap(_.outputTensorName)).map(OnnxEmbeddingProvider.OptionOutputTensorName -> _),
        section.flatMap(_.maxSequenceLength).map(v => OnnxEmbeddingProvider.OptionMaxSequenceLength -> v.toString),
        section.flatMap(_.vocabSize).map(v => OnnxEmbeddingProvider.OptionVocabSize -> v.toString),
        section.flatMap(_.intraOpNumThreads).map(v => OnnxEmbeddingProvider.OptionIntraOpNumThreads -> v.toString),
        section.flatMap(_.interOpNumThreads).map(v => OnnxEmbeddingProvider.OptionInterOpNumThreads -> v.toString),
        clean(section.flatMap(_.optimizationLevel)).map(OnnxEmbeddingProvider.OptionOptimizationLevel -> _),
        clean(section.flatMap(_.executionMode)).map(OnnxEmbeddingProvider.OptionExecutionMode -> _)
      ).flatten.toMap

      EmbeddingProviderConfig(
        baseUrl = "",
        model = modelPath,
        apiKey = "not-required",
        options = options
      )
    }
  }

  private def buildVoyageEmbeddings(
    section: Option[EmbeddingsVoyageSection],
    modelOverride: Option[String]
  ): Result[EmbeddingProviderConfig] = {
    // Use model from unified format, or fall back to section model
    val modelOpt = modelOverride.orElse(section.flatMap(_.model)).map(_.trim).filter(_.nonEmpty)

    // Use section baseUrl if provided, otherwise default
    val baseUrl = section
      .flatMap(_.baseUrl)
      .map(_.trim)
      .filter(_.nonEmpty)
      .getOrElse(DefaultVoyageEmbeddingBaseUrl)

    val apiKeyOpt = section.flatMap(_.apiKey).map(_.trim).filter(_.nonEmpty)

    val apiKeyResult: Result[String] =
      apiKeyOpt.toRight(
        ConfigurationError("Missing Voyage embeddings apiKey (llm4s.embeddings.voyage.apiKey / VOYAGE_API_KEY)")
      )
    val modelResult: Result[String] =
      modelOpt.toRight(
        ConfigurationError(
          "Missing Voyage embeddings model (set EMBEDDING_MODEL=voyage/model-name or VOYAGE_EMBEDDING_MODEL)"
        )
      )

    for {
      apiKey <- apiKeyResult
      model  <- modelResult
    } yield EmbeddingProviderConfig(baseUrl = baseUrl, model = model, apiKey = apiKey)
  }
}
// scalafix:on DisableSyntax.NoPureConfigDefault
