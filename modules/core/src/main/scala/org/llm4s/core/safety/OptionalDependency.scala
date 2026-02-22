package org.llm4s.core.safety

import scala.util.control.Exception

/**
 * Helpers for optional runtime dependencies that may fail during class loading/linking.
 */
object OptionalDependency {

  sealed trait LoadFailure extends Product with Serializable {
    def cause: Throwable
  }

  final case class MissingClass(cause: NoClassDefFoundError)                            extends LoadFailure
  final case class MissingClassDuringInitialization(cause: ExceptionInInitializerError) extends LoadFailure
  final case class Linkage(cause: LinkageError)                                         extends LoadFailure

  def catchLoadFailure[A](thunk: => A): Either[LoadFailure, A] =
    Exception
      .catching(classOf[NoClassDefFoundError], classOf[ExceptionInInitializerError], classOf[LinkageError])
      .either(thunk)
      .left
      .map {
        case e: NoClassDefFoundError =>
          MissingClass(e)
        case e: ExceptionInInitializerError if Option(e.getCause).exists(_.isInstanceOf[NoClassDefFoundError]) =>
          MissingClassDuringInitialization(e)
        case e: LinkageError =>
          Linkage(e)
        case other =>
          throw other
      }
}
