ThisBuild / scalaVersion := "3.3.9"
ThisBuild / organization := "io.github.gtcodingtest"
ThisBuild / version      := "0.1.0-SNAPSHOT"

lazy val root = (project in file("."))
  .settings(
    name := "sysctl-conf",
    scalacOptions ++= Seq(
      "-deprecation",
      "-feature",
      "-unchecked",
      "-Wunused:all",
      "-Wvalue-discard",
      "-Werror"
    ),
    libraryDependencies += "org.scalameta" %% "munit" % "1.3.6" % Test
  )

addCommandAlias("check", "scalafmtSbtCheck; scalafmtCheckAll; test")
