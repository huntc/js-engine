package com.typesafe.jse

import akka.actor.{ActorRef, Actor}
import scala.concurrent.duration._
import scala.concurrent.duration.FiniteDuration
import akka.contrib.pattern.Aggregator
import com.typesafe.jse.Engine._
import akka.util.ByteString
import akka.contrib.process.StreamEvents.{Ack, Done, Output}

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
  protected class EngineIOHandler(
                                   stdoutSource: ActorRef,
                                   stderrSource: ActorRef,
                                   receiver: ActorRef,
                                   timeout: FiniteDuration
                                   ) {

    import context.dispatcher

    val errorBuilder = ByteString.newBuilder
    val outputBuilder = ByteString.newBuilder

    context.system.scheduler.scheduleOnce(timeout, self, FinishProcessing)

    var errorDone, outputDone = false

    val processActivity: Actor.Receive = expect {
      case o: Output =>
        sender match {
          case `stderrSource` => errorBuilder ++= o.data
          case `stdoutSource` => outputBuilder ++= o.data
        }
        sender ! Ack
      case exitValue: Int =>
        unexpect(processActivity)
        receiver ! JsExecutionResult(exitValue, outputBuilder.result(), errorBuilder.result())
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
   * The response of JS execution in the cases where it has been aggregated. A non-zero exit value
   * indicates failure as per the convention of stdio processes. The output and error fields are
   * aggregated from any respective output and error streams from the process.
   */
  case class JsExecutionResult(exitValue: Int, output: ByteString, error: ByteString)

  // Internal types

  private[jse] case object FinishProcessing

}
