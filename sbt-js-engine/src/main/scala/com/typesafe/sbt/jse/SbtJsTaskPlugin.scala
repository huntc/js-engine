package com.typesafe.sbt.jse

import sbt._
import sbt.Keys._
import com.typesafe.sbt.web.incremental.OpInputHasher
import spray.json._
import com.typesafe.sbt.web.incremental.OpSuccess
import com.typesafe.sbt.web.LineBasedProblem
import xsbti.{Problem, Severity}
import com.typesafe.sbt.web.incremental.OpResult
import com.typesafe.sbt.web.incremental.OpFailure
import com.typesafe.sbt.jse.SbtJsEnginePlugin.JsEngineKeys
import com.typesafe.sbt.web.SbtWebPlugin
import com.typesafe.sbt.web.incremental.OpInputHash
import akka.actor.ActorRef
import akka.util.Timeout
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.collection.immutable
import com.typesafe.jse._
import com.typesafe.jse.Node
import com.typesafe.sbt.web.incremental
import com.typesafe.sbt.web.SbtWebPlugin._
import com.typesafe.jse.Engine.JsExecutionResult
import com.typesafe.sbt.web.CompileProblems
import akka.pattern.ask


/**
 * The commonality of JS task execution oriented plugins is captured by this class.
 */
object SbtJsTaskPlugin {

  object JsTaskKeys {

    val jsTasks = SettingKey[Seq[Task[Int]]]("jstasks", "The list of JavaScript tasks to perform.")
    val fileInputHasher = TaskKey[OpInputHasher[File]]("jstask-file-input-hasher", "A function that computes constitues a change for a given file.")
    val shellSource = SettingKey[File]("jstask-shell-source", "The target location of the js shell script to use.")
    val runJsTasks = TaskKey[Int]("jstask-run-all", "Run all um tasks")
  }

  /**
   * Thrown when there is an unexpected problem to do with the task's execution.
   */
  class JsTaskFailure(m: String) extends RuntimeException(m)

  /**
   * For automatic transformation of Json structures.
   */
  object JsTaskProtocol extends DefaultJsonProtocol {

    implicit object FileFormat extends JsonFormat[File] {
      def write(f: File) = JsString(f.getCanonicalPath)

      def read(value: JsValue) = value match {
        case s: JsString => new File(s.convertTo[String])
        case _ => deserializationError("String expected for a file")
      }
    }

    implicit val opSuccessFormat = jsonFormat2(OpSuccess)

    implicit object LineBasedProblemFormat extends JsonFormat[LineBasedProblem] {
      def write(p: LineBasedProblem) = JsString("unimplemented")

      def read(value: JsValue) = value match {
        case o: JsObject => new LineBasedProblem(
          o.fields.get("message").map(_.convertTo[String]).getOrElse("unknown message"),
          o.fields.get("severity").map {
            v =>
              v.toString() match {
                case "(info)" => Severity.Info
                case "(warn)" => Severity.Warn
                case _ => Severity.Error
              }
          }.getOrElse(Severity.Error),
          o.fields.get("lineNumber").map(_.convertTo[Int]).getOrElse(0),
          o.fields.get("characterOffset").map(_.convertTo[Int]).getOrElse(0),
          o.fields.get("lineContent").map(_.convertTo[String]).getOrElse("unknown line content"),
          o.fields.get("source").map(_.convertTo[File]).getOrElse(file(""))
        )
        case _ => deserializationError("Object expected for the problem")
      }

    }

    implicit object OpResultFormat extends JsonFormat[OpResult] {

      def write(r: OpResult) = JsString("unimplemented")

      def read(value: JsValue) = value match {
        case o: JsObject => opSuccessFormat.read(o)
        case JsNull => OpFailure
        case _ => deserializationError("Object expected for the op result")
      }
    }

    case class ProblemResultsPair(results: Seq[SourceResultPair], problems: Seq[LineBasedProblem])

    case class SourceResultPair(result: OpResult, source: File)

    implicit val sourceResultPairFormat = jsonFormat2(SourceResultPair)
    implicit val problemResultPairFormat = jsonFormat2(ProblemResultsPair)
  }

}

abstract class SbtJsTaskPlugin extends sbt.Plugin {

  import WebKeys._
  import JsEngineKeys._
  import SbtJsTaskPlugin._
  import SbtJsTaskPlugin.JsTaskKeys._

  private def firstNoneZero(values: Seq[Int]): Int = values find (_ != 0) getOrElse 0

  def jsTaskUnscopedSettings = Seq(
    jsTasks := Nil,
    runJsTasks := firstNoneZero(jsTasks(_.join).value: Seq[Int])
  )

  def jsTaskSettings = inConfig(Assets)(jsTaskUnscopedSettings) ++ inConfig(TestAssets)(jsTaskUnscopedSettings) ++
    Seq(
      shellSource := {
        SbtWebPlugin.copyResourceTo(
          (target in LocalRootProject).value / "jstask-plugin",
          "shell.js",
          SbtJsTaskPlugin.getClass.getClassLoader
        )
      },
      fileInputHasher := OpInputHasher[File](source => OpInputHash.hashString(source.getAbsolutePath)),
      compile in Compile <<= (compile in Compile).dependsOn(runJsTasks in Assets),
      compile in Test <<= (compile in Test).dependsOn(runJsTasks in TestAssets)
    )

  def executeJs(
                 engine: ActorRef,
                 shellSource: File,
                 jsSources: Seq[File],
                 jsOptions: String
                 )(implicit timeout: Timeout): Future[(Map[File, OpResult], Seq[Problem])] = {

    import ExecutionContext.Implicits.global

    val args = immutable.Seq(
      JsArray(jsSources.map(x => JsString(x.getCanonicalPath)).toList).toString(),
      jsOptions
    )

    (engine ? Engine.ExecuteJs(
      shellSource,
      args,
      timeout.duration
    )).mapTo[JsExecutionResult].map {
      result: JsExecutionResult =>
        if (result.exitValue != 0) {
          throw new JsTaskFailure(new String(result.error.toArray, "UTF-8"))
        }

        val p = JsonParser(new String(result.output.toArray, "UTF-8"))
        import JsTaskProtocol._
        val prp = p.convertTo[ProblemResultsPair]
        (prp.results.map(sr => sr.source -> sr.result).toMap, prp.problems)
    }
  }

  /**
   * Primary means of executing a JavaScript shell script. unmanagedResources is assumed
   * to contain the source files to filter on.
   * @param config The sbt configuration to use e.g. Assets or TestAssets
   * @param fileFilter The files to use from "unmanagedSources in {config}"
   * @param jsOptions The JavaScript options to be passed as a param
   * @param fileInputHasher The hashing algorithm to be used for incremental execution
   * @param opDesc A description of the task for outputting to the console
   * @return A task object
   */
  def jsTask(
              config: Configuration,
              fileFilter: SettingKey[FileFilter],
              jsOptions: TaskKey[String],
              fileInputHasher: TaskKey[OpInputHasher[java.io.File]],
              opDesc: String
              ): Def.Initialize[Task[Int]] = Def.task {

    import scala.concurrent.duration._
    val timeoutPerSource = 30.seconds

    val engineProps = engineType.value match {
      case EngineType.CommonNode => CommonNode.props(stdEnvironment = NodeEngine.nodePathEnv(immutable.Seq((nodeModules in Plugin).value.getCanonicalPath)))
      case EngineType.Node => Node.props(stdEnvironment = NodeEngine.nodePathEnv(immutable.Seq((nodeModules in Plugin).value.getCanonicalPath)))
      case EngineType.PhantomJs => PhantomJs.props()
      case EngineType.Rhino => Rhino.props()
      case EngineType.Trireme => Trireme.props(stdEnvironment = NodeEngine.nodePathEnv(immutable.Seq((nodeModules in Plugin).value.getCanonicalPath)))
    }

    val sources = ((unmanagedSources in config).value ** fileFilter.value).get

    implicit val opInputHasher = fileInputHasher.value
    val problems: Seq[Problem] = incremental.runIncremental(streams.value.cacheDirectory, sources) {
      modifiedJsSources: Seq[File] =>

        if (modifiedJsSources.size > 0) {

          streams.value.log.info(s"$opDesc on ${
            modifiedJsSources.size
          } source(s)")

          val resultBatches: Seq[Future[(Map[java.io.File, OpResult], Seq[Problem])]] =
            try {
              val sourceBatches = (modifiedJsSources grouped Math.max(modifiedJsSources.size / parallelism.value, 1)).toSeq
              sourceBatches.map {
                sourceBatch =>
                  implicit val timeout = Timeout(timeoutPerSource * sourceBatch.size)
                  withActorRefFactory(state.value, this.getClass.getName) {
                    arf =>
                      val engine = arf.actorOf(engineProps)
                      implicit val timeout = Timeout(timeoutPerSource * sourceBatch.size)
                      executeJs(engine, shellSource.value, sourceBatch, jsOptions.value)
                  }
              }
            }

          import scala.concurrent.ExecutionContext.Implicits.global
          val pendingResults = Future.sequence(resultBatches)
          val completedResults = Await.result(pendingResults, timeoutPerSource * modifiedJsSources.size)
          completedResults.foldLeft(Map[File, OpResult]() -> Seq[Problem]()) {
            (r1, r2) =>
              (r1._1 ++ r2._1) -> (r1._2 ++ r2._2)
          }

        } else {
          (Map.empty, Seq.empty)
        }
    }

    CompileProblems.report(reporter.value, problems)

    if (problems.isEmpty) 0 else 1
  }
}