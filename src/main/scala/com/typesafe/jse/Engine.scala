package com.typesafe.jse

import akka.actor.{ActorRef, Actor}
import scala.concurrent.duration._
import scala.concurrent.duration.FiniteDuration
import akka.contrib.pattern.Aggregator
import com.typesafe.jse.Engine._
import akka.contrib.process.Process._
import akka.util.ByteString
import akka.contrib.process.Process.OutputData
import akka.contrib.process.Process.ErrorData

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

    context.system.scheduler.scheduleOnce(timeout, self, FinishProcessing)

    var errorDone, outputDone = false

    val processActivity: Actor.Receive = expect {
      case e: ErrorData =>
        errorBuilder ++= e.data
        sender ! Ack
      case o: OutputData =>
        outputBuilder ++= o.data
        sender ! Ack
      case ErrorDone =>
        errorDone = true
        if (outputDone) self ! FinishProcessing
      case OutputDone =>
        outputDone = true
        if (errorDone) self ! FinishProcessing
      case FinishProcessing =>
        unexpect(processActivity)
        originalSender ! JsExecutionResult(outputBuilder.result(), errorBuilder.result())
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
   * The response of JS execution in the cases where it has been aggregated.
   */
  case class JsExecutionResult(output: ByteString, error: ByteString)

  // Internal types

  private[jse] case object FinishProcessing

}
