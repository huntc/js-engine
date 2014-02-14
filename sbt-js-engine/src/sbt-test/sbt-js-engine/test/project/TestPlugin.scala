import sbt._
import com.typesafe.sbt.web.SbtWebPlugin._
import com.typesafe.sbt.web.incremental._
import sbt.File
import com.typesafe.sbt.jse.SbtJsTaskPlugin

/**
 * The sbt plugin plumbing around the JSHint library.
 */
object TestPlugin extends SbtJsTaskPlugin {

  // Declare the sbt settings associated with the plugin.

  object TestKeys {
    val someCompile = TaskKey[Int]("some-compile", "Perform our JavaScript.")
    val someCompileOptions = TaskKey[String]("some-compile-options", "A Json string of options to pass to our JS. Can be anything we want.")
  }

  // Bind the settings to values and tasks. jsTaskSettings should always form the basis of these settings given
  // that's what our plugin inherits from.

  import WebKeys._
  import SbtJsTaskPlugin.JsTaskKeys._
  import TestKeys._

  def testSettings = jsTaskSettings ++ Seq(

    someCompileOptions := """["-a", "-b", "-whatever"]""",

    // An input hasher declares what constitutes change for a given source file. The following
    // declares whether the source file itself changes or whether the plugins options change.

    fileInputHasher := OpInputHasher[File](source => OpInputHash.hashString(source + "|" + someCompileOptions.value)),

    // Wire up the tasks. We're specifying a js execution to occur for both compile and test scopes.

    someCompile in Assets := jsTask(Assets, jsFilter in Assets, someCompileOptions, fileInputHasher, "JavaScript pretend compiling").value,
    someCompile in TestAssets := jsTask(TestAssets, jsFilter in TestAssets, someCompileOptions, fileInputHasher, "JavaScript test pretend compiling").value,

    // Provide a nice default for when "some-compile" is typed in the sbt console.

    someCompile := (someCompile in Assets).value,

    // Add our plugin's js tasks to the regular compilation tasks in sbt

    jsTasks in Assets <+= (someCompile in Assets),
    jsTasks in TestAssets <+= (someCompile in TestAssets)
  )

}
