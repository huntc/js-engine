package com.typesafe.jse

import com.typesafe.jse.Engine.ExecuteJs
import akka.actor.Props
import org.mozilla.javascript.tools.shell.Main
import scala.collection.mutable.ListBuffer
import org.mozilla.javascript.{ScriptableObject, Context}
import scala.concurrent.blocking

/**
 * Declares an in-JVM Rhino based JavaScript engine. The actor is expected to be
 * associated with a blocking dispatcher as calls to Rhino are blocking.
 */
class Rhino extends Engine {

  expectOnce {
    case ExecuteJs(source, args, timeout, timeoutExitValue) =>
      val requester = sender

      new EngineIOHandler(self, self, requester, (), timeout, timeoutExitValue)

      if (!Main.getGlobal.isInitialized) {
        Main.getGlobal.init(Main.shellContextFactory)
      }
      Context.enter()
      try {
        def jsSelf = Context.javaToJS(self, Main.getGlobal)
        ScriptableObject.putProperty(Main.getGlobal, "engineActor", jsSelf)
      } finally {
        Context.exit()
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
      requester ! exitCode
  }

}


object Rhino {
  def props(): Props = {
    Props(classOf[Rhino])
  }

}