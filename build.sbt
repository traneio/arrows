import ReleaseTransformations._
import com.typesafe.sbt.SbtScalariform.ScalariformKeys
import scalariform.formatter.preferences._
import sbtrelease.ReleasePlugin
import pl.project13.scala.sbt.JmhPlugin

lazy val superPure = new org.scalajs.sbtplugin.cross.CrossType {
  def projectDir(crossBase: File, projectType: String): File =
    projectType match {
      case "jvm" => crossBase
      case "js"  => crossBase / s".$projectType"
    }

  def sharedSrcDir(projectBase: File, conf: String): Option[File] =
    Some(projectBase.getParentFile / "src" / conf / "scala")
}

lazy val `arrows` =
  (project in file("."))
    .settings(commonSettings)
    .aggregate(
      `arrows-stdlib-jvm`, 
      `arrows-stdlib-js`,
      `arrows-twitter`,
      `arrows-benchmark`
    )
    .dependsOn(
      `arrows-stdlib-jvm`, 
      `arrows-stdlib-js`,
      `arrows-twitter`,
      `arrows-benchmark`
    )

lazy val `arrows-stdlib` = 
  crossProject.crossType(superPure)
    .settings(commonSettings)
    .settings(
      name := "arrows-stdlib",
      libraryDependencies += "org.scalatest" %%% "scalatest" % "3.0.1" % "test",
      scoverage.ScoverageKeys.coverageMinimum := 96,
      scoverage.ScoverageKeys.coverageFailOnMinimum := false)
    .jsSettings(
      coverageExcludedPackages := ".*"
    )

lazy val `arrows-stdlib-jvm` = `arrows-stdlib`.jvm
lazy val `arrows-stdlib-js` = `arrows-stdlib`.js

lazy val `arrows-twitter` = project
  .settings(commonSettings)
  .settings(crossScalaVersions := Seq("2.11.12", "2.12.5"))
  .settings(
    libraryDependencies ++= Seq(
      "com.twitter" %% "util-core" % "18.3.0"
    )
  )

lazy val scalaz8Effect = 
  ProjectRef(uri("https://github.com/scalaz/scalaz.git#fcd2d2b320770e2a74e1fb16499f9ab6a402d608"), "effectJVM")

lazy val `arrows-benchmark` = project
  .settings(commonSettings)
  .settings(
    resolvers += Resolver.sonatypeRepo("snapshots"),
    libraryDependencies ++= Seq(
      "io.monix" %% "monix" % "3.0.0-RC1",
      "io.trane" % "future-java" % "0.2.2",
      "org.typelevel" %% "cats-effect" % "0.10"
    )
  )
  .dependsOn(`arrows-stdlib-jvm`, `arrows-twitter`, scalaz8Effect)
  .enablePlugins(JmhPlugin)

def updateReadmeVersion(selectVersion: sbtrelease.Versions => String) =
  ReleaseStep(action = st => {

    val newVersion = selectVersion(st.get(ReleaseKeys.versions).get)

    import scala.io.Source
    import java.io.PrintWriter

    val pattern = """"com.github.fwbrasil.arrows" %% "arrows-.*" % "(.*)"""".r

    val fileName = "README.md"
    val content = Source.fromFile(fileName).getLines.mkString("\n")

    val newContent =
      pattern.replaceAllIn(content,
        m => m.matched.replaceAllLiterally(m.subgroups.head, newVersion))

    new PrintWriter(fileName) { write(newContent); close }

    val vcs = Project.extract(st).get(releaseVcs).get
    vcs.add(fileName).!

    st
  })

def updateWebsiteTag =
  ReleaseStep(action = st => {

    val vcs = Project.extract(st).get(releaseVcs).get
    vcs.tag("website", "update website", false).!

    st
  })

lazy val commonSettings = Seq(
  scalaVersion := "2.12.5",
  // crossScalaVersions := Seq("2.11.12", "2.12.4"),
  organization := "com.github.fwbrasil.arrows",
  EclipseKeys.eclipseOutput := Some("bin"),
  scalacOptions ++= Seq(
    // "-Xfatal-warnings",
    "-deprecation",
    "-encoding", "UTF-8",
    "-feature",
    "-unchecked",
    "-Xlint",
    "-Yno-adapted-args",
    "-Ywarn-numeric-widen",
    "-Xfuture",
    "-Ywarn-unused-import"
    ),
  libraryDependencies ++= Seq(
      "org.scalatest" %% "scalatest" % "3.0.4" % Test
      // ,
      // "junit" % "junit" % "4.12" % Test
    ),
  ScalariformKeys.preferences := ScalariformKeys.preferences.value
    .setPreference(AlignParameters, true)
    .setPreference(CompactStringConcatenation, false)
    .setPreference(IndentPackageBlocks, true)
    .setPreference(FormatXml, true)
    .setPreference(PreserveSpaceBeforeArguments, false)
    .setPreference(DoubleIndentConstructorArguments, false)
    .setPreference(RewriteArrowSymbols, false)
    .setPreference(AlignSingleLineCaseStatements, true)
    .setPreference(AlignSingleLineCaseStatements.MaxArrowIndent, 40)
    .setPreference(SpaceBeforeColon, false)
    .setPreference(SpaceInsideBrackets, false)
    .setPreference(SpaceInsideParentheses, false)
    .setPreference(DanglingCloseParenthesis, Force)
    .setPreference(IndentSpaces, 2)
    .setPreference(IndentLocalDefs, false)
    .setPreference(SpacesWithinPatternBinders, true)
    .setPreference(SpacesAroundMultiImports, true),
  releasePublishArtifactsAction := PgpKeys.publishSigned.value,
  releaseIgnoreUntrackedFiles := true,
  publishMavenStyle := true,
  publishTo := {
    val nexus = "https://oss.sonatype.org/"
    if (isSnapshot.value)
      Some("snapshots" at nexus + "content/repositories/snapshots")
    else
      Some("releases"  at nexus + "service/local/staging/deploy/maven2")
  },
  pgpSecretRing := file("local.secring.gpg"),
  pgpPublicRing := file("local.pubring.gpg"),
  releasePublishArtifactsAction := PgpKeys.publishSigned.value,
  releaseProcess := Seq[ReleaseStep](
    checkSnapshotDependencies,
    inquireVersions,
    runClean,
    runTest,
    setReleaseVersion,
    updateReadmeVersion(_._1),
    commitReleaseVersion,
    updateWebsiteTag,
    tagRelease,
    publishArtifacts,
    setNextVersion,
    updateReadmeVersion(_._2),
    commitNextVersion,
    releaseStepCommand("sonatypeReleaseAll"),
    pushChanges
),
  pomExtra := (
    <url>http://github.com/fwbrasil/arrows</url>
    <licenses>
      <license>
        <name>Apache License 2.0</name>
        <url>https://raw.githubusercontent.com/fwbrasil/arrows/master/LICENSE.txt</url>
        <distribution>repo</distribution>
      </license>
    </licenses>
    <scm>
      <url>git@github.com:fwbrasil/arrows.git</url>
      <connection>scm:git:git@github.com:fwbrasil/arrows.git</connection>
    </scm>
    <developers>
      <developer>
        <id>fwbrasil</id>
        <name>Flavio W. Brasil</name>
        <url>http://github.com/fwbrasil/</url>
      </developer>
    </developers>)
)

lazy val `tut-sources` = Seq(
  "README.md"
)

lazy val `tut-settings` = Seq(
  scalacOptions in Tut := Seq(),
  tutSourceDirectory := baseDirectory.value / "target" / "tut",
  tutNameFilter := `tut-sources`.map(_.replaceAll("""\.""", """\.""")).mkString("(", "|", ")").r,
  sourceGenerators in Compile +=
    Def.task {
      `tut-sources`.foreach { name =>
        val source = baseDirectory.value / name
        val file = baseDirectory.value / "target" / "tut" / name
        val str = IO.read(source).replace("```scala", "```tut")
        IO.write(file, str)
      }
      Seq()
    }.taskValue
)

commands += Command.command("checkUnformattedFiles") { st =>
  val vcs = Project.extract(st).get(releaseVcs).get
  val modified = vcs.cmd("ls-files", "--modified", "--exclude-standard").!!.trim
  if(modified.nonEmpty)
    throw new IllegalStateException(s"Please run `sbt scalariformFormat test:scalariformFormat` and resubmit your pull request. Found unformatted files: \n$modified")
  st
}
