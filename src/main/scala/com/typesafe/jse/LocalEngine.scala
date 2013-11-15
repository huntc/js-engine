package com.typesafe.jse

import akka.actor._
import scala.collection.mutable.ListBuffer
import com.typesafe.jse.Engine.{JsExecutionResult, ExecuteJs}
import akka.contrib.process.Process
import akka.contrib.process.Process.Started
import scala.collection.immutable
import scala.concurrent.duration.FiniteDuration
import akka.util.ByteString
import akka.contrib.process.StreamEvents.{Ack, Output}

/**
 * Provides an Actor on behalf of a JavaScript Engine. Engines are represented as operating system processes and are
 * communicated with by launching with arguments and returning a status code.
 * @param stdArgs a sequence of standard command line arguments used to launch the engine from the command line.
 */
class LocalEngine(stdArgs: Seq[String]) extends Engine {

  /*
   * An EngineIOHandler aggregates stdout and stderr from JavaScript execution. When there
   * is no more then either a JsExecutionError or JsExecutionOutput is sent to the original
   * requester of execution. Execution may also be timed out.
   */
  protected class EngineIOHandler(
                                   stdoutSource: ActorRef,
                                   stderrSource: ActorRef,
                                   receiver: ActorRef,
                                   timeout: FiniteDuration,
                                   timeoutExecutionValue: Int
                                   ) {

    val errorBuilder = ByteString.newBuilder
    val outputBuilder = ByteString.newBuilder

    context.system.scheduler.scheduleOnce(timeout, self, timeoutExecutionValue)(context.dispatcher)

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

  expectOnce {
    case ExecuteJs(f, args, timeout, timeoutExecutionValue) =>
      val requester = sender
      val lb = ListBuffer[String]()
      lb ++= stdArgs
      lb += f.getCanonicalPath
      lb ++= args
      context.actorOf(Process.props(lb.to[immutable.Seq], self))
      expectOnce {
        case Started(i, o, e) => new EngineIOHandler(o, e, requester, timeout, timeoutExecutionValue)
      }
  }
}

/**
 * Used to manage a local instance of Node.js with CommonJs support. common-node is assumed to be on the path.
 */
object CommonNode {
  def props(): Props = {
    val args = Seq("common-node")
    Props(classOf[LocalEngine], args)
  }
}

/**
 * Used to manage a local instance of Node.js. Node is assumed to be on the path.
 */
object Node {
  def props(): Props = {
    val args = Seq("node")
    Props(classOf[LocalEngine], args)
  }
}

/**
 * Used to manage a local instance of PhantomJS. PhantomJS is assumed to be on the path.
 */
object PhantomJs {
  def props(): Props = {
    val args = Seq("phantomjs")
    Props(classOf[LocalEngine], args)
  }
}
