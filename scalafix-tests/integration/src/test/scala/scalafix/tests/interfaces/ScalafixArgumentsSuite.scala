package scalafix.tests.interfaces

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Collections
import java.util.Optional

import scala.collection.JavaConverters._
import scala.util.Try

import scala.meta.internal.io.FileIO
import scala.meta.io.AbsolutePath
import scala.meta.io.Classpath

import buildinfo.RulesBuildInfo
import coursier._
import org.scalatest.funsuite.AnyFunSuite
import scalafix.interfaces.ScalafixArguments
import scalafix.interfaces.ScalafixDiagnostic
import scalafix.interfaces.ScalafixException
import scalafix.interfaces.ScalafixFileEvaluationError
import scalafix.interfaces.ScalafixMainCallback
import scalafix.interfaces.ScalafixMainMode
import scalafix.interfaces.ScalafixPatch
import scalafix.internal.interfaces.ScalafixArgumentsImpl
import scalafix.internal.reflect.ClasspathOps
import scalafix.internal.rule.RemoveUnused
import scalafix.internal.rule.RemoveUnusedConfig
import scalafix.internal.tests.utils.SkipWindows
import scalafix.test.StringFS
import scalafix.testkit.DiffAssertions
import scalafix.tests.BuildInfo
import scalafix.tests.core.Classpaths
import scalafix.tests.util.ScalaVersions
import scalafix.tests.util.compat.CompatSemanticdb
import scalafix.v1.SemanticRule

class ScalafixArgumentsSuite extends AnyFunSuite with DiffAssertions {
  private val scalaVersion = BuildInfo.scalaVersion
  private val removeUnused: String =
    if (ScalaVersions.isScala213)
      "-Wunused:imports"
    else "-Ywarn-unused-import"
  val api: ScalafixArguments =
    ScalafixArgumentsImpl()
      .withScalaVersion(scalaVersion)

  val charset = StandardCharsets.US_ASCII
  val cwd: Path = StringFS
    .string2dir(
      """|/src/Main.scala
        |import scala.concurrent.duration
        |import scala.concurrent.Future
        |
        |object Main extends App {
        |  import scala.concurrent.Await
        |  println("test");
        |  println("ok")
        |}
      """.stripMargin,
      charset
    )
    .toNIO
  val d: Path = cwd.resolve("out")
  val target: Path = cwd.resolve("target")
  val src: Path = cwd.resolve("src")
  Files.createDirectories(d)
  val main: Path = src.resolve("Main.scala")
  val relativePath: Path = cwd.relativize(main)

  val specificScalacOption2: Seq[String] =
    if (!ScalaVersions.isScala3)
      Seq(removeUnused)
    else Nil

  val scalacOptions: Array[String] = Array[String](
    "-classpath",
    s"${scalaLibrary.mkString(":")}",
    "-d",
    d.toString,
    main.toString
  ) ++ specificScalacOption2 ++ CompatSemanticdb.scalacOptions(src, target)

  test("availableRules") {
    val rules = api.availableRules().asScala
    val names = rules.map(_.name())
    assert(names.contains("DisableSyntax"))
    assert(names.contains("AvailableRule"))
    assert(!names.contains("DeprecatedAvailableRule"))
    val hasDescription = rules.filter(_.description().nonEmpty)
    assert(hasDescription.nonEmpty)
    val isSyntactic = rules.filter(_.kind().isSyntactic)
    assert(isSyntactic.nonEmpty)
    val isSemantic = rules.filter(_.kind().isSemantic)
    assert(isSemantic.nonEmpty)
    val isLinter = rules.filter(_.isLinter)
    assert(isLinter.nonEmpty)
    val isRewrite = rules.filter(_.isRewrite)
    assert(isRewrite.nonEmpty)
    val isExperimental = rules.filter(_.isExperimental)
    assert(isExperimental.isEmpty)
  }

  test("validate") {
    val args = api.withRules(List("RemoveUnused").asJava)
    val e = args.validate()
    assert(e.isPresent)
    assert(e.get().getMessage.contains("-Ywarn-unused"))
  }

  test("rulesThatWillRun") {

    val charset = StandardCharsets.US_ASCII
    val cwd = StringFS
      .string2dir(
        """|/src/Semicolon.scala
          |
          |object Semicolon {
          |  def main { println(42) }
          |}
          |/.scalafix.conf
          |rules = ["DisableSyntax"]
      """.stripMargin,
        charset
      )
    val args = api
      .withConfig(Optional.empty())
      .withWorkingDirectory(cwd.toNIO)
    args.validate()
    assert(
      args.rulesThatWillRun().asScala.toList.map(_.toString) == List(
        "ScalafixRule(DisableSyntax)"
      )
    )

    // if a non empty list of rules is provided, rules from config file are ignored
    val args2 = api
      .withRules(List("RedundantSyntax").asJava)
      .withConfig(Optional.empty())
      .withWorkingDirectory(cwd.toNIO)
    args2.validate()
    assert(
      args2.rulesThatWillRun().asScala.toList.map(_.name()) == List(
        "RedundantSyntax"
      )
    )

  }

  test("runMain") {
    // Todo(i1680): this is an integration test that uses many non supported rules in scala 3.
    // Add a more simple test for scala 3. For now we ignore for Scala 3.
    if (ScalaVersions.isScala3) cancel()

    // Assert that non-ascii characters read into "?"
    val charset = StandardCharsets.US_ASCII
    val cwd = StringFS
      .string2dir(
        """|/src/Semicolon.scala
          |
          |object Semicolon {
          |  val a = 1; // みりん þæö
          |  implicit val b = List(1)
          |  def main { println(42) }
          |}
          |
          |/src/Excluded.scala
          |object Excluded {
          |  val a = 1;
          |}
      """.stripMargin,
        charset
      )
      .toNIO
    val d = cwd.resolve("out")
    val src = cwd.resolve("src")
    Files.createDirectories(d)
    val semicolon = src.resolve("Semicolon.scala")
    val excluded = src.resolve("Excluded.scala")
    val scalaBinaryVersion =
      RulesBuildInfo.scalaVersion.split('.').take(2).mkString(".")
    // This rule is published to Maven Central to simplify testing --tool-classpath.
    val dep =
      Dependency(
        Module(
          Organization("ch.epfl.scala"),
          ModuleName(s"example-scalafix-rule_$scalaBinaryVersion")
        ),
        "1.6.0"
      )
    val toolClasspathJars = Fetch()
      .addDependencies(dep)
      .run()
      .toList
    val toolClasspath = ClasspathOps.toClassLoader(
      Classpath(toolClasspathJars.map(jar => AbsolutePath(jar)))
    )
    val scalacOptions = Array[String](
      "-Yrangepos",
      "-classpath",
      s"${scalaLibrary.mkString(":")}",
      "-d",
      d.toString,
      semicolon.toString,
      excluded.toString
    ) ++ CompatSemanticdb.scalacOptions(src)
    scala.tools.nsc.Main.process(scalacOptions)
    val buf = List.newBuilder[ScalafixDiagnostic]
    val callback = new ScalafixMainCallback {
      override def reportDiagnostic(diagnostic: ScalafixDiagnostic): Unit = {
        buf += diagnostic
      }
    }
    val out = new ByteArrayOutputStream()
    val relativePath = cwd.relativize(semicolon)
    val warnRemoveUnused =
      if (ScalaVersions.isScala213)
        "-Wunused:imports"
      else "-Ywarn-unused-import"
    val args = api
      .withParsedArguments(
        List("--settings.DisableSyntax.noSemicolons", "true").asJava
      )
      .withCharset(charset)
      .withClasspath((d +: scalaLibrary.map(_.toNIO)).asJava)
      .withSourceroot(src)
      .withWorkingDirectory(cwd)
      .withPaths(List(relativePath.getParent).asJava)
      .withExcludedPaths(
        List(
          FileSystems.getDefault.getPathMatcher("glob:**Excluded.scala")
        ).asJava
      )
      .withMainCallback(callback)
      .withRules(
        List(
          "DisableSyntax", // syntactic linter
          "ProcedureSyntax", // syntactic rewrite
          "ExplicitResultTypes", // semantic rewrite
          "class:fix.Examplescalafixrule_v1" // --tool-classpath
        ).asJava
      )
      .withPrintStream(new PrintStream(out))
      .withMode(ScalafixMainMode.CHECK)
      .withToolClasspath(toolClasspath)
      .withScalacOptions(Collections.singletonList(warnRemoveUnused))
      .withScalaVersion(scalaVersion)
      .withConfig(Optional.empty())
    val expectedRulesToRun = List(
      "ProcedureSyntax",
      "ExplicitResultTypes",
      "ExampleScalafixRule_v1",
      "DisableSyntax"
    )
    val obtainedRulesToRun =
      args.rulesThatWillRun().asScala.toList.map(_.name())
    assertNoDiff(
      obtainedRulesToRun.sorted.mkString("\n"),
      expectedRulesToRun.sorted.mkString("\n")
    )
    val validateError: Optional[ScalafixException] = args.validate()
    assert(!validateError.isPresent, validateError)
    val scalafixErrors = args.run()
    val errors = scalafixErrors.toList.map(_.toString).sorted
    val stdout = fansi
      .Str(out.toString(charset.name()))
      .plainText
      .replaceAllLiterally(semicolon.toString, relativePath.toString)
      .replace('\\', '/') // for windows
      .linesIterator
      .filterNot(_.trim.isEmpty)
      .mkString("\n")
    assert(errors == List("LinterError", "TestError"), stdout)
    val linterDiagnostics = buf
      .result()
      .map { d =>
        d.position()
          .get()
          .formatMessage(d.severity().toString, d.message())
      }
      .mkString("\n\n")
      .replaceAllLiterally(semicolon.toString, relativePath.toString)
      .replace('\\', '/') // for windows
    assertNoDiff(
      linterDiagnostics,
      """|src/Semicolon.scala:3:12: ERROR: semicolons are disabled
        |  val a = 1; // ??? ???
        |           ^
      """.stripMargin
    )
    assertNoDiff(
      stdout,
      """|--- src/Semicolon.scala
        |+++ <expected fix>
        |@@ -1,6 +1,7 @@
        | object Semicolon {
        |   val a = 1; // ??? ???
        |-  implicit val b = List(1)
        |-  def main { println(42) }
        |+  implicit val b: List[Int] = List(1)
        |+  def main: Unit = { println(42) }
        | }
        |+// Hello world!
        |""".stripMargin
    )
  }

  test("ScalafixArguments.evaluate with a semantic rule", SkipWindows) {
    // Todo(i1680): this is an integration test that uses many non supported rules in scala 3.
    // Add a more simple test for scala 3. For now we ignore for Scala 3.
    if (ScalaVersions.isScala3) cancel()

    val _ = CompatSemanticdb.runScalac(scalacOptions)
    val result = api
      .withRules(
        List(
          removeUnusedRule().name.toString(),
          "ExplicitResultTypes",
          "DisableSyntax"
        ).asJava
      )
      .withParsedArguments(
        List("--settings.DisableSyntax.noSemicolons", "true").asJava
      )
      .withClasspath((scalaLibrary.map(_.toNIO) :+ target).asJava)
      .withScalacOptions(Collections.singletonList(removeUnused))
      .withPaths(Seq(main).asJava)
      .withSourceroot(src)
      .evaluate()

    val error = result.getError
    assert(!error.isPresent) // we ignore completely linterErrors
    assert(result.isSuccessful)
    assert(result.getFileEvaluations.length == 1)
    val fileEvaluation = result.getFileEvaluations.head
    assert(fileEvaluation.isSuccessful)
    val expected =
      """|
        |object Main extends App {
        |  println("test");
        |  println("ok")
        |}
        |""".stripMargin
    val obtained = fileEvaluation.previewPatches.get()
    assertNoDiff(obtained, expected)

    val linterError = fileEvaluation.getDiagnostics.toList
    val linterErrorFormatted = linterError
      .map { d =>
        d.position()
          .get()
          .formatMessage(d.severity().toString, d.message())
      }
      .mkString("\n\n")
      .replaceAllLiterally(main.toString, relativePath.toString)
      .replace('\\', '/') // for windows
    assertNoDiff(
      linterErrorFormatted,
      """|src/Main.scala:6:18: ERROR: semicolons are disabled
        |  println("test");
        |                 ^
      """.stripMargin
    )

    val unifiedDiff = fileEvaluation.previewPatchesAsUnifiedDiff.get()
    assert(unifiedDiff.nonEmpty)
    val patches = fileEvaluation.getPatches.toList

    val expectedWithOnePatch =
      """|
        |import scala.concurrent.Future
        |
        |object Main extends App {
        |  import scala.concurrent.Await
        |  println("test");
        |  println("ok")
        |}
        |""".stripMargin
    // if applying all patches we should get the same result
    val obtained2 =
      fileEvaluation.previewPatches(patches.toArray).get()
    assertNoDiff(obtained2, expected)

    val obtained3 = fileEvaluation
      .previewPatches(Seq(patches.head).toArray)
      .get
    assertNoDiff(obtained3, expectedWithOnePatch)

  }

  test("ScalafixArguments.evaluate getting StaleSemanticdb", SkipWindows) {
    // Todo(i1680): We need a semanticRule in scala 3.
    if (ScalaVersions.isScala3) cancel()
    val _ = CompatSemanticdb.runScalac(scalacOptions)
    val args = api
      .withRules(
        List(
          removeUnusedRule().name.toString()
        ).asJava
      )
      .withClasspath((scalaLibrary.map(_.toNIO) :+ target).asJava)
      .withScalacOptions(Collections.singletonList(removeUnused))
      .withPaths(Seq(main).asJava)
      .withSourceroot(src)

    val result = args.evaluate()
    assert(result.getFileEvaluations.length == 1)
    assert(result.isSuccessful)
    // let's modify file and evaluate again
    val code = FileIO.slurp(AbsolutePath(main), StandardCharsets.UTF_8)
    val staleCode = code + "\n// comment\n"
    Files.write(main, staleCode.getBytes(StandardCharsets.UTF_8))
    val evaluation2 = args.evaluate()
    assert(!evaluation2.getError.isPresent)
    assert(!evaluation2.getErrorMessage.isPresent)
    assert(evaluation2.isSuccessful)
    val fileEval = evaluation2.getFileEvaluations.head
    assert(fileEval.getError.get.toString == "StaleSemanticdbError")
    assert(fileEval.getErrorMessage.get.startsWith("Stale SemanticDB"))
    assert(!fileEval.isSuccessful)
  }

  test(
    "ScalafixArguments.evaluate doesn't take into account withMode and withMainCallback",
    SkipWindows
  ) {
    // Todo(i1680): We need a semanticRule in scala 3.
    if (ScalaVersions.isScala3) cancel()
    val _ = CompatSemanticdb.runScalac(scalacOptions)
    val contentBeforeEvaluation =
      FileIO.slurp(AbsolutePath(main), StandardCharsets.UTF_8)
    var maybeDiagnostic: Option[ScalafixDiagnostic] = None
    val scalafixMainCallback = new ScalafixMainCallback {
      override def reportDiagnostic(diagnostic: ScalafixDiagnostic): Unit =
        maybeDiagnostic = Some(diagnostic)
    }
    val result = api
      .withRules(
        List(
          removeUnusedRule().name.toString(),
          "ExplicitResultTypes",
          "DisableSyntax"
        ).asJava
      )
      .withParsedArguments(
        List("--settings.DisableSyntax.noSemicolons", "true").asJava
      )
      .withClasspath((scalaLibrary.map(_.toNIO) :+ target).asJava)
      .withScalacOptions(Collections.singletonList(removeUnused))
      .withPaths(Seq(main).asJava)
      .withSourceroot(src)
      .withMode(ScalafixMainMode.IN_PLACE)
      .withMainCallback(scalafixMainCallback)
      .evaluate()

    val fileEvaluation = result.getFileEvaluations.toSeq.head
    assert(fileEvaluation.getDiagnostics.toSeq.nonEmpty)
    assert(maybeDiagnostic.isEmpty)
    val content = FileIO.slurp(AbsolutePath(main), StandardCharsets.UTF_8)
    assert(contentBeforeEvaluation == content)
    api
      .withRules(
        List(
          removeUnusedRule().name.toString(),
          "ExplicitResultTypes",
          "DisableSyntax"
        ).asJava
      )
      .withParsedArguments(
        List("--settings.DisableSyntax.noSemicolons", "true").asJava
      )
      .withClasspath((scalaLibrary.map(_.toNIO) :+ target).asJava)
      .withScalacOptions(Collections.singletonList(removeUnused))
      .withPaths(Seq(main).asJava)
      .withSourceroot(src)
      .withMode(ScalafixMainMode.IN_PLACE)
      .withMainCallback(scalafixMainCallback)
      .run()

    val contentAfterRun =
      FileIO.slurp(AbsolutePath(main), StandardCharsets.UTF_8)
    assert(contentAfterRun == fileEvaluation.previewPatches().get)
  }

  test("CommentFileNonAtomic retrieves 2 patches") {
    val run1 = api
      .withRules(List("CommentFileNonAtomic").asJava)
      .withSourceroot(src)

    val fileEvaluation1 = run1.evaluate().getFileEvaluations.head
    val patches1 = fileEvaluation1.getPatches
    assert(patches1.length == 2)

  }
  test("CommentFileAtomicRule retrieves 1 patch") {
    val run = api
      .withRules(List("CommentFileAtomic").asJava)
      .withSourceroot(src)
    val fileEvaluation = run.evaluate().getFileEvaluations.head
    val patches = fileEvaluation.getPatches
    assert(patches.length == 1)
  }

  test("Suppression mechanism isn't applied with non atomic patches") {
    val content =
      """|import scala.concurrent.duration // scalafix:ok
        |import scala.concurrent.Future""".stripMargin
    val cwd = StringFS
      .string2dir(
        s"""|/src/Main.scala
          |$content""".stripMargin,
        charset
      )
      .toNIO
    val src = cwd.resolve("src")
    val run = api
      .withRules(List("CommentFileNonAtomic").asJava)
      .withSourceroot(src)

    val fileEvaluation = run.evaluate().getFileEvaluations.head
    val obtained = fileEvaluation.previewPatches().get
    // A patch without `atomic` will ignore suppressions.
    val expected =
      """|/*import scala.concurrent.duration // scalafix:ok
        |import scala.concurrent.Future*/""".stripMargin
    assertNoDiff(obtained, expected)
  }

  test("Suppression mechanism is applied with atomic patches") {
    val content =
      """|import scala.concurrent.duration // scalafix:ok
        |import scala.concurrent.Future""".stripMargin
    val cwd = StringFS
      .string2dir(
        s"""|/src/Main.scala
          |$content""".stripMargin,
        charset
      )
      .toNIO
    val src = cwd.resolve("src")
    val run = api
      .withRules(List("CommentFileAtomic").asJava)
      .withSourceroot(src)

    val fileEvaluation = run.evaluate().getFileEvaluations.head
    val obtained = fileEvaluation.previewPatches().get
    assertNoDiff(obtained, content)
  }

  test(
    "Scalafix-evaluation-error-messages:Unknown rule error message",
    SkipWindows
  ) {
    val eval = api.withRules(List("nonExisting").asJava).evaluate()
    assert(!eval.isSuccessful)
    assert(eval.getError.get.toString == "CommandLineError")
    assert(eval.getErrorMessage.get.contains("Unknown rule"))
  }

  test("Scalafix-evaluation-error-messages: No file error", SkipWindows) {
    val eval = api
      .withPaths(Seq(Paths.get("/tmp/non-existing-file.scala")).asJava)
      .withRules(List("DisableSyntax").asJava)
      .evaluate()
    assert(!eval.isSuccessful)
    assert(eval.getError.get.toString == "NoFilesError")
    assert(eval.getErrorMessage.get == "No files to fix")
  }

  test("Scalafix-evaluation-error-messages: NoRulesError", SkipWindows) {
    // don't get rules from the project's .scalafix.conf
    val noscalafixconfig = Files.createTempDirectory("scalafix")
    val eval = api
      .withPaths(Seq(main).asJava)
      .withWorkingDirectory(noscalafixconfig)
      .evaluate()
    assert(!eval.isSuccessful)
    assert(eval.getErrorMessage.get == "No rules requested to run")
  }

  test("Scalafix-evaluation-error-messages: missing semanticdb", SkipWindows) {
    // Todo(i1680): We need a semanticRule in scala 3.
    if (ScalaVersions.isScala3) cancel()
    val eval = api
      .withPaths(Seq(main).asJava)
      .withRules(List("ExplicitResultTypes").asJava)
      .evaluate()
    assert(eval.isSuccessful)
    val fileEvaluation = eval.getFileEvaluations.head
    assert(fileEvaluation.getError.get.toString == "MissingSemanticdbError")
    assert(fileEvaluation.getErrorMessage.get.contains(main.toString))
    assert(fileEvaluation.getErrorMessage.get.contains("SemanticDB not found"))
  }

  test(
    "Scala2 source with Scala3 syntax can only be parsed with -Xsource:3 or -Xsource:3-cross"
  ) {
    val cwd = StringFS
      .string2dir(
        s"""|/src/Scala2Source3.scala
          |open class Scala2Source3""".stripMargin,
        charset
      )
      .toNIO
    val src = cwd.resolve("src")
    val args = api
      .withScalaVersion("2.13.6")
      .withRules(List("DisableSyntax").asJava)
      .withSourceroot(src)

    val withoutSource3 = args.evaluate().getFileEvaluations.head
    assert(!withoutSource3.isSuccessful)

    val withSource3 =
      args
        .withScalacOptions(Collections.singletonList("-Xsource:3"))
        .evaluate()
        .getFileEvaluations
        .head
    assert(withSource3.isSuccessful)

    val withSource3cross =
      args
        .withScalacOptions(Collections.singletonList("-Xsource:3-cross"))
        .evaluate()
        .getFileEvaluations
        .head
    assert(withSource3cross.isSuccessful)
  }

  test("Source with Scala3 syntax can be parsed with dialect Scala3") {
    val content =
      """|
        |object HelloWorld:
        |  @main def hello = println("Hello, world!")""".stripMargin
    val cwd = StringFS
      .string2dir(
        s"""|/src/Main.scala
          |$content""".stripMargin,
        charset
      )
      .toNIO
    val src = cwd.resolve("src")
    val run = api
      .withScalaVersion("3.0.0")
      .withRules(List("CommentFileAtomic").asJava)
      .withSourceroot(src)
      .evaluate()

    val obtained = run.getFileEvaluations.head.previewPatches.get()

    val expected =
      """|/*
        |object HelloWorld:
        |  @main def hello = println("Hello, world!")*/""".stripMargin
    assertNoDiff(obtained, expected)
  }

  test("Source with Scala3 syntax cannot be parsed with dialect Scala2") {
    val content =
      """|
        |object HelloWorld:
        |  @main def hello = println("Hello, world!")""".stripMargin
    val cwd = StringFS
      .string2dir(
        s"""|/src/Main.scala
          |$content""".stripMargin,
        charset
      )
      .toNIO
    val src = cwd.resolve("src")
    val run = api
      .withScalaVersion("2.13")
      .withRules(List("CommentFileAtomic").asJava)
      .withSourceroot(src)
      .evaluate()

    val obtainedError = run.getFileEvaluations.head.getError.get

    assert(obtainedError == ScalafixFileEvaluationError.ParseError)
  }

  test("withScalaVersion: non-parsable scala version") {
    val run = Try(api.withScalaVersion("213"))
    val expectedErrorMessage = "Failed to parse the Scala version"
    assert(run.failed.toOption.map(_.getMessage) == Some(expectedErrorMessage))
  }

  test("Scala 2.11 is no longer supported") {
    val run = Try(api.withScalaVersion("2.11.12"))
    val expectedErrorMessage =
      "Scala 2.11 is no longer supported; the final version supporting it is Scalafix 0.10.4"
    assert(run.failed.toOption.map(_.getMessage) == Some(expectedErrorMessage))
  }

  test("textEdits returns patch as edits") {
    val cwd: Path = StringFS
      .string2dir(
        """|/src/Main2.scala
          |import scala.concurrent.duration
          |import scala.concurrent.Future
          |
          |object Main extends App {
          |  import scala.concurrent.Await
          |  println("test");
          |  println("ok")
          |}""".stripMargin,
        charset
      )
      .toNIO
    val src = cwd.resolve("src")

    val run = api
      .withRules(List("CommentFileNonAtomic", "CommentFileAtomic").asJava)
      .withSourceroot(src)

    val fileEvaluation = run.evaluate().getFileEvaluations.head
    val patches = fileEvaluation.getPatches

    // CommentFileNonAtomic produces two patches which in turn produce one
    // token patch each, whilst CommentFileAtomic produces one patch which
    // in turn produces two token patches
    val Array(nonAtomicEditArray1, nonAtomicEditArray2, atomicEditArray) =
      patches.map(_.textEdits()).sortBy(_.length)

    // Check the above holds
    assert(nonAtomicEditArray1.length == 1 && nonAtomicEditArray2.length == 1)
    assert(atomicEditArray.length == 2)

    // Check the offsets for the atomic edits look ok (e.g. they're zero-based)
    val Array(atomicEdit1, atomicEdit2) =
      atomicEditArray.sortBy(_.position.startLine)
    assert(
      atomicEdit1.position.startLine == 0 && atomicEdit1.position.startColumn == 0
    )
    assert(
      atomicEdit2.position.startLine == 7 && atomicEdit2.position.startColumn == 1
    )

    // Check the same holds for the non-atomic edit pair
    val Array(nonAtomicEdit1, nonAtomicEdit2) =
      (nonAtomicEditArray1 ++ nonAtomicEditArray2).sortBy(_.position.startLine)
    assert(
      nonAtomicEdit1.position.startLine == 0 && nonAtomicEdit1.position.startColumn == 0
    )
    assert(
      nonAtomicEdit2.position.startLine == 7 && nonAtomicEdit2.position.startColumn == 1
    )
  }

  test("ScalafixPatch isAtomic") {
    val cwd: Path = StringFS
      .string2dir(
        """|/src/Main.scala
          |
          |object Main extends App""".stripMargin,
        charset
      )
      .toNIO
    val src = cwd.resolve("src")

    def patches(rules: List[String]): Array[ScalafixPatch] = {
      val run = api.withRules(rules.asJava).withSourceroot(src)
      val fileEvaluation = run.evaluate().getFileEvaluations.head
      fileEvaluation.getPatches
    }

    assert(patches(List("CommentFileAtomic")).forall(_.isAtomic))
    assert(patches(List("CommentFileNonAtomic")).forall(!_.isAtomic))
  }

  test("com.github.liancheng::organize-imports is ignored") {
    val rules = api
      .withToolClasspath(
        Nil.asJava,
        Seq("com.github.liancheng::organize-imports:0.6.0").asJava
      )
      .availableRules()
      .asScala
      .filter(_.name() == "OrganizeImports")

    // only one should be loaded
    assert(rules.length == 1)

    // ensure it's the built-in one (the external one was marked as experimental)
    assert(!rules.head.isExperimental)
  }

  def removeUnusedRule(): SemanticRule = {
    val config = RemoveUnusedConfig.default
    new RemoveUnused(config)
  }

  def scalaLibrary: Seq[AbsolutePath] = Classpaths.scalaLibrary.entries

}
