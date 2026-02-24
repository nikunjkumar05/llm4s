package org.llm4s.llmconnect

import org.llm4s.llmconnect.model.EmbeddingRequest
import org.llm4s.llmconnect.provider.EmbeddingProvider
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class EmbeddingProviderSpec extends AnyWordSpec with Matchers {
  "EmbeddingProvider.close" should {
    "be a no-op for simple providers" in {
      val p = new EmbeddingProvider {
        override def embed(request: EmbeddingRequest) = Right(org.llm4s.llmconnect.model.EmbeddingResponse.empty)
      }

      // close should not throw
      p.close()
    }
  }
}
