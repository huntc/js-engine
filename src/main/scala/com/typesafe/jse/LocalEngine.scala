package com.typesafe.jse

import akka.actor._
import scala.sys.process._
import akka.contrib.pattern.Aggregator
import com.typesafe.jse.Engine._
import scala.collection.mutable.ListBuffer
import com.typesafe.jse.Engine.ExecuteJs
import com.typesafe.jse.Engine.ErrorEvent
import java.io.InputStream
import scala.io.Source

/**
 * Provides an Actor on behalf of a JavaScript Engine. Engines are represented as operating system processes and are
 * communicated with by launching with arguments and returning a status code.
 * @param stdArgs a sequence of standard command line arguments used to launch the engine from the command line.
 */
class LocalEngine(stdArgs: Seq[String]) extends Engine with Aggregator {

  /*
 * Given an input stream, consume characters into a string buffer and communicate the event
 * to the engine. If there's nothing left on the input stream then signal this to the engine.
 */
  protected def respond(is: InputStream, engine: ActorRef, eventFactory: String => IOEvent): Unit = {
    val source = Source.fromInputStream(is)
    val sb = new StringBuilder
    source.getLines().foreach(sb.append)
    val reader = source.reader()
    val nextChar = reader.read()
    if (nextChar != -1) sb.append(nextChar)
    engine ! eventFactory(sb.toString)
    if (nextChar == -1) engine ! FinalEvent
  }

  expectOnce {
    case ExecuteJs(f, args, timeout) =>
      val lb = ListBuffer[String]()
      lb ++= stdArgs
      lb += f.getCanonicalPath
      lb ++= args
      new EngineIOHandler(sender, timeout)
      val pio = new ProcessIO(_ => (), respond(_, self, OutputEvent), respond(_, self, ErrorEvent))
      Process(lb.toSeq).run(pio)
  }

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
