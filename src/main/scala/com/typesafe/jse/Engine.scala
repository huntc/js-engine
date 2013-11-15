package com.typesafe.jse

import akka.actor.{ActorRef, Actor}
import scala.concurrent.duration._
import scala.concurrent.duration.FiniteDuration
import akka.contrib.pattern.Aggregator
import akka.util.ByteString
import scala.collection.immutable
import com.typesafe.jse.Engine.JsExecutionResult

/**
 * A JavaScript engine. JavaScript engines are intended to be short-lived and will terminate themselves on
 * completion of executing some JavaScript.
 */
abstract class Engine extends Actor with Aggregator {

  /*
  * An EngineIOHandler aggregates stdout and stderr from JavaScript execution.
  * Execution may also be timed out. The contract is that an exit value is always
  * only ever sent after all stdio has completed.
  */
  class EngineIOHandler(
                         stdoutSource: ActorRef,
                         stderrSource: ActorRef,
                         receiver: ActorRef,
                         ack: => Any,
                         timeout: FiniteDuration,
                         timeoutExitValue: Int
                         ) {

    val errorBuilder = ByteString.newBuilder
    val outputBuilder = ByteString.newBuilder

    context.system.scheduler.scheduleOnce(timeout, self, timeoutExitValue)(context.dispatcher)

    val processActivity: Actor.Receive = expect {
      case bytes: ByteString =>
        sender match {
          case `stderrSource` => errorBuilder ++= bytes
          case `stdoutSource` => outputBuilder ++= bytes
        }
        sender ! ack
      case exitValue: Int =>
        unexpect(processActivity)
        receiver ! JsExecutionResult(exitValue, outputBuilder.result(), errorBuilder.result())
        context.stop(self)
    }
  }

}

object Engine {

  /**
   * Execute JS. Execution will result in a JsExecutionResult being replied to the sender.
   * @param source The source file to execute.
   * @param args The sequence of arguments to pass to the js source.
   * @param timeout The amount of time to wait for the js to execute.
   * @param timeoutExitValue The exit value to receive if the above timeout occurs.
   */
  case class ExecuteJs(
                        source: java.io.File,
                        args: immutable.Seq[String],
                        timeout: FiniteDuration = 10.seconds,
                        timeoutExitValue: Int = -1
                        )

  /**
   * The response of JS execution in the cases where it has been aggregated. A non-zero exit value
   * indicates failure as per the convention of stdio processes. The output and error fields are
   * aggregated from any respective output and error streams from the process.
   */
  case class JsExecutionResult(exitValue: Int, output: ByteString, error: ByteString)

  // Internal types

  private[jse] case object FinishProcessing

}
