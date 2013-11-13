package com.typesafe.jse.tester

import akka.actor.ActorSystem
import akka.pattern.ask
import akka.pattern.gracefulStop

import com.typesafe.jse.{Rhino, CommonNode, Engine}
import akka.util.Timeout
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Future, Await}
import java.io.File
import com.typesafe.jse.Engine.JsExecutionOutput

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
      result <- (engine ? Engine.ExecuteJs(f, Seq("999"))).mapTo[JsExecutionOutput]
    ) yield {
      println(new String(result.output.toArray, "UTF-8"))

      try {
        val stopped: Future[Boolean] = gracefulStop(engine, 1.second)
        Await.result(stopped, 2.seconds)
        System.exit(0)
      } catch {
        case _: Throwable =>
      }

    }

  }
}
