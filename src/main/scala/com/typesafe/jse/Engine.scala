package com.typesafe.jse

import akka.actor.{Terminated, ActorRef, Actor}
import scala.concurrent.duration._
import scala.concurrent.duration.FiniteDuration
import akka.util.ByteString
import scala.collection.immutable
import com.typesafe.jse.Engine.JsExecutionResult

/**
 * A JavaScript engine. JavaScript engines are intended to be short-lived and will terminate themselves on
 * completion of executing some JavaScript.
 */
abstract class Engine(stdArgs: immutable.Seq[String], stdEnvironment: Map[String, String]) extends Actor {

  /*
  * An engineIOHandler is a receiver that aggregates stdout and stderr from JavaScript execution.
  * Execution may also be timed out. The contract is that an exit value is always
  * only ever sent after all stdio has completed.
  */
  def engineIOHandler(
                       stdinSink: ActorRef,
                       stdoutSource: ActorRef,
                       stderrSource: ActorRef,
                       receiver: ActorRef,
                       ack: => Any,
                       timeout: FiniteDuration,
                       timeoutExitValue: Int
                       ): Receive = {

    val errorBuilder = ByteString.newBuilder
    val outputBuilder = ByteString.newBuilder

    def handleStdioBytes(sender: ActorRef, bytes: ByteString): Unit = {
      sender match {
        case `stderrSource` => errorBuilder ++= bytes
        case `stdoutSource` => outputBuilder ++= bytes
      }
      sender ! ack
    }

    def sendExecutionResult(exitValue: Int): Unit = {
      receiver ! JsExecutionResult(exitValue, outputBuilder.result(), errorBuilder.result())
    }

    context.watch(stdinSink)
    context.watch(stdoutSource)
    context.watch(stderrSource)

    context.system.scheduler.scheduleOnce(timeout, self, timeoutExitValue)(context.dispatcher)

    var openStreams = 3

    {
      case bytes: ByteString => handleStdioBytes(sender, bytes)
      case exitValue: Int =>
        if (exitValue != timeoutExitValue) {
          context.become {
            case bytes: ByteString => handleStdioBytes(sender, bytes)
            case Terminated(`stdinSink` | `stdoutSource` | `stderrSource`) => {
              openStreams -= 1
              if (openStreams == 0) {
                sendExecutionResult(exitValue)
                context.stop(self)
              }
            }
          }
        } else {
          context.stop(self)
        }
      case Terminated(`stdinSink` | `stdoutSource` | `stderrSource`) =>
        openStreams -= 1
        if (openStreams == 0) {
          context.become {
            case exitValue: Int =>
              sendExecutionResult(exitValue)
              context.stop(self)
          }
        }
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
   * @param environment A mapping of environment variables to use.
   */
  case class ExecuteJs(
                        source: java.io.File,
                        args: immutable.Seq[String],
                        timeout: FiniteDuration,
                        timeoutExitValue: Int = -1,
                        environment: Map[String, String] = Map.empty
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
