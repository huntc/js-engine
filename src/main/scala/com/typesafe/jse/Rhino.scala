package com.typesafe.jse

import com.typesafe.jse.Engine.ExecuteJs
import akka.actor.{Terminated, Props}
import org.mozilla.javascript.tools.shell.{Global, Main}
import scala.collection.mutable.ListBuffer
import org.mozilla.javascript._
import scala.concurrent.blocking
import java.io.{OutputStream, PipedInputStream, PipedOutputStream}
import akka.contrib.process.StreamEvents.Ack
import akka.contrib.process.Source
import com.typesafe.jse.Engine.ExecuteJs

/**
 * Declares an in-JVM Rhino based JavaScript engine. The actor is expected to be
 * associated with a blocking dispatcher as calls to Rhino are blocking.
 */
class Rhino extends Engine {

  def withContext(block: => Unit): Unit = {
    Context.enter()
    try block finally {
      Context.exit()
    }
  }

  expectOnce {
    case ExecuteJs(source, args, timeout, timeoutExitValue) =>
      val requester = sender

      val stdoutOs = new PipedOutputStream()
      val stderrOs = new PipedOutputStream()

      try {
        val stdoutIs = new PipedInputStream(stdoutOs)
        val stderrIs = new PipedInputStream(stderrOs)

        // The sources are responsible for closing the input streams.
        val stdoutSource = context.watch(context.actorOf(Source.props(stdoutIs, self)))
        val stderrSource = context.watch(context.actorOf(Source.props(stderrIs, self)))

        new EngineIOHandler(stdoutSource, stderrSource, requester, Ack, timeout, timeoutExitValue)

        // Initialise our Rhino environment. If we've never done so before then do general Rhino shell
        // initialisation and then override its print function. The Rhino shell is all static so this
        // only need be done once.
        if (!Main.getGlobal.isInitialized) {
          Main.getGlobal.init(Main.shellContextFactory)
          withContext {
            Main.getGlobal.defineFunctionProperties(Array("print"), classOf[Rhino], ScriptableObject.DONTENUM)
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
          "-modules", source.getParentFile.getCanonicalPath,
          source.getCanonicalPath
        )
        lb ++= args

        // Call the Rhino shell. We have to assume that Rhino is blocking - who knows what will be going on
        // within the submitted JS?
        // FIXME: Given that it is blocking, we're not going to be in a position to process source output
        // until its finished. This could mean that Rhino is blocked from writing and thus result in a
        // deadlock. The solution is for Rhino to execute in its own thread - perhaps as its own actor?
        // At this point flushing the print function will become something worthy of consideration - to
        // flush as we go or not?
        val exitCode = blocking {
          Main.exec(lb.toArray)
        }

        // When all streams are closed then we signal that Rhino has exited. This is entirely satisfactory in
        // a Rhino shell situation given that we are in control of the stdout and stderr. Our contract to the
        // outside world (EngineIOHandler) is that the exit status is always sent after stdout and stderr as
        // per the akka.contrib.process package.
        var openStreams = 2
        expect {
          case Terminated(`stdoutSource` | `stderrSource`) =>
            openStreams -= 1
            if (openStreams == 0) {
              self ! exitCode
            }
        }

      } finally {
        blocking {
          stdoutOs.close()
          stderrOs.close()
        }
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

  private val lineSeparator = System.getProperty("line.separator").getBytes("UTF-8")

  /*
   * Our override of Rhino's print function. Output is sent to the stdout source. There is no provision in Rhino's
   * shell to send to stderr.
   */
  def print(cx: Context, thisObj: Scriptable, args: Array[Any], funObj: org.mozilla.javascript.Function): Any = {
    val property = funObj.getParentScope.get("stdout", thisObj)
    property match {
      case jsOs: NativeJavaObject =>
        jsOs.unwrap() match {
          case os: OutputStream =>
            args.foreach {
              arg => val s = Context.toString(arg)
                os.write(s.getBytes("UTF-8"))
            }
            os.write(lineSeparator)
        }
    }
    Context.getUndefinedValue
  }

}
