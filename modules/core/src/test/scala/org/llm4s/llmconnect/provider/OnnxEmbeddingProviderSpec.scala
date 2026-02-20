package org.llm4s.llmconnect.provider

import org.llm4s.llmconnect.config.{ EmbeddingModelConfig, EmbeddingProviderConfig }
import org.llm4s.llmconnect.model.EmbeddingRequest
import org.scalatest.EitherValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.nio.file.Paths

class OnnxEmbeddingProviderSpec extends AnyWordSpec with Matchers with EitherValues {
  private val testModelPath: String =
    Option(getClass.getClassLoader.getResource("model.onnx"))
      .map(url => Paths.get(url.toURI).toString)
      .getOrElse(Paths.get("modules", "core", "src", "test", "resources", "model.onnx").toString)

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

  "OnnxEmbeddingProvider.fromConfig" should {
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
          OnnxEmbeddingProvider.OptionVocabSize               -> "16000",
          OnnxEmbeddingProvider.OptionOptimizationLevel       -> "basic",
          OnnxEmbeddingProvider.OptionExecutionMode           -> "parallel"
        )
      )

      val provider = OnnxEmbeddingProvider.fromConfig(cfg).asInstanceOf[OnnxEmbeddingProvider]
      provider.settings.inputTensorName shouldBe "ids"
      provider.settings.attentionMaskTensorName shouldBe None
      provider.settings.tokenTypeTensorName shouldBe None
      provider.settings.maxSequenceLength shouldBe 128
      provider.settings.vocabSize shouldBe 16000
      provider.settings.optimizationLevel.map(_.name()) shouldBe Some("BASIC_OPT")
      provider.settings.executionMode.map(_.name()) shouldBe Some("PARALLEL")
    }

    "fallback to defaults when ONNX options are omitted" in {
      val cfg = EmbeddingProviderConfig(
        baseUrl = "",
        model = "missing-model.onnx",
        apiKey = "not-required",
        options = Map.empty
      )

      val provider = OnnxEmbeddingProvider.fromConfig(cfg).asInstanceOf[OnnxEmbeddingProvider]
      provider.settings.inputTensorName shouldBe OnnxEmbeddingProvider.DefaultInputTensorName
      provider.settings.attentionMaskTensorName shouldBe Some(OnnxEmbeddingProvider.DefaultAttentionMaskTensorName)
      provider.settings.tokenTypeTensorName shouldBe Some(OnnxEmbeddingProvider.DefaultTokenTypeTensorName)
      provider.settings.outputTensorName shouldBe None
      provider.settings.maxSequenceLength shouldBe OnnxEmbeddingProvider.DefaultMaxSequenceLength
      provider.settings.vocabSize shouldBe OnnxEmbeddingProvider.DefaultVocabSize
      provider.settings.optimizationLevel shouldBe None
      provider.settings.executionMode shouldBe None
    }

    "ignore invalid numeric and enum options and use safe defaults" in {
      val cfg = EmbeddingProviderConfig(
        baseUrl = "",
        model = "missing-model.onnx",
        apiKey = "not-required",
        options = Map(
          OnnxEmbeddingProvider.OptionMaxSequenceLength -> "0",
          OnnxEmbeddingProvider.OptionVocabSize         -> "not-a-number",
          OnnxEmbeddingProvider.OptionOptimizationLevel -> "turbo",
          OnnxEmbeddingProvider.OptionExecutionMode     -> "concurrent"
        )
      )

      val provider = OnnxEmbeddingProvider.fromConfig(cfg).asInstanceOf[OnnxEmbeddingProvider]
      provider.settings.maxSequenceLength shouldBe OnnxEmbeddingProvider.DefaultMaxSequenceLength
      provider.settings.vocabSize shouldBe OnnxEmbeddingProvider.DefaultVocabSize
      provider.settings.optimizationLevel shouldBe None
      provider.settings.executionMode shouldBe None
    }
  }

  "OnnxEmbeddingProvider" should {
    "return an embedding error for invalid model path without throwing at construction time" in {
      val provider = new OnnxEmbeddingProvider("invalid/path/to/model.onnx")
      val request = EmbeddingRequest(
        input = Seq("Sample text"),
        model = EmbeddingModelConfig(name = "all-MiniLM-L6-v2", dimensions = 384)
      )

      val result = provider.embed(request)
      result.isLeft shouldBe true
      result.left.value.provider shouldBe "onnx"
      result.left.value.message should include("Failed to initialize ONNX model")
    }

    "embed successfully with the test ONNX model" in {
      val provider = OnnxEmbeddingProvider
        .fromConfig(
          EmbeddingProviderConfig(
            baseUrl = "",
            model = testModelPath,
            apiKey = "not-required"
          )
        )
        .asInstanceOf[OnnxEmbeddingProvider]

      try {
        val request = EmbeddingRequest(
          input = Seq("Hello ONNX embeddings"),
          model = EmbeddingModelConfig(name = "all-MiniLM-L6-v2", dimensions = 384)
        )

        val result = provider.embed(request)
        result.isRight shouldBe true
        result.value.embeddings should have size 1
        result.value.embeddings.head should not be empty
        result.value.metadata("provider") shouldBe "onnx"
      } finally
        provider.close()
    }

    "return an embedding error when configured input tensor name does not exist" in {
      val provider = OnnxEmbeddingProvider
        .fromConfig(
          EmbeddingProviderConfig(
            baseUrl = "",
            model = testModelPath,
            apiKey = "not-required",
            options = Map(OnnxEmbeddingProvider.OptionInputTensorName -> "missing_input_tensor")
          )
        )
        .asInstanceOf[OnnxEmbeddingProvider]

      try {
        val request = EmbeddingRequest(
          input = Seq("Hello ONNX embeddings"),
          model = EmbeddingModelConfig(name = "all-MiniLM-L6-v2", dimensions = 384)
        )

        val result = provider.embed(request)
        result.isLeft shouldBe true
        result.left.value.message should include("Configured input tensor 'missing_input_tensor' not found")
      } finally
        provider.close()
    }

    "return an embedding error when configured output tensor name does not exist" in {
      val provider = OnnxEmbeddingProvider
        .fromConfig(
          EmbeddingProviderConfig(
            baseUrl = "",
            model = testModelPath,
            apiKey = "not-required",
            options = Map(OnnxEmbeddingProvider.OptionOutputTensorName -> "missing_output_tensor")
          )
        )
        .asInstanceOf[OnnxEmbeddingProvider]

      try {
        val request = EmbeddingRequest(
          input = Seq("Hello ONNX embeddings"),
          model = EmbeddingModelConfig(name = "all-MiniLM-L6-v2", dimensions = 384)
        )

        val result = provider.embed(request)
        result.isLeft shouldBe true
        result.left.value.message should include("Configured output tensor 'missing_output_tensor' not found")
      } finally
        provider.close()
    }
  }
}
