package com.typesafe.jse

import akka.actor.{ActorRef, Actor}
import scala.concurrent.duration._
import scala.concurrent.duration.FiniteDuration
import akka.contrib.pattern.Aggregator
import com.typesafe.jse.Engine._
import com.typesafe.jse.Engine.JsExecutionOutput
import com.typesafe.jse.Engine.ErrorEvent
import com.typesafe.jse.Engine.JsExecutionError

/**
 * A JavaScript engine. Engines are intended to be short-lived and will terminate themselves on
 * completion of executing some JavaScript.
 */
abstract class Engine extends Actor with Aggregator {

  /*
   * An EngineIOHandler aggregates stdout and stderr from JavaScript execution. When there
   * is no more then either a JsExecutionError or JsExecutionOutput is sent to the original
   * requester of execution. Execution may also be timed out.
   */
  protected class EngineIOHandler(originalSender: ActorRef, timeout: FiniteDuration) {

    import context.dispatcher

    val errorBuilder = new StringBuilder
    val outputBuilder = new StringBuilder

    context.system.scheduler.scheduleOnce(timeout, self, TimedOut)

    val processActivity = expect {
      case ErrorEvent(error) => errorBuilder.append(error)
      case OutputEvent(output) => outputBuilder.append(output)
      case FinalEvent | TimedOut => processFinal()
    }

    def processFinal() {
      unexpect(processActivity)
      if (errorBuilder.length > 0) {
        originalSender ! JsExecutionError(errorBuilder.toString())
      } else {
        originalSender ! JsExecutionOutput(outputBuilder.toString())
      }
      context.stop(self)
    }

  }

}

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

  /**
   * For describing IO events.
   */
  sealed class IOEvent(data: String)

  /**
   * Stream some output.
   */
  case class OutputEvent(output: String) extends IOEvent(output)

  /**
   * Stream some error.
   */
  case class ErrorEvent(error: String) extends IOEvent(error)

  /**
   * Signal that there are no more events.
   */
  case object FinalEvent

  // Internal types

  private[jse] case object TimedOut

}
