package org.llm4s.llmconnect.provider

import org.llm4s.llmconnect.config.EmbeddingProviderConfig
import org.scalatest.EitherValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path }
import scala.util.Using

class OnnxEmbeddingProviderSpec extends AnyWordSpec with Matchers with EitherValues {
  final private class TempVocab(tokens: Seq[String]) extends AutoCloseable {
    private val dir = Files.createTempDirectory("onnx-vocab-spec-")
    val path: Path  = dir.resolve("vocab.txt")

    Files.write(path, tokens.mkString("\n").getBytes(StandardCharsets.UTF_8))

    override def close(): Unit = {
      Files.deleteIfExists(path)
      Files.deleteIfExists(dir)
    }
  }

  private def withTempVocab[A](tokens: Seq[String])(test: String => A): A =
    Using.resource(new TempVocab(tokens))(resource => test(resource.path.toString))

  private val minimalVocab = Seq(
    "[PAD]",
    "[CLS]",
    "[SEP]",
    "[UNK]",
    "hello",
    "world",
    "##s",
    "!"
  )

  "OnnxEmbeddingProvider.decodeOutput" should {
    "decode a [1, dim] float output tensor" in {
      val output: AnyRef = Array(Array(0.1f, 0.2f, 0.3f))
      val result         = OnnxEmbeddingProvider.decodeOutput(output, Array(1L, 1L, 1L))

      result.isRight shouldBe true
      val expected = Vector(0.1d, 0.2d, 0.3d)
      result.value.zip(expected).foreach { case (actual, exp) =>
        actual shouldBe (exp +- 1e-6)
      }
    }

    "mean-pool a [1, seq, dim] float output tensor using attention mask" in {
      val output: AnyRef = Array(
        Array(
          Array(1.0f, 2.0f),
          Array(3.0f, 4.0f),
          Array(100.0f, 200.0f)
        )
      )
      val attentionMask = Array(1L, 1L, 0L)
      val result        = OnnxEmbeddingProvider.decodeOutput(output, attentionMask)

      result.isRight shouldBe true
      val expected = Vector(2.0d, 3.0d)
      result.value.zip(expected).foreach { case (actual, exp) =>
        actual shouldBe (exp +- 1e-6)
      }
    }

    "decode a [dim] double output tensor" in {
      val output: AnyRef = Array(0.1d, 0.2d, 0.3d)
      val result         = OnnxEmbeddingProvider.decodeOutput(output, Array(1L, 1L, 1L))

      result.isRight shouldBe true
      result.value.zip(Vector(0.1d, 0.2d, 0.3d)).foreach { case (actual, exp) =>
        actual shouldBe (exp +- 1e-9)
      }
    }

    "decode a [dim] float output tensor" in {
      val output: AnyRef = Array(0.4f, 0.5f, 0.6f)
      val result         = OnnxEmbeddingProvider.decodeOutput(output, Array(1L, 1L, 1L))

      result.isRight shouldBe true
      result.value.zip(Vector(0.4d, 0.5d, 0.6d)).foreach { case (actual, exp) =>
        actual shouldBe (exp +- 1e-6)
      }
    }

    "decode a [1, dim] double output tensor" in {
      val output: AnyRef = Array(Array(0.7d, 0.8d, 0.9d))
      val result         = OnnxEmbeddingProvider.decodeOutput(output, Array(1L, 1L, 1L))

      result.isRight shouldBe true
      result.value.zip(Vector(0.7d, 0.8d, 0.9d)).foreach { case (actual, exp) =>
        actual shouldBe (exp +- 1e-9)
      }
    }

    "mean-pool a [1, seq, dim] double output tensor using attention mask" in {
      val output: AnyRef = Array(
        Array(
          Array(2.0d, 4.0d),
          Array(6.0d, 8.0d),
          Array(100.0d, 200.0d)
        )
      )
      val attentionMask = Array(1L, 1L, 0L)
      val result        = OnnxEmbeddingProvider.decodeOutput(output, attentionMask)

      result.isRight shouldBe true
      val expected = Vector(4.0d, 6.0d)
      result.value.zip(expected).foreach { case (actual, exp) =>
        actual shouldBe (exp +- 1e-9)
      }
    }

    "return an error when output is null" in {
      val result = OnnxEmbeddingProvider.decodeOutput(null, Array(1L))

      result.isLeft shouldBe true
      result.left.value.message should include("ONNX output was null")
    }

    "return an error for an unexpected output type" in {
      val output: AnyRef = "not-a-tensor"
      val result         = OnnxEmbeddingProvider.decodeOutput(output, Array(1L))

      result.isLeft shouldBe true
      result.left.value.message should include("Unexpected ONNX output type: java.lang.String")
    }
  }

  "OnnxEmbeddingProvider.settingsFromConfig" should {
    "apply ONNX-specific options from EmbeddingProviderConfig" in {
      val cfg = EmbeddingProviderConfig(
        baseUrl = "",
        model = "missing-model.onnx",
        apiKey = "not-required",
        options = Map(
          OnnxEmbeddingProvider.OptionInputTensorName         -> "ids",
          OnnxEmbeddingProvider.OptionAttentionMaskTensorName -> "none",
          OnnxEmbeddingProvider.OptionTokenTypeTensorName     -> "disabled",
          OnnxEmbeddingProvider.OptionMaxSequenceLength       -> "128",
          OnnxEmbeddingProvider.OptionTokenizerVocabPath      -> "path/to/vocab.txt",
          OnnxEmbeddingProvider.OptionTokenizerDoLowerCase    -> "false",
          OnnxEmbeddingProvider.OptionTokenizerUnknownToken   -> "<unk>",
          OnnxEmbeddingProvider.OptionTokenizerClsToken       -> "<s>",
          OnnxEmbeddingProvider.OptionTokenizerSepToken       -> "</s>",
          OnnxEmbeddingProvider.OptionTokenizerPadToken       -> "<pad>",
          OnnxEmbeddingProvider.OptionOptimizationLevel       -> "basic",
          OnnxEmbeddingProvider.OptionExecutionMode           -> "parallel"
        )
      )

      val settings = OnnxEmbeddingProvider.settingsFromConfig(cfg)
      settings.inputTensorName shouldBe "ids"
      settings.attentionMaskTensorName shouldBe None
      settings.tokenTypeTensorName shouldBe None
      settings.maxSequenceLength shouldBe 128
      settings.tokenizerVocabPath shouldBe Some("path/to/vocab.txt")
      settings.tokenizerDoLowerCase shouldBe false
      settings.tokenizerUnknownToken shouldBe "<unk>"
      settings.tokenizerClsToken shouldBe "<s>"
      settings.tokenizerSepToken shouldBe "</s>"
      settings.tokenizerPadToken shouldBe "<pad>"
      settings.optimizationLevel.map(_.name()) shouldBe Some("BASIC_OPT")
      settings.executionMode.map(_.name()) shouldBe Some("PARALLEL")
    }

    "fallback to defaults when ONNX options are omitted" in {
      val cfg = EmbeddingProviderConfig(
        baseUrl = "",
        model = "missing-model.onnx",
        apiKey = "not-required",
        options = Map.empty
      )

      val settings = OnnxEmbeddingProvider.settingsFromConfig(cfg)
      settings.inputTensorName shouldBe OnnxEmbeddingProvider.DefaultInputTensorName
      settings.attentionMaskTensorName shouldBe Some(OnnxEmbeddingProvider.DefaultAttentionMaskTensorName)
      settings.tokenTypeTensorName shouldBe Some(OnnxEmbeddingProvider.DefaultTokenTypeTensorName)
      settings.outputTensorName shouldBe None
      settings.maxSequenceLength shouldBe OnnxEmbeddingProvider.DefaultMaxSequenceLength
      settings.tokenizerVocabPath shouldBe None
      settings.tokenizerDoLowerCase shouldBe OnnxEmbeddingProvider.DefaultTokenizerDoLowerCase
      settings.tokenizerUnknownToken shouldBe OnnxEmbeddingProvider.DefaultUnknownToken
      settings.tokenizerClsToken shouldBe OnnxEmbeddingProvider.DefaultClsToken
      settings.tokenizerSepToken shouldBe OnnxEmbeddingProvider.DefaultSepToken
      settings.tokenizerPadToken shouldBe OnnxEmbeddingProvider.DefaultPadToken
      settings.optimizationLevel shouldBe None
      settings.executionMode shouldBe None
    }

    "ignore invalid numeric and enum options and use safe defaults" in {
      val cfg = EmbeddingProviderConfig(
        baseUrl = "",
        model = "missing-model.onnx",
        apiKey = "not-required",
        options = Map(
          OnnxEmbeddingProvider.OptionMaxSequenceLength    -> "0",
          OnnxEmbeddingProvider.OptionTokenizerDoLowerCase -> "not-a-bool",
          OnnxEmbeddingProvider.OptionOptimizationLevel    -> "turbo",
          OnnxEmbeddingProvider.OptionExecutionMode        -> "concurrent"
        )
      )

      val settings = OnnxEmbeddingProvider.settingsFromConfig(cfg)
      settings.maxSequenceLength shouldBe OnnxEmbeddingProvider.DefaultMaxSequenceLength
      settings.tokenizerDoLowerCase shouldBe OnnxEmbeddingProvider.DefaultTokenizerDoLowerCase
      settings.optimizationLevel shouldBe None
      settings.executionMode shouldBe None
    }
  }

  "OnnxEmbeddingProvider.WordPieceTokenizer" should {
    "encode text with vocab-backed ids and special tokens" in withTempVocab(minimalVocab) { vocabPath =>
      val tokenizer = OnnxEmbeddingProvider.WordPieceTokenizer
        .fromVocabFile(
          vocabPath = vocabPath,
          doLowerCase = true,
          unknownToken = "[UNK]",
          clsToken = "[CLS]",
          sepToken = "[SEP]",
          padToken = "[PAD]"
        )
        .value

      val encoded = tokenizer.encode("Hello worlds!", maxSequenceLength = 8)
      encoded.toVector shouldBe Vector(1L, 4L, 5L, 6L, 7L, 2L, 0L, 0L)
    }

    "fallback to unknown token when wordpiece decomposition fails" in withTempVocab(minimalVocab) { vocabPath =>
      val tokenizer = OnnxEmbeddingProvider.WordPieceTokenizer
        .fromVocabFile(
          vocabPath = vocabPath,
          doLowerCase = true,
          unknownToken = "[UNK]",
          clsToken = "[CLS]",
          sepToken = "[SEP]",
          padToken = "[PAD]"
        )
        .value

      val encoded = tokenizer.encode("mystery", maxSequenceLength = 6)
      encoded.toVector shouldBe Vector(1L, 3L, 2L, 0L, 0L, 0L)
    }

    "return an error when required special tokens are missing" in withTempVocab(
      Seq("[PAD]", "[CLS]", "[UNK]", "hello")
    ) { vocabPath =>
      val result = OnnxEmbeddingProvider.WordPieceTokenizer.fromVocabFile(
        vocabPath = vocabPath,
        doLowerCase = true,
        unknownToken = "[UNK]",
        clsToken = "[CLS]",
        sepToken = "[SEP]",
        padToken = "[PAD]"
      )

      result.isLeft shouldBe true
      result.left.value should include("missing required tokens")
    }
  }

  "OnnxEmbeddingProvider" should {
    "return an initialization error for an invalid model path" in withTempVocab(
      minimalVocab
    ) { vocabPath =>
      val result = OnnxEmbeddingProvider.fromConfig(
        EmbeddingProviderConfig(
          baseUrl = "",
          model = "invalid/path/to/model.onnx",
          apiKey = "not-required",
          options = Map(OnnxEmbeddingProvider.OptionTokenizerVocabPath -> vocabPath)
        )
      )

      result.isLeft shouldBe true
      result.left.value.context.get("provider") shouldBe Some("onnx")
      result.left.value.message should include("Failed to initialize ONNX model")
    }

    "use default settings when no custom values are provided" in {
      val settings = OnnxEmbeddingSettings()

      settings.inputTensorName shouldBe OnnxEmbeddingProvider.DefaultInputTensorName
      settings.attentionMaskTensorName shouldBe Some(OnnxEmbeddingProvider.DefaultAttentionMaskTensorName)
      settings.tokenTypeTensorName shouldBe Some(OnnxEmbeddingProvider.DefaultTokenTypeTensorName)
      settings.maxSequenceLength shouldBe OnnxEmbeddingProvider.DefaultMaxSequenceLength
      settings.tokenizerDoLowerCase shouldBe OnnxEmbeddingProvider.DefaultTokenizerDoLowerCase
      settings.tokenizerUnknownToken shouldBe OnnxEmbeddingProvider.DefaultUnknownToken
      settings.tokenizerClsToken shouldBe OnnxEmbeddingProvider.DefaultClsToken
      settings.tokenizerSepToken shouldBe OnnxEmbeddingProvider.DefaultSepToken
      settings.tokenizerPadToken shouldBe OnnxEmbeddingProvider.DefaultPadToken
    }

    "throw an error for invalid optimizationLevel" in {
      val invalidSettings = OnnxEmbeddingSettings(
        optimizationLevel = Some("invalid_level")
      )

      val result = OnnxEmbeddingProvider.validateSettings(invalidSettings)

      result.isLeft shouldBe true
      result.left.value should include("Invalid optimizationLevel")
    }

    "throw an error for invalid executionMode" in {
      val invalidSettings = OnnxEmbeddingSettings(
        executionMode = Some("invalid_mode")
      )

      val result = OnnxEmbeddingProvider.validateSettings(invalidSettings)

      result.isLeft shouldBe true
      result.left.value should include("Invalid executionMode")
    }

    "handle empty vocab.txt path gracefully" in {
      val invalidSettings = OnnxEmbeddingSettings(tokenizerVocabPath = Some(""))

      val result = OnnxEmbeddingProvider.loadTokenizer(invalidSettings)

      result.isLeft shouldBe true
      result.left.value should include("Invalid vocab.txt path")
    }

    "handle exceeding maxSequenceLength" in {
      val settings = OnnxEmbeddingSettings(maxSequenceLength = 5)
      val inputText = "This is a test input that exceeds the max sequence length."

      val result = OnnxEmbeddingProvider.tokenize(inputText, settings)

      result.isLeft shouldBe true
      result.left.value should include("Input exceeds max sequence length")
    }

    "handle session close errors gracefully" in {
      val mockSession = mock[OrtSession]
      when(mockSession.close()).thenThrow(new OrtException("Session close error"))

      val result = OnnxEmbeddingProvider.closeSession(mockSession)

      result.isLeft shouldBe true
      result.left.value should include("Ignored session close error")
    }
  }
}
