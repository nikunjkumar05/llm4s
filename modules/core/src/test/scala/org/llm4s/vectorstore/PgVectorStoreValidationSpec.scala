package org.llm4s.vectorstore

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.llm4s.error.ProcessingError

class PgVectorStoreValidationSpec extends AnyFlatSpec with Matchers {

  "validateTableName" should "accept valid table names" in {
    val valid = "vectors_1"
    val res   = PgVectorStore.validateTableName(valid)
    res shouldBe Right(valid)
  }

  it should "reject invalid table names" in {
    val invalids = Seq("vectors-1", "; DROP TABLE users; --", "spaces not allowed", "", null)
    invalids.foreach { n =>
      val res = PgVectorStore.validateTableName(n)
      res match {
        case Left(err: ProcessingError) => err.message should include("Invalid table name")
        case other                      => fail(s"Expected ProcessingError for '$n', got $other")
      }
    }
  }
}
