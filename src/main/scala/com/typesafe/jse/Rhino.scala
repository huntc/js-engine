package com.typesafe.jse

import akka.actor._
import org.mozilla.javascript.tools.shell.Main
import scala.collection.mutable.ListBuffer
import org.mozilla.javascript._
import scala.concurrent.blocking
import java.io._
import akka.contrib.process.StreamEvents.Ack
import akka.contrib.process.Source
import scala.collection.immutable
import com.typesafe.jse.Engine.ExecuteJs
import com.typesafe.jse.Engine.ExecuteJs
import akka.actor.Terminated

/**
 * Declares an in-JVM Rhino based JavaScript engine. The actor is expected to be
 * associated with a blocking dispatcher as calls to Rhino and its use of Jdk streams
 * are blocking.
 */
class Rhino extends Engine {

  // The main objective of this actor implementation is to establish actors for both the execution of
  // Rhino code (Rhino's execution is blocking), and actors for the source of stdio (which is also blocking).
  // This actor is then a conduit of the IO as a result of execution.

  val stdoutOs = new PipedOutputStream()
  val stderrOs = new PipedOutputStream()

  val stdoutIs = new PipedInputStream(stdoutOs)
  val stderrIs = new PipedInputStream(stderrOs)

  expectOnce {
    case ExecuteJs(source, args, timeout, timeoutExitValue) =>
      val requester = sender

      val stdoutSource = context.actorOf(Source.props(stdoutIs, self))
      val stderrSource = context.actorOf(Source.props(stderrIs, self))

      new EngineIOHandler(stdoutSource, stderrSource, requester, Ack, timeout, timeoutExitValue)

      context.actorOf(RhinoShell.props(
        source.getParentFile.getCanonicalFile,
        immutable.Seq(source.getCanonicalPath) ++ args,
        stdoutOs, stdoutSource,
        stderrOs, stderrSource
      ))
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

object Rhino {
  /**
   * Give me a Rhino props.
   */
  def props(): Props = {
    Props(classOf[Rhino])
  }

}


/**
 * Manage the execution of the Rhino shell setting up its environment, running the main entry point
 * and sending its parent the exit code when it can see that the stdio sources have closed.
 */
private[jse] class RhinoShell(
                               moduleBase: File,
                               args: immutable.Seq[String],
                               stdoutOs: OutputStream, stdoutSource: ActorRef,
                               stderrOs: OutputStream, stderrSource: ActorRef
                               ) extends Actor with ActorLogging {

  // A utility to safely manage Rhino contexts.
  def withContext(block: => Unit): Unit = {
    Context.enter()
    try block finally {
      Context.exit()
    }
  }

  // Initialise our Rhino environment. If we've never done so before then do general Rhino shell
  // initialisation and then override its print function. The Rhino shell is all static so this
  // only need be done once.
  if (!Main.getGlobal.isInitialized) {
    Main.getGlobal.init(Main.shellContextFactory)
    withContext {
      Main.getGlobal.defineFunctionProperties(Array("print"), classOf[RhinoShell], ScriptableObject.DONTENUM)
    }
  }

  // Each time we use Rhino we set properties in the global scope that represent the stdout and stderr
  // output streams. These output streams are plugged into our source actors.
  withContext {
    def jsStdoutOs = Context.javaToJS(stdoutOs, Main.getGlobal)
    ScriptableObject.putProperty(Main.getGlobal, "stdout", jsStdoutOs)
    def jsStderrOs = Context.javaToJS(stderrOs, Main.getGlobal)
    ScriptableObject.putProperty(Main.getGlobal, "stderr", jsStderrOs)
  }

  // Formulate arguments to the Rhino shell.
  val lb = ListBuffer[String]()
  lb ++= Seq(
    "-opt", "-1",
    "-modules", moduleBase.getCanonicalPath
  )
  lb ++= args


  val exitCode = blocking {
    try {
      if (log.isDebugEnabled) {
        log.debug("Invoking Rhino with {}", lb.toString())
      }
      Main.exec(lb.toArray)
    } finally {
      stdoutOs.close()
      stderrOs.close()
    }
  }

  // When all streams are closed then we signal that Rhino has exited. This is entirely satisfactory in
  // a Rhino shell situation given that we are in control of the stdout and stderr. Our contract to the
  // outside world (EngineIOHandler) is that the exit status is always sent after stdout and stderr as
  // per the akka.contrib.process package.

  context.watch(stdoutSource)
  context.watch(stderrSource)

  var openStreams = 2

  def receive = {
    case Terminated(`stdoutSource` | `stderrSource`) =>
      openStreams -= 1
      if (openStreams == 0) {
        context.parent ! exitCode
      }
  }

}

private[jse] object RhinoShell {
  def props(
             moduleBase: File,
             args: immutable.Seq[String],
             stdoutOs: OutputStream, stdoutSource: ActorRef,
             stderrOs: OutputStream, stderrSource: ActorRef
             ): Props = {
    Props(classOf[RhinoShell], moduleBase, args, stdoutOs, stdoutSource, stderrOs, stderrSource)
  }

  private val lineSeparator = System.getProperty("line.separator").getBytes("UTF-8")

  /*
   * Our override of Rhino's print function. Output is sent to the stdout source. There is no provision in Rhino's
   * shell to send to stderr.
   *
   * Has to be public in order for the Rhino shell to find it.
   */
  def print(
             cx: Context,
             thisObj: Scriptable,
             args: Array[Any],
             funObj: org.mozilla.javascript.Function
             ): Any = {
    val property = funObj.getParentScope.get("stdout", thisObj)
    property match {
      case jsOs: NativeJavaObject =>
        jsOs.unwrap() match {
          case os: OutputStream =>
            args.foreach {
              arg =>
                val s = Context.toString(arg)
                os.write(s.getBytes("UTF-8"))
            }
            os.write(lineSeparator)
        }
    }
    Context.getUndefinedValue
  }
}