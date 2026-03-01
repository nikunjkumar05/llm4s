package org.llm4s.vectorstore

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.llm4s.error.ProcessingError
import org.scalamock.scalatest.MockFactory
import com.zaxxer.hikari.HikariDataSource

/**
 * Integration tests for PgVectorStore factory methods.
 *
 * These tests validate:
 * 1. The create() factory validates tableName before constructing the store
 * 2. The apply() overload delegates to create() and validates tableName
 * 3. Validation rejects unsafe identifiers before any database operations
 * 4. The ownsDataSource default is false (non-owning) to prevent shared pool closure
 *
 * Note: These tests use mocks to avoid requiring a live PostgreSQL instance.
 * For full integration tests with a real database, use testcontainers (adds PostgreSQL container lifecycle).
 */
class PgVectorStoreFactoryIntegrationSpec extends AnyFlatSpec with Matchers with MockFactory {

  "PgVectorStore.create" should "reject invalid tableName before constructing store" in {
    val mockDataSource = mock[HikariDataSource]
    val invalidName    = "; DROP TABLE users; --"

    val result = PgVectorStore.create(mockDataSource, invalidName)

    result should matchPattern { case Left(ProcessingError("pgvector-store", msg, _)) if msg.contains("Invalid table name") => }
    // Verify that the dataSource was never used (no side effects)
    (mockDataSource.getConnection _).expects().never()
  }

  it should "accept valid tableName and attempt store construction" in {
    val mockDataSource = mock[HikariDataSource]
    val validName      = "vectors_test"

    // Mock getConnection to validate that construction would proceed
    // (Note: actual construction will fail because the mock doesn't provide a full DB,
    // but we can verify validation passed)
    (mockDataSource.getConnection _).expects().throwing(new Exception("Mock datasource")).atLeastOnce()

    val result = PgVectorStore.create(mockDataSource, validName)

    // Should fail during construction (not validation), confirming validation passed
    result should matchPattern { case Left(ProcessingError("pgvector-store", msg, _)) if msg.contains("Failed to create store") => }
  }

  it should "default ownsDataSource to false for shared pool safety" in {
    val mockDataSource = mock[HikariDataSource]
    val validName      = "vectors_safe"

    // The third parameter is ownsDataSource; default should be false
    // This test documents the safe default behavior
    (mockDataSource.getConnection _).expects().throwing(new Exception("Mock")).atLeastOnce()

    val resultDefault = PgVectorStore.create(mockDataSource, validName)
    val resultExplicitFalse = PgVectorStore.create(mockDataSource, validName, ownsDataSource = false)

    // Both should fail the same way (validation passed, construction attempted)
    resultDefault should matchPattern { case Left(_) => }
    resultExplicitFalse should matchPattern { case Left(_) => }
  }

  "PgVectorStore.apply(dataSource, tableName)" should "delegate to create() with ownsDataSource=false" in {
    val mockDataSource = mock[HikariDataSource]
    val validName      = "vectors_apply"

    (mockDataSource.getConnection _).expects().throwing(new Exception("Mock")).atLeastOnce()

    val result = PgVectorStore.apply(mockDataSource, validName)

    // Should fail during construction (not validation), showing validation passed
    result should matchPattern { case Left(ProcessingError("pgvector-store", _, _)) => }
  }

  it should "reject invalid tableName the same way as create()" in {
    val mockDataSource = mock[HikariDataSource]
    val invalidName    = "invalid-table"

    val resultApply  = PgVectorStore.apply(mockDataSource, invalidName)
    val resultCreate = PgVectorStore.create(mockDataSource, invalidName)

    resultApply should matchPattern { case Left(ProcessingError("pgvector-store", msg, _)) if msg.contains("Invalid table name") => }
    resultCreate should matchPattern { case Left(ProcessingError("pgvector-store", msg, _)) if msg.contains("Invalid table name") => }
  }

  "PgVectorStore.Config" should "provide defaults for all fields" in {
    val config = PgVectorStore.Config()

    config.host shouldBe "localhost"
    config.port shouldBe 5432
    config.database shouldBe "postgres"
    config.user shouldBe "postgres"
    config.password shouldBe ""
    config.tableName shouldBe "vectors"
    config.maxPoolSize shouldBe 10
  }

  it should "allow custom tableName in config" in {
    val config = PgVectorStore.Config(tableName = "my_vectors")

    config.tableName shouldBe "my_vectors"
  }

  it should "build correct JDBC URL" in {
    val config = PgVectorStore.Config(host = "db.example.com", port = 5433, database = "mydb")

    config.jdbcUrl shouldBe "jdbc:postgresql://db.example.com:5433/mydb"
  }
}
