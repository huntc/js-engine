package com.typesafe.jse

import org.junit.runner.RunWith
import org.specs2.runner.JUnitRunner
import org.specs2.mutable.Specification

@RunWith(classOf[JUnitRunner])
class LocalEngineSpec extends Specification {

  "The Rhino engine" should {
    "execute some javascript" in new TestActorSystem {
    }
  }

}
