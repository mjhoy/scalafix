package scalafix.tests.core

import scala.meta._
import scala.meta.internal.io.PathIO
import scala.meta.internal.symtab.SymbolTable

import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite
import scalafix.internal.config.ScalaVersion
import scalafix.internal.reflect.ClasspathOps
import scalafix.internal.v0.LegacyInMemorySemanticdbIndex
import scalafix.syntax._
import scalafix.testkit.DiffAssertions
import scalafix.tests.BuildInfo
import scalafix.v1.SemanticDocument
import scalafix.v1.SyntacticDocument

object BaseSemanticSuite {
  val defaultScalaVersion = ScalaVersion.scala2 // For tests
  lazy val symtab: SymbolTable = {
    val classpath =
      Classpaths.withDirectory(AbsolutePath(BuildInfo.classDirectory))
    ClasspathOps.newSymbolTable(classpath)
  }
  def loadDoc(
      filename: String,
      scalaVersion: Option[String] = None
  ): SemanticDocument = {
    val (abspath, scalaVersion) = {
      val root = AbsolutePath(BuildInfo.sourceroot)
      val commonPath = root.resolve("scala/test").resolve(filename)
      if (commonPath.toFile.exists) {
        (commonPath, defaultScalaVersion)
      } else {
        val scalaMajorVersion = BuildInfo.scalaVersion.split("\\.")(0)
        val path =
          root.resolve(s"scala-$scalaMajorVersion/test").resolve(filename)
        (path, ScalaVersion.from(BuildInfo.scalaVersion).get)
      }
    }
    val relpath = abspath.toRelative(AbsolutePath(BuildInfo.baseDirectory))
    val input = Input.File(abspath)
    val doc = SyntacticDocument.fromInput(input, scalaVersion)
    SemanticDocument.fromPath(
      doc,
      relpath,
      ClasspathOps.thisClassLoaderWith(
        BuildInfo.semanticClasspath.map(_.toURI.toURL)
      ),
      symtab
    )
  }
}

abstract class BaseSemanticSuite(filename: String)
    extends AnyFunSuite
    with BeforeAndAfterAll
    with DiffAssertions {
  var _db: LegacyInMemorySemanticdbIndex = _
  var _input: Input = _
  implicit def index: LegacyInMemorySemanticdbIndex = _db
  def input: Input = _input
  def source: Source = {
    input.parse[Source].get
  }

  override def beforeAll(): Unit = {
    val dirs =
      scalafix.tests.BuildInfo.semanticClasspath.map(AbsolutePath.apply)
    _db = LegacyInMemorySemanticdbIndex.load(
      Classpaths.withDirectories(dirs.toList),
      PathIO.workingDirectory
    )
    _input = _db.inputs
      .collectFirst {
        case i if i.label.contains(filename) =>
          i
      }
      .getOrElse {
        throw new IllegalArgumentException(
          s"No $filename.semanticdb file found!"
        )
      }
  }
}
