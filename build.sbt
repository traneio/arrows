import Dependencies._

lazy val root = (project in file(".")).
  settings(
    inThisBuild(List(
      organization := "com.twitter.arrows",
      scalaVersion := "2.12.4",
      version      := "0.1.0-SNAPSHOT"
    )),
    name := "arrows",
    libraryDependencies ++= Seq(
      scalaTest % Test,
      "com.twitter" %% "util-core" % "17.11.0"
    )
  )
