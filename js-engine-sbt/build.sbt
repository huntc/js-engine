sbtPlugin := true

organization := "com.typesafe"

name := "js-engine-sbt"

version := "1.0.0-SNAPSHOT"

libraryDependencies ++= Seq(
  "com.typesafe" %% "jse" % "1.0.0-SNAPSHOT"
)

addSbtPlugin("com.typesafe" %% "web-sbt" % "1.0.0-SNAPSHOT")
