package org.llm4s.knowledgegraph.storage

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class PropertyKeyValidationSpec extends AnyFlatSpec with Matchers {

  // Property key regex: only alphanumeric, underscore, hyphen
  val propertyKeyPattern = "^[a-zA-Z0-9_-]+$".r

  def validatePropertyKey(key: String): Either[String, String] =
    if (!propertyKeyPattern.matches(key)) Left("Invalid property key")
    else if (key.contains("--")) Left("Invalid property key")
    else Right(key)

  "Property key validation" should "accept valid keys" in {
    validatePropertyKey("username") shouldBe Right("username")
    validatePropertyKey("user_id") shouldBe Right("user_id")
    validatePropertyKey("user-name") shouldBe Right("user-name")
    validatePropertyKey("User123") shouldBe Right("User123")
  }

  it should "reject keys with SQL metacharacters" in {
    validatePropertyKey("user name").isLeft shouldBe true
    validatePropertyKey("user;DROP TABLE").isLeft shouldBe true
    validatePropertyKey("user--comment").isLeft shouldBe true
    validatePropertyKey("user'name").isLeft shouldBe true
    validatePropertyKey("user,name").isLeft shouldBe true
    validatePropertyKey("user.name").isLeft shouldBe true
    validatePropertyKey("user@name").isLeft shouldBe true
    validatePropertyKey("user$name").isLeft shouldBe true
    validatePropertyKey("user#name").isLeft shouldBe true
  }
}
