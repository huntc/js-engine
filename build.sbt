organization := "com.typesafe"

name := "jse"

version := "1.0.0-SNAPSHOT"

scalaVersion := "2.10.3"

resolvers += Resolver.sonatypeRepo("snapshots")

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor" % "2.2.3",
  "com.typesafe.akka" %% "akka-contrib" % "2.2.3",
  "org.mozilla" % "rhino" % "1.7R5-SNAPSHOT",
  "org.ringojs" % "ringojs" % "0.10.1-SNAPSHOT",
  "io.spray" %% "spray-json" % "1.2.5",
  "jline" % "jline" % "1.0",
  "org.specs2" %% "specs2" % "2.2.2" % "test",
  "junit" % "junit" % "4.11" % "test",
  "com.typesafe.akka" %% "akka-testkit" % "2.2.3" % "test"
)

publishTo := {
  val isSnapshot = version.value.contains("-SNAPSHOT")
  val typesafe = "http://private-repo.typesafe.com/typesafe/"
  val (name, url) = if (isSnapshot)
    ("sbt-plugin-snapshots", typesafe + "maven-snapshots")
  else
    ("sbt-plugin-releases", typesafe + "maven-releases")
  Some(Resolver.url(name, new URL(url)))
}

lazy val root = project.in(file("."))

lazy val `sbt-js-engine` = project.dependsOn(root)

lazy val `js-engine-tester` = project.dependsOn(root)

