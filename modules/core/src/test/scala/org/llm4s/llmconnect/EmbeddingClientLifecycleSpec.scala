package org.llm4s.llmconnect

import org.llm4s.llmconnect.config.EmbeddingModelConfig
import org.llm4s.llmconnect.model.{ EmbeddingError, EmbeddingRequest, EmbeddingResponse }
import org.llm4s.llmconnect.provider.EmbeddingProvider
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class EmbeddingClientLifecycleSpec extends AnyWordSpec with Matchers {
  final private class CountingProvider extends EmbeddingProvider {
    var closeCalls = 0

    override def embed(request: EmbeddingRequest): Either[EmbeddingError, EmbeddingResponse] =
      Right(EmbeddingResponse(embeddings = Seq(Vector(0.1d, 0.2d))))

    override def close(): Unit =
      closeCalls += 1
  }

  private val request = EmbeddingRequest(
    input = Seq("hello"),
    model = EmbeddingModelConfig("test-model", 2)
  )

  "EmbeddingClient.close" should {
    "close provider once even when called repeatedly" in {
      val provider = new CountingProvider
      val client   = new EmbeddingClient(provider)

      client.close()
      client.close()
      client.close()

      provider.closeCalls shouldBe 1
    }

    "share lifecycle state across derived clients" in {
      val provider = new CountingProvider
      val base     = new EmbeddingClient(provider)
      val derived  = base.withOperation("test-op")

      derived.close()
      provider.closeCalls shouldBe 1

      base.embed(request) match {
        case Left(err) =>
          err.message should include("already closed")
        case Right(_) =>
          fail("Expected closed client to return an error")
      }
    }
  }
}
