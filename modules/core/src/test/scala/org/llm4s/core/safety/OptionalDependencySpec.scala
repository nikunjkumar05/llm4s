package org.llm4s.core.safety

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class OptionalDependencySpec extends AnyWordSpec with Matchers {
  "OptionalDependency.catchLoadFailure" should {
    "classify NoClassDefFoundError as MissingClass" in {
      val res = OptionalDependency.catchLoadFailure[Int](throw new NoClassDefFoundError("missing"))
      res.isLeft shouldBe true
      res.swap.toOption.get shouldBe a[OptionalDependency.MissingClass]
    }

    "classify ExceptionInInitializerError caused by NoClassDefFoundError as MissingClassDuringInitialization" in {
      val cause = new NoClassDefFoundError("missing")
      val e     = new ExceptionInInitializerError(cause)
      val res   = OptionalDependency.catchLoadFailure[Int](throw e)
      res.isLeft shouldBe true
      res.swap.toOption.get shouldBe a[OptionalDependency.MissingClassDuringInitialization]
    }

    "classify LinkageError as Linkage" in {
      val res = OptionalDependency.catchLoadFailure[Int](throw new LinkageError("linkage"))
      res.isLeft shouldBe true
      res.swap.toOption.get shouldBe a[OptionalDependency.Linkage]
    }
  }
}
