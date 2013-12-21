package com.typesafe.jse

import akka.actor._
import scala.collection.mutable.ListBuffer
import scala.concurrent.blocking
import java.io._
import akka.contrib.process.StreamEvents.Ack
import akka.contrib.process.{Sink, Source}
import scala.collection.immutable
import com.typesafe.jse.Engine.ExecuteJs
import org.ringojs.repository.FileRepository

/**
 * Declares an in-JVM Ringo based JavaScript engine. The actor is expected to be
 * associated with a blocking dispatcher as its use of Jdk streams are blocking.
 */
class Ringo(ioDispatcherId: String) extends Engine {

  // The main objective of this actor implementation is to establish actors for both the execution of
  // Ringo code (Ringo's execution is blocking), and actors for the source of stdio (which is also blocking).
  // This actor is then a conduit of the IO as a result of execution.

  val stdoutOs = new PipedOutputStream()
  val stderrOs = new PipedOutputStream()

  val stdoutIs = new PipedInputStream(stdoutOs)
  val stderrIs = new PipedInputStream(stderrOs)

  def receive = {
    case ExecuteJs(source, args, timeout, timeoutExitValue, modulePaths) =>
      val requester = sender

      // Create an input stream and close it immediately as it isn't going to be used.
      val stdinOs = new PipedOutputStream()
      val stdinIs = new PipedInputStream(stdinOs)

      try {
        val stdinSink = context.actorOf(Sink.props(stdinOs, ioDispatcherId = ioDispatcherId), "stdin")
        val stdoutSource = context.actorOf(Source.props(stdoutIs, self, ioDispatcherId = ioDispatcherId), "stdout")
        val stderrSource = context.actorOf(Source.props(stderrIs, self, ioDispatcherId = ioDispatcherId), "stderr")

        context.become(engineIOHandler(
          stdinSink, stdoutSource, stderrSource,
          requester,
          Ack,
          timeout, timeoutExitValue
        ))

        context.actorOf(RingoShell.props(
          source,
          args,
          modulePaths,
          stdinIs, new PrintStream(stdoutOs), new PrintStream(stderrOs)
        ), "ringo-shell") ! RingoShell.Execute

        // We don't need an input stream so close it out straight away.
        stdinSink ! PoisonPill

      } finally {
        blocking {
          closeSafely(stdinIs)
          closeSafely(stdinOs)
        }
      }

  }

  def closeSafely(closable: Closeable): Unit = {
    try {
      closable.close()
    } catch {
      case _: Exception =>
    }
  }

  override def postStop() = {
    // Be paranoid and ensure that all resources are cleared up.
    blocking {
      closeSafely(stderrIs)
      closeSafely(stdoutIs)
      closeSafely(stderrOs)
      closeSafely(stdoutOs)
    }
  }

}

object Ringo {
  /**
   * Give me a Ringo props.
   */
  def props(ioDispatcherId: String = "blocking-process-io-dispatcher"): Props = {
    Props(classOf[Ringo], ioDispatcherId)
      .withDispatcher(ioDispatcherId)
  }

}


/**
 * Manage the execution of the Ringo shell setting up its environment, running the main entry point
 * and sending its parent the exit code when we're done.
 */
private[jse] class RingoShell(
                               source: File,
                               args: immutable.Seq[String],
                               modulePaths: immutable.Seq[String],
                               stdinIs: InputStream,
                               stdoutOs: PrintStream,
                               stderrOs: PrintStream
                               ) extends Actor with ActorLogging {

  import RingoShell._

  val moduleBase = source.getParentFile.getCanonicalFile
  val sourcePath = source.getCanonicalPath

  val home = new FileRepository(new File("."))
  val userModulePaths = Array(moduleBase.getCanonicalPath) ++ modulePaths
  val sysModulePaths = Array("modules", "packages")
  val scriptArgs = Array(sourcePath) ++ args

  def receive = {
    case Execute =>
      val exitValue = blocking {
        try {
          if (log.isDebugEnabled) {
            log.debug("Invoking Ringo with {}", scriptArgs)
          }
          val config = new org.ringojs.engine.RingoConfig(home, userModulePaths, sysModulePaths)
          config.setArguments(scriptArgs)
          config.setMainScript(sourcePath)
          config.setOptLevel(-1)
          config.setSystemIn(stdinIs)
          config.setSystemOut(stdoutOs)
          config.setSystemErr(stderrOs)
          val engine = new org.ringojs.engine.RhinoEngine(config, null)
          engine.runScript(config.getMainResource, scriptArgs: _*)
          0

        } catch {
          case e: Exception =>
            log.error("Exception from Ringo during script execution", e)
            1
        } finally {
          stdoutOs.close()
          stderrOs.close()
        }
      }
      sender ! exitValue
  }
}

private[jse] object RingoShell {
  def props(
             moduleBase: File,
             args: immutable.Seq[String],
             modulePaths: immutable.Seq[String],
             stdinIs: InputStream,
             stdoutOs: PrintStream,
             stderrOs: PrintStream
             ): Props = {
    Props(classOf[RingoShell], moduleBase, args, modulePaths, stdinIs, stdoutOs, stderrOs)
  }

  case object Execute

}