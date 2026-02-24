package org.llm4s.llmconnect

import org.llm4s.llmconnect.config.EmbeddingProviderConfig
import org.llm4s.llmconnect.model.EmbeddingError
import org.scalatest.EitherValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class EmbeddingClientFromSpec extends AnyWordSpec with Matchers with EitherValues {
  "EmbeddingClient.from" should {
    "return error for unsupported provider" in {
      val cfg = EmbeddingProviderConfig(baseUrl = "", model = "", apiKey = "", options = Map.empty)
      val res = EmbeddingClient.from("unknown-provider", cfg)
      res.isLeft shouldBe true
      res.left.value shouldBe a[EmbeddingError]
    }

    "return error for onnx when runtime dependency is missing or fails" in {
      val cfg = EmbeddingProviderConfig(baseUrl = "", model = "model.onnx", apiKey = "", options = Map.empty)
      val res = EmbeddingClient.from("onnx", cfg)
      res.isLeft shouldBe true
      // message should mention ONNX when the optional dependency is missing
      res.left.value.message.toLowerCase should include("onnx")
    }
  }
}
