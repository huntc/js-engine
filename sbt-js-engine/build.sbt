sbtPlugin := true

organization := "com.typesafe"

name := "sbt-js-engine"

version := "1.0.0-SNAPSHOT"

scalaVersion := "2.10.3"

libraryDependencies ++= Seq(
  "com.typesafe" %% "jse" % "1.0.0-SNAPSHOT"
)

addSbtPlugin("com.typesafe" % "sbt-web" % "1.0.0-SNAPSHOT")
