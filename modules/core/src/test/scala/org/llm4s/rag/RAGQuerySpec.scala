package org.llm4s.rag

import org.llm4s.llmconnect.provider.EmbeddingProvider
import org.llm4s.llmconnect.model.{ EmbeddingRequest, EmbeddingResponse }
import org.llm4s.llmconnect.EmbeddingClient
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.EitherValues

class RAGQuerySpec extends AnyWordSpec with Matchers with EitherValues {
  private object DummyProvider extends EmbeddingProvider {
    override def embed(request: EmbeddingRequest) =
      Right(EmbeddingResponse(embeddings = Seq(Seq.fill(3)(0.1))))
  }

  "RAG.queryWithAnswer" should {
    "return configuration error when no LLM client configured" in {
      val client = new EmbeddingClient(DummyProvider)
      val cfg    = RAGConfig().withEmbeddings(EmbeddingProvider.OpenAI)
      val ragRes = RAG.buildWithClient(cfg, client)
      ragRes.isRight shouldBe true
      val rag = ragRes.toOption.get

      val answer = rag.queryWithAnswer("What is X?")
      answer.isLeft shouldBe true
      answer.left.value.message should include("LLM client required for answer generation")
    }
  }
}
