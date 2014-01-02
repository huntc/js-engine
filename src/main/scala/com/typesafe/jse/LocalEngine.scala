package com.typesafe.jse

import akka.actor._
import scala.collection.mutable.ListBuffer
import com.typesafe.jse.Engine.ExecuteJs
import akka.contrib.process.BlockingProcess
import akka.contrib.process.BlockingProcess.Started
import scala.collection.immutable
import akka.contrib.process.StreamEvents.Ack

/**
 * Provides an Actor on behalf of a JavaScript Engine. Engines are represented as operating system processes and are
 * communicated with by launching with arguments and returning a status code.
 * @param stdArgs a sequence of standard command line arguments used to launch the engine from the command line.
 */
class LocalEngine(stdArgs: immutable.Seq[String]) extends Engine {

  def receive = {
    case ExecuteJs(f, args, timeout, timeoutExitValue, modulePaths) =>
      val requester = sender
      val lb = ListBuffer[String]()
      lb ++= stdArgs
      lb += f.getCanonicalPath
      lb ++= args
      context.actorOf(BlockingProcess.props(lb.to[immutable.Seq], Map.empty, self), "process")
      context.become {
        case Started(i, o, e) =>
          context.become(engineIOHandler(i, o, e, requester, Ack, timeout, timeoutExitValue))
          i ! PoisonPill // We don't need an input stream so close it out straight away.
      }
  }
}

/**
 * Provides an Actor on behalf of a Node JavaScript Engine. Engines are represented as operating system processes and are
 * communicated with by launching with arguments and returning a status code.
 * @param stdArgs a sequence of standard command line arguments used to launch the engine from the command line.
 */
class NodeEngine(stdArgs: immutable.Seq[String]) extends Engine {

  import NodeEngine._

  def receive = {
    case ExecuteJs(f, args, timeout, timeoutExitValue, modulePaths) =>
      val requester = sender

      context.actorOf(BlockingProcess.props((stdArgs :+ f.getCanonicalPath) ++ args, nodePathEnv(modulePaths), self), "process")
      context.become {
        case Started(i, o, e) =>
          context.become(engineIOHandler(i, o, e, requester, Ack, timeout, timeoutExitValue))
          i ! PoisonPill // We don't need an input stream so close it out straight away.
      }
  }
}

object NodeEngine {
  val nodePathDelim = if (System.getProperty("os.name").toLowerCase.contains("win")) ";" else ":"

  def nodePathEnv(modulePaths: immutable.Seq[String]): Map[String, String] = {
    val nodePath = modulePaths.mkString(nodePathDelim)
    val newNodePath = Option(System.getenv("NODE_PATH")).map(_ + nodePathDelim + nodePath).getOrElse(nodePath)
    if (newNodePath.isEmpty) Map.empty[String, String] else Map("NODE_PATH" -> newNodePath)
  }
}

/**
 * Used to manage a local instance of Node.js with CommonJs support. common-node is assumed to be on the path.
 */
object CommonNode {
  def props(): Props = {
    val args = immutable.Seq("common-node")
    Props(classOf[NodeEngine], args)
  }
}

/**
 * Used to manage a local instance of Node.js. Node is assumed to be on the path.
 */
object Node {
  def props(): Props = {
    val args = immutable.Seq("node")
    Props(classOf[NodeEngine], args)
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
