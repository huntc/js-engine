package com.typesafe.jse

import akka.actor.Actor
import scala.concurrent.duration._
import scala.concurrent.duration.FiniteDuration

/**
 * A JavaScript engine.
 */
abstract class Engine extends Actor

object Engine {

  /**
   * Execute JS.
   */
  case class ExecuteJs(source: java.io.File, args: Seq[String], timeout: FiniteDuration = 10.seconds)

  /**
   * The error response of JS execution. If this is sent then they'll be no JsExecutionOutput.
   */
  case class JsExecutionError(error: String)

  /**
   * The output of a JS execution.
   */
  case class JsExecutionOutput(output: String)

}
