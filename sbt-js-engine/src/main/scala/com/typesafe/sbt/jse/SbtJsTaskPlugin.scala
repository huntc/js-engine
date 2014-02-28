package com.typesafe.sbt.jse

import sbt._
import sbt.Keys._
import com.typesafe.sbt.web.incremental.OpInputHasher
import spray.json._
import com.typesafe.sbt.web._
import xsbti.{Problem, Severity}
import com.typesafe.sbt.web.incremental.OpResult
import com.typesafe.sbt.web.incremental.OpFailure
import com.typesafe.sbt.jse.SbtJsEnginePlugin.JsEngineKeys
import com.typesafe.sbt.web.incremental.OpInputHash
import akka.actor.ActorRef
import akka.util.Timeout
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.collection.immutable
import com.typesafe.jse._
import com.typesafe.jse.Node
import com.typesafe.sbt.web.SbtWebPlugin._
import akka.pattern.ask
import scala.concurrent.duration.FiniteDuration
import com.typesafe.sbt.web.incremental
import sbt.Task
import com.typesafe.jse.Engine.JsExecutionResult
import com.typesafe.sbt.web.incremental.OpSuccess
import sbt.Configuration
import sbinary.{Input, Output, Format}


/**
 * The commonality of JS task execution oriented plugins is captured by this class.
 */
object SbtJsTaskPlugin {

  object JsTaskKeys {

    val fileFilter = SettingKey[FileFilter]("jstask-file-filter", "The file extension of files to perform a task on.")
    val fileInputHasher = TaskKey[OpInputHasher[PathMapping]]("jstask-file-input-hasher", "A function that constitues a change for a given file.")
    val jsOptions = TaskKey[String]("jstask-js-options", "The JSON options to be passed to the task.")
    val taskMessage = SettingKey[String]("jstask-message", "The message to output for a task")
    val shellFile = SettingKey[String]("jstask-shell-file", "The name of the file to perform a given task.")
    val shellSource = TaskKey[File]("jstask-shell-source", "The target location of the js shell script to use.")
    val timeoutPerSource = SettingKey[FiniteDuration]("jstask-timeout-per-source", "The maximum number of seconds to wait per source file processed by the JS task.")
  }

  import JsTaskKeys._

  import scala.concurrent.duration._

  val jsTaskSettings = Seq(
    timeoutPerSource := 30.seconds
  )

  val jsEngineAndTaskSettings = SbtJsEnginePlugin.jsEngineSettings ++ jsTaskSettings

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
        case x => deserializationError(s"String expected for a file, instead got $x")
      }
    }

    implicit object PathMappingFormat extends JsonFormat[PathMapping] {
      def write(p: PathMapping) = JsArray(JsString(p._1.getCanonicalPath), JsString(p._2))

      def read(value: JsValue) = value match {
        case a: JsArray => FileFormat.read(a.elements(0)) -> a.elements(1).convertTo[String]
        case x => deserializationError(s"Array expected for a path mapping, instead got $x")
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
        case x => deserializationError(s"Object expected for the problem, instead got $x")
      }

    }

    implicit object OpResultFormat extends JsonFormat[OpResult] {

      def write(r: OpResult) = JsString("unimplemented")

      def read(value: JsValue) = value match {
        case o: JsObject => opSuccessFormat.read(o)
        case JsNull => OpFailure
        case x => deserializationError(s"Object expected for the op result, instead got $x")
      }
    }

    case class ProblemResultsPair(results: Seq[SourceResultPair], problems: Seq[LineBasedProblem])

    case class SourceResultPair(result: OpResult, source: PathMapping)

    implicit val sourceResultPairFormat = jsonFormat2(SourceResultPair)
    implicit val problemResultPairFormat = jsonFormat2(ProblemResultsPair)
  }

}

abstract class SbtJsTaskPlugin extends sbt.Plugin {

  import SbtJsTaskPlugin._

  import WebKeys._
  import JsEngineKeys._
  import SbtJsTaskPlugin.JsTaskKeys._

  val jsTaskSpecificUnscopedConfigSettings = Seq(
    fileInputHasher := OpInputHasher[PathMapping](p => OpInputHash.hashString(p._1.getAbsolutePath + "|" + jsOptions.value)),
    jsOptions := "{}",
    resourceManaged := target.value / moduleName.value
  )

  val jsTaskSpecificUnscopedSettings =
    inConfig(Assets)(jsTaskSpecificUnscopedConfigSettings) ++
      inConfig(TestAssets)(jsTaskSpecificUnscopedConfigSettings) ++
      Seq(
        shellSource := {
          SbtWebPlugin.copyResourceTo(
            (target in Plugin).value / moduleName.value,
            shellFile.value,
            SbtJsTaskPlugin.getClass.getClassLoader
          )
        }
      )


  private type FileOpResultMappings = Map[PathMapping, OpResult]

  private def FileOpResultMappings(s: (PathMapping, OpResult)*): FileOpResultMappings = Map(s: _*)

  private type PathMappingsAndProblems = (Seq[PathMapping], Seq[Problem])

  private def PathMappingsAndProblems(): PathMappingsAndProblems = PathMappingsAndProblems(Nil, Nil)

  private def PathMappingsAndProblems(pathMappingsAndProblems: (Seq[PathMapping], Seq[Problem])): PathMappingsAndProblems = pathMappingsAndProblems


  private def executeSourceFilesJs(
                                    engine: ActorRef,
                                    shellSource: File,
                                    sourceFileMappings: Seq[PathMapping],
                                    target: File,
                                    options: String
                                    )(implicit timeout: Timeout): Future[(FileOpResultMappings, Seq[Problem])] = {

    import ExecutionContext.Implicits.global

    val args = immutable.Seq(
      JsArray(sourceFileMappings.map(x => JsArray(JsString(x._1.getCanonicalPath), JsString(x._2))).toList).toString(),
      target.getAbsolutePath,
      options
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

        val jsonBytes = result.output.dropWhile(_ != '\u0010').drop(1)
        val json = new String(jsonBytes.toArray, "UTF-8")
        val p = JsonParser(json)
        import JsTaskProtocol._
        val prp = p.convertTo[ProblemResultsPair]
        (prp.results.map(sr => sr.source -> sr.result).toMap, prp.problems)
    }
  }

  /*
   * For reading/writing binary representations of files.
   */
  private implicit object FileFormat extends Format[File] {

    import Cache._

    def reads(in: Input): File = file(StringFormat.reads(in))

    def writes(out: Output, fh: File) = StringFormat.writes(out, fh.getAbsolutePath)
  }

  /**
   * Primary means of executing a JavaScript shell script for processing source files. unmanagedResources is assumed
   * to contain the source files to filter on.
   * @param task The task to resolve js task settings from - relates to the concrete plugin sub class
   * @param config The sbt configuration to use e.g. Assets or TestAssets
   * @return A task object
   */
  def jsSourceFileTask(
                        task: TaskKey[Seq[PathMapping]],
                        config: Configuration
                        ): Def.Initialize[Task[Seq[PathMapping]]] = Def.task {

    val engineProps = engineType.value match {
      case EngineType.CommonNode => CommonNode.props(stdEnvironment = NodeEngine.nodePathEnv(immutable.Seq((nodeModules in Plugin).value.getCanonicalPath)))
      case EngineType.Node => Node.props(stdEnvironment = NodeEngine.nodePathEnv(immutable.Seq((nodeModules in Plugin).value.getCanonicalPath)))
      case EngineType.PhantomJs => PhantomJs.props()
      case EngineType.Rhino => Rhino.props()
      case EngineType.Trireme => Trireme.props(stdEnvironment = NodeEngine.nodePathEnv(immutable.Seq((nodeModules in Plugin).value.getCanonicalPath)))
    }

    val sources = ((unmanagedSources in config).value ** (fileFilter in task in config).value)
      .get.pair(relativeTo((unmanagedSources in config).value))

    implicit val opInputHasher = (fileInputHasher in task in config).value
    val results: PathMappingsAndProblems = incremental.runIncremental(streams.value.cacheDirectory, sources) {
      modifiedJsSources: Seq[PathMapping] =>

        if (modifiedJsSources.size > 0) {

          streams.value.log.info(s"${(taskMessage in task in config).value} on ${
            modifiedJsSources.size
          } source(s)")

          val resultBatches: Seq[Future[(FileOpResultMappings, Seq[Problem])]] =
            try {
              val sourceBatches = (modifiedJsSources grouped Math.max(modifiedJsSources.size / parallelism.value, 1)).toSeq
              sourceBatches.map {
                sourceBatch =>
                  implicit val timeout = Timeout((timeoutPerSource in task in config).value * sourceBatch.size)
                  withActorRefFactory(state.value, this.getClass.getName) {
                    arf =>
                      val engine = arf.actorOf(engineProps)
                      implicit val timeout = Timeout((timeoutPerSource in task in config).value * sourceBatch.size)
                      executeSourceFilesJs(
                        engine,
                        (shellSource in task in config).value,
                        sourceBatch,
                        (resourceManaged in task in config).value,
                        (jsOptions in task in config).value
                      )
                  }
              }
            }

          import scala.concurrent.ExecutionContext.Implicits.global
          val pendingResults = Future.sequence(resultBatches)
          val completedResults = Await.result(pendingResults, (timeoutPerSource in task in config).value * modifiedJsSources.size)

          completedResults.foldLeft((FileOpResultMappings(), PathMappingsAndProblems())) {
            (allCompletedResults, completedResult) =>

              val (prevOpResults, (prevPathMappings, prevProblems)) = allCompletedResults

              val (nextOpResults, nextProblems) = completedResult
              val nextPathMappings: Seq[PathMapping] = nextOpResults.values.map {
                case opSuccess: OpSuccess =>
                  opSuccess.filesRead.pair(relativeTo((unmanagedSources in config).value)) ++
                    opSuccess.filesWritten.pair(relativeTo((target in task in config).value), errorIfNone = false)
                case _ => Nil
              }.flatten.toSeq

              (
                prevOpResults ++ nextOpResults,
                PathMappingsAndProblems(prevPathMappings ++ nextPathMappings, prevProblems ++ nextProblems)
                )
          }

        } else {
          (FileOpResultMappings(), PathMappingsAndProblems())
        }
    }

    CompileProblems.report(reporter.value, results._2)

    import Cache._
    val previousMappings = task.previous.getOrElse(Nil)
    val untouchedMappings = previousMappings.toSet -- results._1
    untouchedMappings.filter(_._1.exists).toSeq ++ results._1
  }

  /**
   * Convenience method to add a source file task into the Asset and TestAsset configurations, along with adding the
   * source file tasks in to their respective collection.
   * @param sourceFileTask The task key to declare.
   * @return The settings produced.
   */
  def addJsSourceFileTasks(sourceFileTask: TaskKey[Seq[PathMapping]]): Seq[Setting[_]] = {
    Seq(
      sourceFileTask in Assets := jsSourceFileTask(sourceFileTask, Assets).value,
      sourceFileTask in TestAssets := jsSourceFileTask(sourceFileTask, TestAssets).value,
      sourceFileTask := (sourceFileTask in Assets).value,

      assetTasks in Assets <+= (sourceFileTask in Assets),
      assetTasks in TestAssets <+= (sourceFileTask in TestAssets)
    )
  }
}