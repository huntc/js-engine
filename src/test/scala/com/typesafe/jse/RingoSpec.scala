package com.typesafe.jse

import org.junit.runner.RunWith
import org.specs2.runner.JUnitRunner
import org.specs2.mutable.Specification
import com.typesafe.jse.Engine.JsExecutionResult
import java.io.File
import scala.collection.immutable
import akka.pattern.ask
import org.specs2.time.NoTimeConversions
import akka.util.Timeout
import akka.actor.ActorSystem
import scala.concurrent.Await

@RunWith(classOf[JUnitRunner])
class RingoSpec extends Specification with NoTimeConversions {

  "The Ringo engine" should {
    "execute some javascript by passing in a string arg and comparing its return value" in {
      val system = ActorSystem()
      val engine = system.actorOf(Ringo.props())
      val f = new File(classOf[RingoSpec].getResource("test.js").toURI)
      implicit val timeout = Timeout(5000L)

      val futureResult = (engine ? Engine.ExecuteJs(f, immutable.Seq("999"))).mapTo[JsExecutionResult]
      val result = Await.result(futureResult, timeout.duration)
      new String(result.output.toArray, "UTF-8").trim must_== "999"

    }
  }

}
