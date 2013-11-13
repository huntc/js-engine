package com.typesafe.jse

import akka.actor.{ActorRef, Actor}
import scala.concurrent.duration._
import scala.concurrent.duration.FiniteDuration
import akka.contrib.pattern.Aggregator
import com.typesafe.jse.Engine._
import com.typesafe.jse.Engine.JsExecutionOutput
import com.typesafe.jse.Engine.JsExecutionError
import akka.contrib.process.Process.{Ack, EOF, OutputEvent, ErrorEvent}
import akka.util.ByteString

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

    val errorBuilder = ByteString.newBuilder
    val outputBuilder = ByteString.newBuilder

    context.system.scheduler.scheduleOnce(timeout, self, TimedOut)

    val processActivity = expect {
      case e: ErrorEvent =>
        errorBuilder ++= e.data
        sender ! Ack
      case o: OutputEvent =>
        outputBuilder ++= o.data
        sender ! Ack
      case EOF | TimedOut => processFinal()
    }

    def processFinal() {
      unexpect(processActivity)
      if (errorBuilder.length > 0) {
        originalSender ! JsExecutionError(errorBuilder.result())
      } else {
        originalSender ! JsExecutionOutput(outputBuilder.result())
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
  case class JsExecutionError(error: ByteString)

  /**
   * The output of a JS execution.
   */
  case class JsExecutionOutput(output: ByteString)

  // Internal types

  private[jse] case object TimedOut

}
