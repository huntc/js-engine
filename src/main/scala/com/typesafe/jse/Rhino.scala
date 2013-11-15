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

        val stdoutSource = context.watch(context.actorOf(Source.props(stdoutIs, self)))
        val stderrSource = context.watch(context.actorOf(Source.props(stderrIs, self)))

        new EngineIOHandler(stdoutSource, stderrSource, requester, Ack, timeout, timeoutExitValue)

        if (!Main.getGlobal.isInitialized) {
          Main.getGlobal.init(Main.shellContextFactory)
          withContext {
            Main.getGlobal.defineFunctionProperties(Array("print"), classOf[Rhino], ScriptableObject.DONTENUM)
          }
        }
        withContext {
          def jsStdoutOs = Context.javaToJS(stdoutOs, Main.getGlobal)
          ScriptableObject.putProperty(Main.getGlobal, "stdout", jsStdoutOs)
          def jsStderrOs = Context.javaToJS(stderrOs, Main.getGlobal)
          ScriptableObject.putProperty(Main.getGlobal, "stderr", jsStderrOs)
        }

        val lb = ListBuffer[String]()
        lb ++= Seq(
          "-opt", "-1",
          "-modules", source.getParentFile.getCanonicalPath,
          source.getCanonicalPath
        )
        lb ++= args

        // We have to assume that Rhino is blocking - who knows what will be going on within the submitted JS?
        val exitCode = blocking {
          Main.exec(lb.toArray)
        }

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
  def props(): Props = {
    Props(classOf[Rhino])
  }

  /*
   * Our override of Rhino's print function.
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
        }
    }
    Context.getUndefinedValue
  }

}
