package com.typesafe.jse

import com.typesafe.jse.Engine.ExecuteJs
import akka.actor.{Props, ActorSystem}
import org.mozilla.javascript.tools.shell.Main
import scala.collection.mutable.ListBuffer
import org.mozilla.javascript.{ScriptableObject, Context, NativeJavaObject}

/**
 * Declares an in-JVM Rhino based JavaScript engine.
 */
class Rhino extends Engine {

  expectOnce {
    case ExecuteJs(source, args, timeout) =>
// FIXME
//      new EngineIOHandler(sender, timeout)

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
      Main.exec(lb.toArray)
  }

}


object Rhino {
  def props()(implicit system: ActorSystem): Props = {
    Props(classOf[Rhino])
  }

}