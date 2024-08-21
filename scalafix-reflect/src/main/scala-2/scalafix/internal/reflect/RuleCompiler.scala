package scalafix.internal.reflect
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Paths

import scala.reflect.internal.util.AbstractFileClassLoader
import scala.reflect.internal.util.BatchSourceFile
import scala.reflect.io.Directory
import scala.reflect.io.PlainDirectory
import scala.tools.nsc.Global
import scala.tools.nsc.Settings
import scala.tools.nsc.io.VirtualDirectory
import scala.tools.nsc.reporters.StoreReporter

import metaconfig.ConfError
import metaconfig.Configured
import metaconfig.Input
import metaconfig.Position

class RuleCompiler(
    toolClasspath: URLClassLoader,
    targetDirectory: Option[File] = None
) {
  private val output = targetDirectory match {
    case Some(file) => new PlainDirectory(new Directory(file))
    case None => new VirtualDirectory("(memory)", None)
  }
  private val settings = new Settings()
  settings.deprecation.value = true // enable detailed deprecation warnings
  settings.unchecked.value = true // enable detailed unchecked warnings
  settings.outputDirs.setSingleOutput(output)
  val classpath: String =
    (toolClasspath.getURLs.map(url => Paths.get(url.toURI)) ++
      RuleCompilerClasspath.defaultClasspathPaths.map(_.toNIO))
      .mkString(File.pathSeparator)
  settings.classpath.value = classpath
  lazy val reporter = new StoreReporter
  private val global = new Global(settings, reporter)

  def compile(input: Input): Configured[ClassLoader] = {
    reporter.reset()
    val run = new global.Run
    val label = input match {
      case Input.File(path, _) => path.toString
      case Input.VirtualFile(path, _) => path
      case _ => "(input)"
    }
    run.compileSources(
      List(new BatchSourceFile(label, new String(input.chars)))
    )
    val errors = reporter.infos.collect {
      case r: reporter.Info if r.severity == reporter.ERROR =>
        ConfError
          .message(r.msg)
          .atPos(
            if (r.pos.isDefined) Position.Range(input, r.pos.start, r.pos.end)
            else Position.None
          )
          .notOk
    }
    ConfError
      .fromResults(errors.toSeq)
      .map(_.notOk)
      .getOrElse {
        val classLoader: AbstractFileClassLoader =
          new AbstractFileClassLoader(output, toolClasspath)
        Configured.Ok(classLoader)
      }
  }
}
