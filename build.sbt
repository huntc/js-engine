organization := "com.typesafe"

name := "jse"

version := "1.0.0-SNAPSHOT"

scalaVersion := "2.10.3"

resolvers ++= Seq(
  "spray nightlies repo" at "http://nightlies.spray.io",
  "spray repo" at "http://repo.spray.io/"
  )

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor" % "2.2.3",
  "com.typesafe.akka" %% "akka-contrib" % "2.2.3",
  "org.mozilla" % "rhino" % "1.7R4",
  "io.spray" %% "spray-json" % "1.2.5",
  "org.specs2" %% "specs2" % "2.2.2" % "test",
  "junit" % "junit" % "4.11" % "test",
  "com.typesafe.akka" %% "akka-testkit" % "2.2.3" % "test"
)

lazy val root = project.in( file(".") )

lazy val `sbt-js-engine` = project.dependsOn(root)

lazy val `js-engine-tester` = project.dependsOn(root)
