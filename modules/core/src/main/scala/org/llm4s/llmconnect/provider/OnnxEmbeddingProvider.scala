package org.llm4s.llmconnect.provider

import ai.onnxruntime.{ OnnxTensor, OnnxValue, OrtEnvironment, OrtException, OrtSession }
import ai.onnxruntime.OrtSession.SessionOptions.{ ExecutionMode, OptLevel }
import org.llm4s.llmconnect.config.EmbeddingProviderConfig
import org.llm4s.llmconnect.model.{ EmbeddingError, EmbeddingRequest, EmbeddingResponse }
import org.slf4j.LoggerFactory

import java.nio.LongBuffer
import scala.collection.mutable
import scala.jdk.CollectionConverters._
import scala.util.Try
import scala.util.Using
import scala.util.control.NonFatal

final case class OnnxEmbeddingSettings(
  inputTensorName: String = OnnxEmbeddingProvider.DefaultInputTensorName,
  attentionMaskTensorName: Option[String] = Some(OnnxEmbeddingProvider.DefaultAttentionMaskTensorName),
  tokenTypeTensorName: Option[String] = Some(OnnxEmbeddingProvider.DefaultTokenTypeTensorName),
  outputTensorName: Option[String] = None,
  maxSequenceLength: Int = OnnxEmbeddingProvider.DefaultMaxSequenceLength,
  vocabSize: Int = OnnxEmbeddingProvider.DefaultVocabSize,
  intraOpNumThreads: Option[Int] = None,
  interOpNumThreads: Option[Int] = None,
  optimizationLevel: Option[OptLevel] = None,
  executionMode: Option[ExecutionMode] = None
) {
  require(maxSequenceLength >= 2, "maxSequenceLength must be >= 2")
  require(vocabSize > 1, "vocabSize must be > 1")
}

object OnnxEmbeddingProvider {
  val ProviderName                   = "onnx"
  val DefaultInputTensorName         = "input_ids"
  val DefaultAttentionMaskTensorName = "attention_mask"
  val DefaultTokenTypeTensorName     = "token_type_ids"
  val DefaultMaxSequenceLength       = 256
  val DefaultVocabSize               = 30522
  val OptionInputTensorName          = "inputTensorName"
  val OptionAttentionMaskTensorName  = "attentionMaskTensorName"
  val OptionTokenTypeTensorName      = "tokenTypeTensorName"
  val OptionOutputTensorName         = "outputTensorName"
  val OptionMaxSequenceLength        = "maxSequenceLength"
  val OptionVocabSize                = "vocabSize"
  val OptionIntraOpNumThreads        = "intraOpNumThreads"
  val OptionInterOpNumThreads        = "interOpNumThreads"
  val OptionOptimizationLevel        = "optimizationLevel"
  val OptionExecutionMode            = "executionMode"
  private val logger                 = LoggerFactory.getLogger(getClass)

  def fromConfig(cfg: EmbeddingProviderConfig): EmbeddingProvider = {
    val settings = OnnxEmbeddingSettings(
      inputTensorName = readStringOption(cfg, OptionInputTensorName).getOrElse(DefaultInputTensorName),
      attentionMaskTensorName =
        readTensorNameOption(cfg, OptionAttentionMaskTensorName, Some(DefaultAttentionMaskTensorName)),
      tokenTypeTensorName = readTensorNameOption(cfg, OptionTokenTypeTensorName, Some(DefaultTokenTypeTensorName)),
      outputTensorName = readTensorNameOption(cfg, OptionOutputTensorName, None),
      maxSequenceLength = readPositiveIntOption(cfg, OptionMaxSequenceLength).getOrElse(DefaultMaxSequenceLength),
      vocabSize = readPositiveIntOption(cfg, OptionVocabSize).getOrElse(DefaultVocabSize),
      intraOpNumThreads = readPositiveIntOption(cfg, OptionIntraOpNumThreads),
      interOpNumThreads = readPositiveIntOption(cfg, OptionInterOpNumThreads),
      optimizationLevel = readOptimizationLevel(cfg, OptionOptimizationLevel),
      executionMode = readExecutionMode(cfg, OptionExecutionMode)
    )
    new OnnxEmbeddingProvider(modelPath = cfg.model, settings = settings)
  }

  private def readStringOption(cfg: EmbeddingProviderConfig, key: String): Option[String] =
    cfg.options.get(key).map(_.trim).filter(_.nonEmpty)

  private def readTensorNameOption(
    cfg: EmbeddingProviderConfig,
    key: String,
    default: Option[String]
  ): Option[String] =
    cfg.options.get(key).map(_.trim) match {
      case Some(raw) if raw.equalsIgnoreCase("none") || raw.equalsIgnoreCase("disabled") || raw.isEmpty => None
      case Some(raw)                                                                                    => Some(raw)
      case None                                                                                         => default
    }

  private def readPositiveIntOption(cfg: EmbeddingProviderConfig, key: String): Option[Int] =
    readStringOption(cfg, key).flatMap { raw =>
      Try(raw.toInt).toOption.filter(_ > 0).orElse {
        logger.warn(s"[OnnxEmbeddingProvider] Invalid integer value '$raw' for option '$key'; ignoring")
        None
      }
    }

  private def readOptimizationLevel(cfg: EmbeddingProviderConfig, key: String): Option[OptLevel] =
    readStringOption(cfg, key).flatMap { raw =>
      raw.toLowerCase match {
        case "no_opt" | "none" | "off"   => Some(OptLevel.NO_OPT)
        case "basic" | "basic_opt"       => Some(OptLevel.BASIC_OPT)
        case "extended" | "extended_opt" => Some(OptLevel.EXTENDED_OPT)
        case "all" | "all_opt"           => Some(OptLevel.ALL_OPT)
        case other =>
          logger.warn(
            s"[OnnxEmbeddingProvider] Invalid optimizationLevel '$other'; expected one of: no_opt, basic, extended, all"
          )
          None
      }
    }

  private def readExecutionMode(cfg: EmbeddingProviderConfig, key: String): Option[ExecutionMode] =
    readStringOption(cfg, key).flatMap { raw =>
      raw.toLowerCase match {
        case "sequential" => Some(ExecutionMode.SEQUENTIAL)
        case "parallel"   => Some(ExecutionMode.PARALLEL)
        case other =>
          logger.warn(
            s"[OnnxEmbeddingProvider] Invalid executionMode '$other'; expected one of: sequential, parallel"
          )
          None
      }
    }

  private[provider] def decodeOutput(
    output: AnyRef,
    attentionMask: Array[Long],
    providerName: String = ProviderName
  ): Either[EmbeddingError, Vector[Double]] =
    output match {
      case null => Left(EmbeddingError(None, "ONNX output was null", providerName))

      case onnxValue: OnnxValue =>
        decodeOutput(onnxValue.getValue.asInstanceOf[AnyRef], attentionMask, providerName)

      case arr: Array[Float] if arr.nonEmpty =>
        Right(arr.iterator.map(_.toDouble).toVector)

      case arr: Array[Double] if arr.nonEmpty =>
        Right(arr.toVector)

      case arr: Array[Array[Float]] if arr.nonEmpty =>
        Right(arr(0).iterator.map(_.toDouble).toVector)

      case arr: Array[Array[Double]] if arr.nonEmpty =>
        Right(arr(0).toVector)

      // Typical encoder output: [batch, seq, dim] -> mean pool over seq using attention mask.
      case arr: Array[Array[Array[Float]]] if arr.nonEmpty =>
        Right(meanPoolFloat(arr(0), attentionMask))

      case arr: Array[Array[Array[Double]]] if arr.nonEmpty =>
        Right(meanPoolDouble(arr(0), attentionMask))

      case other =>
        Left(
          EmbeddingError(
            code = None,
            message = s"Unexpected ONNX output type: ${other.getClass.getName}",
            provider = providerName
          )
        )
    }

  private def meanPoolFloat(tokenEmbeddings: Array[Array[Float]], attentionMask: Array[Long]): Vector[Double] =
    if (tokenEmbeddings.isEmpty || tokenEmbeddings(0).isEmpty) Vector.empty
    else {
      val dimension = tokenEmbeddings(0).length
      val sums      = Array.fill[Double](dimension)(0.0)
      var count     = 0
      var i         = 0

      while (i < tokenEmbeddings.length && i < attentionMask.length) {
        if (attentionMask(i) > 0) {
          val row = tokenEmbeddings(i)
          var j   = 0
          while (j < row.length && j < dimension) {
            sums(j) += row(j).toDouble
            j += 1
          }
          count += 1
        }
        i += 1
      }

      val divisor = if (count == 0) 1.0 else count.toDouble
      sums.iterator.map(_ / divisor).toVector
    }

  private def meanPoolDouble(tokenEmbeddings: Array[Array[Double]], attentionMask: Array[Long]): Vector[Double] =
    if (tokenEmbeddings.isEmpty || tokenEmbeddings(0).isEmpty) Vector.empty
    else {
      val dimension = tokenEmbeddings(0).length
      val sums      = Array.fill[Double](dimension)(0.0)
      var count     = 0
      var i         = 0

      while (i < tokenEmbeddings.length && i < attentionMask.length) {
        if (attentionMask(i) > 0) {
          val row = tokenEmbeddings(i)
          var j   = 0
          while (j < row.length && j < dimension) {
            sums(j) += row(j)
            j += 1
          }
          count += 1
        }
        i += 1
      }

      val divisor = if (count == 0) 1.0 else count.toDouble
      sums.iterator.map(_ / divisor).toVector
    }
}

final class OnnxEmbeddingProvider(
  val modelPath: String,
  val settings: OnnxEmbeddingSettings = OnnxEmbeddingSettings()
) extends EmbeddingProvider {
  private val logger           = LoggerFactory.getLogger(getClass)
  private val env              = OrtEnvironment.getEnvironment()
  private val sessionOptions   = createSessionOptions(settings)
  private val sessionOrError   = createSession(modelPath, sessionOptions)
  private val clsTokenId: Long = 101L
  private val sepTokenId: Long = 102L
  private val padTokenId: Long = 0L

  override def embed(request: EmbeddingRequest): Either[EmbeddingError, EmbeddingResponse] =
    sessionOrError.flatMap { session =>
      if (request.input.isEmpty) {
        Right(
          EmbeddingResponse(
            embeddings = Seq.empty,
            metadata = Map("provider" -> OnnxEmbeddingProvider.ProviderName, "model" -> modelPath, "count" -> "0"),
          )
        )
      } else {
        embedWithSession(session, request.input)
      }
    }

  /**
   * Optional manual cleanup for long-lived apps.
   * EmbeddingProvider does not currently define a close lifecycle hook.
   */
  def close(): Unit = {
    sessionOrError.foreach { session =>
      try session.close()
      catch {
        case NonFatal(e) =>
          logger.debug(s"[OnnxEmbeddingProvider] Ignored session close error: ${e.getMessage}")
      }
    }
    sessionOptions.foreach { options =>
      try options.close()
      catch {
        case NonFatal(e) =>
          logger.debug(s"[OnnxEmbeddingProvider] Ignored session options close error: ${e.getMessage}")
      }
    }
  }

  private def embedWithSession(
    session: OrtSession,
    inputs: Seq[String]
  ): Either[EmbeddingError, EmbeddingResponse] = {
    val availableInputs = session.getInputInfo.keySet().asScala.toSet
    if (!availableInputs.contains(settings.inputTensorName)) {
      Left(
        EmbeddingError(
          code = None,
          message =
            s"Configured input tensor '${settings.inputTensorName}' not found. Available inputs: ${availableInputs.toSeq.sorted
                .mkString(", ")}",
          provider = OnnxEmbeddingProvider.ProviderName
        )
      )
    } else {
      resolveOutputIndex(session).flatMap { outputIndex =>
        val vectorsOrErrors = inputs.map(text => embedSingle(session, availableInputs, outputIndex, text))
        val errors          = vectorsOrErrors.collect { case Left(err) => err }
        if (errors.nonEmpty) Left(errors.head)
        else {
          val vectors = vectorsOrErrors.collect { case Right(vector) => vector }
          Right(
            EmbeddingResponse(
              embeddings = vectors,
              metadata = Map(
                "provider" -> OnnxEmbeddingProvider.ProviderName,
                "model"    -> modelPath,
                "count"    -> inputs.size.toString
              ),
              dim = vectors.headOption.map(_.size)
            )
          )
        }
      }
    }
  }

  private def resolveOutputIndex(session: OrtSession): Either[EmbeddingError, Int] = {
    val outputNames = session.getOutputInfo.keySet().asScala.toVector
    if (outputNames.isEmpty) {
      Left(
        EmbeddingError(
          code = None,
          message = "ONNX model exposes no outputs",
          provider = OnnxEmbeddingProvider.ProviderName
        )
      )
    } else {
      settings.outputTensorName.map(_.trim).filter(_.nonEmpty) match {
        case Some(name) =>
          val index = outputNames.indexOf(name)
          if (index >= 0) Right(index)
          else {
            Left(
              EmbeddingError(
                code = None,
                message =
                  s"Configured output tensor '$name' not found. Available outputs: ${outputNames.mkString(", ")}",
                provider = OnnxEmbeddingProvider.ProviderName
              )
            )
          }
        case None =>
          Right(0)
      }
    }
  }

  private def embedSingle(
    session: OrtSession,
    availableInputs: Set[String],
    outputIndex: Int,
    text: String
  ): Either[EmbeddingError, Vector[Double]] = {
    val tokenIds      = tokenize(text)
    val attentionMask = tokenIds.map(id => if (id == padTokenId) 0L else 1L)
    val tokenTypeIds  = Array.fill[Long](tokenIds.length)(0L)
    val shape         = Array[Long](1L, tokenIds.length.toLong)

    val inferenceResult = Using.Manager { use =>
      val inputTensors = mutable.Map.empty[String, OnnxTensor]

      val inputIdsTensor = use(OnnxTensor.createTensor(env, LongBuffer.wrap(tokenIds), shape))
      inputTensors += settings.inputTensorName -> inputIdsTensor

      settings.attentionMaskTensorName.foreach { tensorName =>
        if (availableInputs.contains(tensorName)) {
          val attentionMaskTensor = use(OnnxTensor.createTensor(env, LongBuffer.wrap(attentionMask), shape))
          inputTensors += tensorName -> attentionMaskTensor
        }
      }

      settings.tokenTypeTensorName.foreach { tensorName =>
        if (availableInputs.contains(tensorName)) {
          val tokenTypeTensor = use(OnnxTensor.createTensor(env, LongBuffer.wrap(tokenTypeIds), shape))
          inputTensors += tensorName -> tokenTypeTensor
        }
      }

      val result = use(session.run(inputTensors.toMap.asJava))
      if (outputIndex >= result.size()) {
        throw new IllegalStateException(
          s"Configured output index $outputIndex is out of bounds for ${result.size()} output tensor(s)"
        )
      }
      val outputValue = result.get(outputIndex).getValue
      OnnxEmbeddingProvider.decodeOutput(outputValue, attentionMask)
    }.toEither

    inferenceResult match {
      case Right(value) =>
        value
      case Left(e: OrtException) =>
        Left(
          EmbeddingError(
            code = None,
            message = s"ONNX runtime error: ${e.getMessage}",
            provider = OnnxEmbeddingProvider.ProviderName
          )
        )
      case Left(e) =>
        Left(
          EmbeddingError(
            code = None,
            message = Option(e.getMessage).getOrElse(e.getClass.getSimpleName),
            provider = OnnxEmbeddingProvider.ProviderName
          )
        )
    }
  }

  private def createSessionOptions(
    runtimeSettings: OnnxEmbeddingSettings
  ): Option[OrtSession.SessionOptions] = {
    val hasOverrides =
      runtimeSettings.intraOpNumThreads.isDefined ||
        runtimeSettings.interOpNumThreads.isDefined ||
        runtimeSettings.optimizationLevel.isDefined ||
        runtimeSettings.executionMode.isDefined

    if (!hasOverrides) {
      None
    } else {
      val options = new OrtSession.SessionOptions()
      runtimeSettings.intraOpNumThreads.foreach(options.setIntraOpNumThreads)
      runtimeSettings.interOpNumThreads.foreach(options.setInterOpNumThreads)
      runtimeSettings.optimizationLevel.foreach(options.setOptimizationLevel)
      runtimeSettings.executionMode.foreach(options.setExecutionMode)
      Some(options)
    }
  }

  private def createSession(
    path: String,
    options: Option[OrtSession.SessionOptions]
  ): Either[EmbeddingError, OrtSession] =
    try {
      val session = options match {
        case Some(sessionOptions) => env.createSession(path, sessionOptions)
        case None                 => env.createSession(path)
      }
      Right(session)
    } catch {
      case e: OrtException =>
        options.foreach(o => Try(o.close()))
        Left(
          EmbeddingError(
            code = None,
            message = s"Failed to initialize ONNX model at '$path': ${e.getMessage}",
            provider = OnnxEmbeddingProvider.ProviderName
          )
        )
      case NonFatal(e) =>
        options.foreach(o => Try(o.close()))
        Left(
          EmbeddingError(
            code = None,
            message =
              s"Failed to initialize ONNX model at '$path': ${Option(e.getMessage).getOrElse(e.getClass.getSimpleName)}",
            provider = OnnxEmbeddingProvider.ProviderName
          )
        )
    }

  private def tokenize(text: String): Array[Long] = {
    val maxBodyTokens = math.max(0, settings.maxSequenceLength - 2)
    val body = text.trim
      .split("\\s+")
      .iterator
      .filter(_.nonEmpty)
      .map(tokenToId)
      .take(maxBodyTokens)
      .toArray

    val withSpecial = Array(clsTokenId) ++ body ++ Array(sepTokenId)

    if (withSpecial.length >= settings.maxSequenceLength) {
      withSpecial.take(settings.maxSequenceLength)
    } else {
      withSpecial ++ Array.fill[Long](settings.maxSequenceLength - withSpecial.length)(padTokenId)
    }
  }

  private def tokenToId(token: String): Long = {
    val rawHash = token.hashCode
    val safeAbs = if (rawHash == Int.MinValue) Int.MaxValue else math.abs(rawHash)
    1L + (safeAbs % (settings.vocabSize - 1)).toLong
  }
}
