package org.llm4s.llmconnect.provider

import org.llm4s.llmconnect.config.{ EmbeddingModelConfig, EmbeddingProviderConfig }
import org.llm4s.llmconnect.model.EmbeddingRequest
import org.scalatest.EitherValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class OnnxEmbeddingProviderSpec extends AnyWordSpec with Matchers with EitherValues {

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
  }
}
