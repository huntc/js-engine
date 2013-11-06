package com.typesafe.jse

import akka.actor._
import scala.sys.process._
import java.io.InputStream
import akka.contrib.pattern.Aggregator
import com.typesafe.jse.LocalEngine._
import scala.concurrent.duration._
import scala.io.Source
import com.typesafe.jse.Engine.{JsExecutionOutput, JsExecutionError, ExecuteJs}
import com.typesafe.jse.LocalEngine.ErrorEvent
import scala.collection.mutable.ListBuffer

/**
 * Provides an Actor on behalf of a JavaScript Engine. Engines are represented as operating system processes and are
 * communicated with by launching with arguments and returning a status code.
 * @param stdArgs a sequence of standard command line arguments used to launch the engine from the command line.
 */
class LocalEngine(stdArgs: Seq[String]) extends Engine with ActorLogging with Aggregator {

  expectOnce {
    case ExecuteJs(f, args, timeout) =>
      val lb = ListBuffer[String]()
      lb ++= stdArgs
      lb += f.getCanonicalPath
      lb ++= args
      new ProcessIOHandler(sender, timeout)
      val pio = new ProcessIO(_ => (), self ! OutputEvent(_), self ! ErrorEvent(_))
      Process(lb.toSeq).run(pio)
  }

  private class ProcessIOHandler(originalSender: ActorRef, timeout: FiniteDuration) {

    import context.dispatcher

    val errorBuilder = new StringBuilder
    val outputBuilder = new StringBuilder

    context.system.scheduler.scheduleOnce(timeout, self, TimedOut)

    def handleResponse(is: InputStream, sb: StringBuilder): Unit = {
      val source = Source.fromInputStream(is)
      source.getLines().foreach(sb.append)
      val reader = source.reader()
      val nextChar = reader.read()
      if (nextChar == -1) processFinal() else sb.append(nextChar)
    }

    val processActivity = expect {
      case ErrorEvent(is) => handleResponse(is, errorBuilder)
      case OutputEvent(is) => handleResponse(is, outputBuilder)
      case TimedOut ⇒ processFinal()
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

object LocalEngine {

  private[jse] case class OutputEvent(is: InputStream)

  private[jse] case class ErrorEvent(is: InputStream)

  private[jse] case object TimedOut

}

/**
 * Used to manage a local instance of Node.js with CommonJs support. common-node is assumed to be on the path.
 */
object CommonNode {
  def props()(implicit system: ActorSystem): Props = {
    val args = Seq("common-node")
    Props(classOf[LocalEngine], args)
  }
}

/**
 * Used to manage a local instance of Node.js. Node is assumed to be on the path.
 */
object Node {
  def props()(implicit system: ActorSystem): Props = {
    val args = Seq("node")
    Props(classOf[LocalEngine], args)
  }
}

/**
 * Used to manage a local instance of PhantomJS. PhantomJS is assumed to be on the path.
 */
object PhantomJs {
  def props()(implicit system: ActorSystem): Props = {
    val args = Seq("phantomjs")
    Props(classOf[LocalEngine], args)
  }
}
