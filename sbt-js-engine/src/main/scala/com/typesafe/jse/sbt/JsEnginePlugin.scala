package com.typesafe.jse.sbt

import sbt._
import akka.actor.ActorSystem
import akka.util.Timeout
import scala.concurrent.duration._

/**
 * Declares the main parts of a WebDriver based plugin for sbt.
 */
abstract class JsEnginePlugin extends sbt.Plugin {

  object JsEngineKeys {

    object EngineType extends Enumeration {
      val CommonNode, Node, PhantomJs, Rhino = Value
    }

    val engineType = SettingKey[EngineType.Value]("jse-type", "The type of engine to use.")
    val parallelism = SettingKey[Int]("jse-parallelism", "The number of parallel tasks for the JavaScript engine. Defaults to the # of available processors + 1 to keep things busy.")
  }

  import JsEngineKeys._

  implicit val jseSystem = withActorClassloader(ActorSystem("jse-system"))
  implicit val jseTimeout = Timeout(5.seconds)

  override val globalSettings: Seq[Setting[_]] = Seq(
    engineType := EngineType.Rhino,
    parallelism := java.lang.Runtime.getRuntime.availableProcessors() + 1
  )

  /*
   * Sometimes the class loader associated with the actor system is required e.g. when loading configuration in sbt.
   */
  private def withActorClassloader[A](f: => A): A = {
    val newLoader = ActorSystem.getClass.getClassLoader
    val thread = Thread.currentThread
    val oldLoader = thread.getContextClassLoader

    thread.setContextClassLoader(newLoader)
    try {
      f
    } finally {
      thread.setContextClassLoader(oldLoader)
    }
  }
}
