import sbt._

import com.typesafe.sbt.web.SbtWebPlugin
import com.typesafe.sbt.jse.SbtJsEnginePlugin
import xsbti.Severity

object TestBuild extends Build {

  object TestLogger extends Logger {
    val messages = new StringBuilder()

    def trace(t: => Throwable): Unit = {}

    def success(message: => String): Unit = {}

    def log(level: Level.Value, message: => String): Unit = messages ++= message
  }

  object TestReporter extends LoggerReporter(-1, TestLogger)

  lazy val root = Project(
    id = "test-build",
    base = file("."),
    settings =
      Project.defaultSettings ++
        SbtWebPlugin.webSettings ++
        SbtJsEnginePlugin.jsEngineSettings ++
        TestPlugin.testSettings ++
        Seq(
          SbtWebPlugin.WebKeys.reporter := TestReporter,
          TaskKey[Unit]("check") := {
            val errorCount = TestReporter.count.get(Severity.Error)
            if (errorCount != 1) {
              sys.error(s"$errorCount errors received when 1 was expected.")
            }
            val messages = TestLogger.messages.toString()
            if (
              !messages.contains("Some compilation error message.")
            ) {
              sys.error(messages)
            }
          }
        )
  )

}