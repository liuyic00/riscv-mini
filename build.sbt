ThisBuild / scalaVersion     := "2.13.15"
ThisBuild / version          := "2.5.0"
ThisBuild / organization     := "edu.berkeley.cs"

val chiselVersion = "7.0.0-M2+150-a1d471a3-SNAPSHOT"

lazy val root = (project in file("."))
  .settings(
    name := "riscv-mini",
    libraryDependencies ++= Seq(
      "org.chipsalliance" %% "chisel" % chiselVersion,
      "edu.berkeley.cs" %% "chiseltest" % "6.0.0" % "test",
      "cn.ac.ios.tis" %% "riscvspeccore" % "1.3_noQandTLB-chisel7.0.0-m2+150-a1d471a3-snapshot-7745210-SNAPSHOT"
    ),
    scalacOptions ++= Seq(
      "-language:reflectiveCalls",
      "-deprecation",
      "-feature",
      "-Xcheckinit",
    ),
    addCompilerPlugin("org.chipsalliance" % "chisel-plugin" % chiselVersion cross CrossVersion.full),
  )
