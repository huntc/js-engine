package com.typesafe.jse.tester

import akka.actor.ActorSystem
import akka.pattern.ask

import com.typesafe.jse.{Rhino, CommonNode, Engine}
import akka.util.Timeout
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global
import java.io.File
import com.typesafe.jse.Engine.JsExecutionResult

object Main {
  def main(args: Array[String]) {
    implicit val system = ActorSystem("jse-system")
    implicit val timeout = Timeout(5.seconds)

    system.scheduler.scheduleOnce(7.seconds) {
      system.shutdown()
      System.exit(1)
    }

    val engine = system.actorOf(CommonNode.props(), "engine")
    val f = new File(Main.getClass.getResource("test.js").toURI)
    for (
      result <- (engine ? Engine.ExecuteJs(f, Seq("999"))).mapTo[JsExecutionResult]
    ) yield {
      println(new String(result.output.toArray, "UTF-8"))

      try {
        system.shutdown()
        System.exit(0)
      } catch {
        case _: Throwable =>
      }

    }

  }
}
