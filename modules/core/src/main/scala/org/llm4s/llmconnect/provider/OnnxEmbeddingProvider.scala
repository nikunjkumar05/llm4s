package org.llm4s.llmconnect.provider

import ai.onnxruntime.{ OnnxTensor, OnnxValue, OrtEnvironment, OrtException, OrtSession }
import ai.onnxruntime.OrtSession.SessionOptions.{ ExecutionMode, OptLevel }
import org.llm4s.llmconnect.config.EmbeddingProviderConfig
import org.llm4s.llmconnect.model.{ EmbeddingError, EmbeddingRequest, EmbeddingResponse }
import org.llm4s.types.{ Result, TryOps }
import org.slf4j.LoggerFactory

import java.nio.LongBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Paths }
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import scala.collection.mutable
import scala.jdk.CollectionConverters._
import scala.util.Try
import scala.util.Using

/**
 * Runtime settings for local ONNX embedding inference.
 *
 * @param inputTensorName model input tensor name for token IDs
 * @param attentionMaskTensorName optional attention mask tensor name; `None` disables mask input
 * @param tokenTypeTensorName optional token type tensor name; `None` disables token type input
 * @param outputTensorName optional output tensor name; defaults to the first model output
 * @param maxSequenceLength max token length (including special tokens and padding)
 * @param tokenizerVocabPath optional explicit path to `vocab.txt`
 * @param tokenizerDoLowerCase whether tokenizer lowercases input text
 * @param tokenizerUnknownToken vocabulary token used for unknown subwords
 * @param tokenizerClsToken vocabulary token prepended at sequence start
 * @param tokenizerSepToken vocabulary token appended at sequence end
 * @param tokenizerPadToken vocabulary token used for padding
 * @param intraOpNumThreads optional ONNX intra-op thread override
 * @param interOpNumThreads optional ONNX inter-op thread override
 * @param optimizationLevel optional ONNX graph optimization level
 * @param executionMode optional ONNX execution mode
 */
final case class OnnxEmbeddingSettings(
  inputTensorName: String = OnnxEmbeddingProvider.DefaultInputTensorName,
  attentionMaskTensorName: Option[String] = Some(OnnxEmbeddingProvider.DefaultAttentionMaskTensorName),
  tokenTypeTensorName: Option[String] = Some(OnnxEmbeddingProvider.DefaultTokenTypeTensorName),
  outputTensorName: Option[String] = None,
  maxSequenceLength: Int = OnnxEmbeddingProvider.DefaultMaxSequenceLength,
  tokenizerVocabPath: Option[String] = None,
  tokenizerDoLowerCase: Boolean = OnnxEmbeddingProvider.DefaultTokenizerDoLowerCase,
  tokenizerUnknownToken: String = OnnxEmbeddingProvider.DefaultUnknownToken,
  tokenizerClsToken: String = OnnxEmbeddingProvider.DefaultClsToken,
  tokenizerSepToken: String = OnnxEmbeddingProvider.DefaultSepToken,
  tokenizerPadToken: String = OnnxEmbeddingProvider.DefaultPadToken,
  intraOpNumThreads: Option[Int] = None,
  interOpNumThreads: Option[Int] = None,
  optimizationLevel: Option[OptLevel] = None,
  executionMode: Option[ExecutionMode] = None
) {
  require(maxSequenceLength >= 2, "maxSequenceLength must be >= 2")
}

object OnnxEmbeddingProvider {
  val ProviderName                   = "onnx"
  val DefaultInputTensorName         = "input_ids"
  val DefaultAttentionMaskTensorName = "attention_mask"
  val DefaultTokenTypeTensorName     = "token_type_ids"
  val DefaultMaxSequenceLength       = 256
  val DefaultUnknownToken            = "[UNK]"
  val DefaultClsToken                = "[CLS]"
  val DefaultSepToken                = "[SEP]"
  val DefaultPadToken                = "[PAD]"
  val DefaultTokenizerDoLowerCase    = true

  val OptionInputTensorName         = "inputTensorName"
  val OptionAttentionMaskTensorName = "attentionMaskTensorName"
  val OptionTokenTypeTensorName     = "tokenTypeTensorName"
  val OptionOutputTensorName        = "outputTensorName"
  val OptionMaxSequenceLength       = "maxSequenceLength"
  val OptionVocabSize               = "vocabSize" // Deprecated: kept for backward compatibility only.
  val OptionTokenizerVocabPath      = "tokenizerVocabPath"
  val OptionTokenizerDoLowerCase    = "tokenizerDoLowerCase"
  val OptionTokenizerUnknownToken   = "tokenizerUnknownToken"
  val OptionTokenizerClsToken       = "tokenizerClsToken"
  val OptionTokenizerSepToken       = "tokenizerSepToken"
  val OptionTokenizerPadToken       = "tokenizerPadToken"
  val OptionIntraOpNumThreads       = "intraOpNumThreads"
  val OptionInterOpNumThreads       = "interOpNumThreads"
  val OptionOptimizationLevel       = "optimizationLevel"
  val OptionExecutionMode           = "executionMode"
  private val logger                = LoggerFactory.getLogger(getClass)

  def fromConfig(cfg: EmbeddingProviderConfig): Result[EmbeddingProvider] = {
    if (readPositiveIntOption(cfg, OptionVocabSize).isDefined) {
      logger.warn(
        s"[OnnxEmbeddingProvider] '$OptionVocabSize' is ignored. Configure tokenizer assets via '$OptionTokenizerVocabPath'."
      )
    }

    val settings       = settingsFromConfig(cfg)
    val sessionOptions = createSessionOptions(settings)

    createEnvironment().left
      .map { error =>
        closeSessionOptionsQuietly(sessionOptions)
        error
      }
      .flatMap { env =>
        createSession(env, cfg.model, sessionOptions).left
          .map { error =>
            closeSessionOptionsQuietly(sessionOptions)
            error
          }
          .flatMap { session =>
            createTokenizer(cfg.model, settings).left
              .map { error =>
                closeSessionQuietly(session)
                closeSessionOptionsQuietly(sessionOptions)
                error
              }
              .map { tokenizer =>
                new OnnxEmbeddingProvider(
                  modelPath = cfg.model,
                  settings = settings,
                  env = env,
                  session = session,
                  tokenizer = tokenizer,
                  sessionOptions = sessionOptions
                )
              }
          }
      }
  }

  private[provider] def settingsFromConfig(cfg: EmbeddingProviderConfig): OnnxEmbeddingSettings =
    OnnxEmbeddingSettings(
      inputTensorName = readStringOption(cfg, OptionInputTensorName).getOrElse(DefaultInputTensorName),
      attentionMaskTensorName =
        readTensorNameOption(cfg, OptionAttentionMaskTensorName, Some(DefaultAttentionMaskTensorName)),
      tokenTypeTensorName = readTensorNameOption(cfg, OptionTokenTypeTensorName, Some(DefaultTokenTypeTensorName)),
      outputTensorName = readTensorNameOption(cfg, OptionOutputTensorName, None),
      maxSequenceLength = readPositiveIntOption(cfg, OptionMaxSequenceLength).getOrElse(DefaultMaxSequenceLength),
      tokenizerVocabPath = readStringOption(cfg, OptionTokenizerVocabPath),
      tokenizerDoLowerCase = readBooleanOption(cfg, OptionTokenizerDoLowerCase).getOrElse(DefaultTokenizerDoLowerCase),
      tokenizerUnknownToken = readStringOption(cfg, OptionTokenizerUnknownToken).getOrElse(DefaultUnknownToken),
      tokenizerClsToken = readStringOption(cfg, OptionTokenizerClsToken).getOrElse(DefaultClsToken),
      tokenizerSepToken = readStringOption(cfg, OptionTokenizerSepToken).getOrElse(DefaultSepToken),
      tokenizerPadToken = readStringOption(cfg, OptionTokenizerPadToken).getOrElse(DefaultPadToken),
      intraOpNumThreads = readPositiveIntOption(cfg, OptionIntraOpNumThreads),
      interOpNumThreads = readPositiveIntOption(cfg, OptionInterOpNumThreads),
      optimizationLevel = readOptimizationLevel(cfg, OptionOptimizationLevel),
      executionMode = readExecutionMode(cfg, OptionExecutionMode)
    )

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

  private def readBooleanOption(cfg: EmbeddingProviderConfig, key: String): Option[Boolean] =
    readStringOption(cfg, key).flatMap {
      case raw if raw.equalsIgnoreCase("true") || raw.equalsIgnoreCase("yes") || raw == "1" => Some(true)
      case raw if raw.equalsIgnoreCase("false") || raw.equalsIgnoreCase("no") || raw == "0" => Some(false)
      case raw =>
        logger.warn(s"[OnnxEmbeddingProvider] Invalid boolean value '$raw' for option '$key'; ignoring")
        None
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

  final private[provider] case class WordPieceTokenizer private (
    vocab: Map[String, Long],
    doLowerCase: Boolean,
    unknownToken: String,
    clsToken: String,
    sepToken: String,
    padToken: String
  ) {
    private val unknownTokenId = vocab(unknownToken)
    private val clsTokenId     = vocab(clsToken)
    private val sepTokenId     = vocab(sepToken)
    private val padId          = vocab(padToken)

    def encode(text: String, maxSequenceLength: Int): Array[Long] = {
      val maxBodyTokens = math.max(0, maxSequenceLength - 2)
      val bodyTokenIds = basicTokenize(text).iterator
        .flatMap(wordPieceTokenize)
        .take(maxBodyTokens)
        .map(piece => vocab.getOrElse(piece, unknownTokenId))
        .toArray

      val withSpecial = Array(clsTokenId) ++ bodyTokenIds ++ Array(sepTokenId)
      if (withSpecial.length >= maxSequenceLength) withSpecial.take(maxSequenceLength)
      else withSpecial ++ Array.fill(maxSequenceLength - withSpecial.length)(padId)
    }

    def padTokenId: Long = padId

    private def basicTokenize(text: String): Vector[String] = {
      val normalized = if (doLowerCase) text.toLowerCase(Locale.ROOT) else text
      val output     = mutable.ArrayBuffer.empty[String]
      val current    = new StringBuilder()

      def flushCurrent(): Unit =
        if (current.nonEmpty) {
          output += current.result()
          current.clear()
        }

      var i = 0
      while (i < normalized.length) {
        val ch = normalized.charAt(i)
        if (Character.isWhitespace(ch)) {
          flushCurrent()
        } else if (isControl(ch)) {
          flushCurrent()
        } else if (isPunctuation(ch)) {
          flushCurrent()
          output += ch.toString
        } else {
          current.append(ch)
        }
        i += 1
      }
      flushCurrent()
      output.toVector.filter(_.nonEmpty)
    }

    private def wordPieceTokenize(token: String): Vector[String] =
      if (token.isEmpty) {
        Vector.empty
      } else {
        val pieces = mutable.ArrayBuffer.empty[String]
        var start  = 0
        var bad    = false

        while (start < token.length && !bad) {
          var end   = token.length
          var found = Option.empty[String]

          while (start < end && found.isEmpty) {
            val piece = token.substring(start, end)
            val key   = if (start == 0) piece else s"##$piece"
            if (vocab.contains(key)) found = Some(key)
            else end -= 1
          }

          found match {
            case Some(piece) =>
              pieces += piece
              start = end
            case None =>
              bad = true
          }
        }

        if (bad) Vector(unknownToken) else pieces.toVector
      }

    private def isControl(ch: Char): Boolean =
      ch != '\t' && ch != '\n' && ch != '\r' && Character.isISOControl(ch)

    private def isPunctuation(ch: Char): Boolean = {
      val codePoint = ch.toInt
      (codePoint >= 33 && codePoint <= 47) ||
      (codePoint >= 58 && codePoint <= 64) ||
      (codePoint >= 91 && codePoint <= 96) ||
      (codePoint >= 123 && codePoint <= 126) || {
        val category = Character.getType(ch)
        category == Character.CONNECTOR_PUNCTUATION ||
        category == Character.DASH_PUNCTUATION ||
        category == Character.START_PUNCTUATION ||
        category == Character.END_PUNCTUATION ||
        category == Character.INITIAL_QUOTE_PUNCTUATION ||
        category == Character.FINAL_QUOTE_PUNCTUATION ||
        category == Character.OTHER_PUNCTUATION
      }
    }
  }

  private[provider] object WordPieceTokenizer {
    def fromVocabFile(
      vocabPath: String,
      doLowerCase: Boolean,
      unknownToken: String,
      clsToken: String,
      sepToken: String,
      padToken: String
    ): Either[String, WordPieceTokenizer] =
      Try {
        val path = Paths.get(vocabPath)
        if (!Files.isRegularFile(path)) {
          Left(s"Tokenizer vocabulary file not found at '$vocabPath'")
        } else {
          val lines = Files.readAllLines(path, StandardCharsets.UTF_8).asScala.toVector
          val vocab = lines.zipWithIndex.collect { case (token, idx) if token.nonEmpty => token -> idx.toLong }.toMap
          if (vocab.isEmpty) {
            Left(s"Tokenizer vocabulary at '$vocabPath' is empty")
          } else {
            val requiredTokens = Seq(unknownToken, clsToken, sepToken, padToken)
            val missingTokens  = requiredTokens.filterNot(vocab.contains)
            if (missingTokens.nonEmpty) {
              Left(s"Tokenizer vocabulary at '$vocabPath' is missing required tokens: ${missingTokens.mkString(", ")}")
            } else {
              Right(
                WordPieceTokenizer(
                  vocab = vocab,
                  doLowerCase = doLowerCase,
                  unknownToken = unknownToken,
                  clsToken = clsToken,
                  sepToken = sepToken,
                  padToken = padToken
                )
              )
            }
          }
        }
      }.toResult.left.map { error =>
        s"Failed to read tokenizer vocabulary at '$vocabPath': ${Option(error.message).getOrElse(error.getClass.getSimpleName)}"
      }.flatten
  }

  private def createEnvironment(): Either[EmbeddingError, OrtEnvironment] =
    Try(OrtEnvironment.getEnvironment()).toResult.left.map { error =>
      EmbeddingError(
        code = None,
        message = s"Failed to initialize ONNX runtime environment: ${error.message}",
        provider = ProviderName
      )
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
    env: OrtEnvironment,
    path: String,
    options: Option[OrtSession.SessionOptions]
  ): Either[EmbeddingError, OrtSession] =
    Try {
      options match {
        case Some(sessionOptions) => env.createSession(path, sessionOptions)
        case None                 => env.createSession(path)
      }
    }.toResult.left.map { error =>
      EmbeddingError(
        code = None,
        message = s"Failed to initialize ONNX model at '$path': ${error.message}",
        provider = ProviderName
      )
    }

  private def createTokenizer(
    path: String,
    runtimeSettings: OnnxEmbeddingSettings
  ): Either[EmbeddingError, OnnxEmbeddingProvider.WordPieceTokenizer] =
    resolveTokenizerVocabPath(path, runtimeSettings).left
      .map(message => EmbeddingError(code = None, message = message, provider = ProviderName))
      .flatMap { vocabPath =>
        OnnxEmbeddingProvider.WordPieceTokenizer
          .fromVocabFile(
            vocabPath = vocabPath,
            doLowerCase = runtimeSettings.tokenizerDoLowerCase,
            unknownToken = runtimeSettings.tokenizerUnknownToken,
            clsToken = runtimeSettings.tokenizerClsToken,
            sepToken = runtimeSettings.tokenizerSepToken,
            padToken = runtimeSettings.tokenizerPadToken
          )
          .left
          .map(message => EmbeddingError(code = None, message = message, provider = ProviderName))
      }

  private def resolveTokenizerVocabPath(
    path: String,
    runtimeSettings: OnnxEmbeddingSettings
  ): Either[String, String] =
    runtimeSettings.tokenizerVocabPath match {
      case Some(configuredPath) =>
        val candidate = configuredPath.trim
        if (candidate.isEmpty) {
          Left(
            s"Tokenizer vocabulary path is empty. Set '$OptionTokenizerVocabPath' to a valid vocab.txt path."
          )
        } else {
          Try {
            if (Files.isRegularFile(Paths.get(candidate))) {
              Right(candidate)
            } else {
              Left(s"Tokenizer vocabulary file not found at '$candidate'")
            }
          }.toResult.left.map(_ => s"Tokenizer vocabulary file not found at '$candidate'").flatten
        }

      case None =>
        Try {
          val modelPath = Paths.get(path)
          val candidates = Seq(
            modelPath.resolveSibling("vocab.txt"),
            modelPath.resolveSibling("tokenizer").resolve("vocab.txt")
          )
          candidates.find(Files.isRegularFile(_)) match {
            case Some(found) =>
              Right(found.toString)
            case None =>
              Left(
                s"Tokenizer vocabulary not found for ONNX model '$path'. Set '$OptionTokenizerVocabPath' to the matching vocab.txt."
              )
          }
        }.toResult.left
          .map(_ =>
            s"Tokenizer vocabulary not found for ONNX model '$path'. Set '$OptionTokenizerVocabPath' to the matching vocab.txt."
          )
          .flatten
    }

  private def closeSessionQuietly(session: OrtSession): Unit =
    Try(session.close()).failed.foreach { error =>
      logger.debug(
        s"[OnnxEmbeddingProvider] Ignored session close error: ${Option(error.getMessage).getOrElse(error.getClass.getSimpleName)}"
      )
    }

  private def closeSessionOptionsQuietly(options: Option[OrtSession.SessionOptions]): Unit =
    options.foreach { sessionOptions =>
      Try(sessionOptions.close()).failed.foreach { error =>
        logger.debug(
          s"[OnnxEmbeddingProvider] Ignored session options close error: ${Option(error.getMessage).getOrElse(error.getClass.getSimpleName)}"
        )
      }
    }
}

final class OnnxEmbeddingProvider private (
  val modelPath: String,
  val settings: OnnxEmbeddingSettings,
  private val env: OrtEnvironment,
  private val session: OrtSession,
  private val tokenizer: OnnxEmbeddingProvider.WordPieceTokenizer,
  private val sessionOptions: Option[OrtSession.SessionOptions]
) extends EmbeddingProvider {
  private val logger = LoggerFactory.getLogger(getClass)
  private val closed = new AtomicBoolean(false)

  override def embed(request: EmbeddingRequest): Either[EmbeddingError, EmbeddingResponse] =
    if (closed.get()) {
      Left(
        EmbeddingError(
          code = None,
          message = s"ONNX embedding provider for model '$modelPath' is already closed",
          provider = OnnxEmbeddingProvider.ProviderName
        )
      )
    } else if (request.input.isEmpty) {
      Right(
        EmbeddingResponse(
          embeddings = Seq.empty,
          metadata = Map("provider" -> OnnxEmbeddingProvider.ProviderName, "model" -> modelPath, "count" -> "0")
        )
      )
    } else {
      embedWithSession(session, tokenizer, request.input)
    }

  override def close(): Unit =
    if (closed.compareAndSet(false, true)) {
      Try(session.close()).failed.foreach { error =>
        logger.debug(
          s"[OnnxEmbeddingProvider] Ignored session close error: ${Option(error.getMessage).getOrElse(error.getClass.getSimpleName)}"
        )
      }
      sessionOptions.foreach { options =>
        Try(options.close()).failed.foreach { error =>
          logger.debug(
            s"[OnnxEmbeddingProvider] Ignored session options close error: ${Option(error.getMessage).getOrElse(error.getClass.getSimpleName)}"
          )
        }
      }
    }

  private def embedWithSession(
    session: OrtSession,
    tokenizer: OnnxEmbeddingProvider.WordPieceTokenizer,
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
        val vectorsOrErrors = inputs.map(text => embedSingle(session, tokenizer, availableInputs, outputIndex, text))
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
    tokenizer: OnnxEmbeddingProvider.WordPieceTokenizer,
    availableInputs: Set[String],
    outputIndex: Int,
    text: String
  ): Either[EmbeddingError, Vector[Double]] = {
    val tokenIds      = tokenizer.encode(text, settings.maxSequenceLength)
    val attentionMask = tokenIds.map(id => if (id == tokenizer.padTokenId) 0L else 1L)
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
}
