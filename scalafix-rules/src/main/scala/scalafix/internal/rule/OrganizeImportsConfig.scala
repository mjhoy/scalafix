package scalafix.internal.rule

import metaconfig.Conf
import metaconfig.ConfDecoder
import metaconfig.ConfEncoder
import metaconfig.generic.Surface
import metaconfig.generic.deriveDecoder
import metaconfig.generic.deriveEncoder
import metaconfig.generic.deriveSurface
import scalafix.internal.config.ReaderUtil

sealed trait ImportsOrder

object ImportsOrder {
  case object Ascii extends ImportsOrder
  case object SymbolsFirst extends ImportsOrder
  case object Keep extends ImportsOrder

  def all: List[ImportsOrder] =
    List(Ascii, SymbolsFirst, Keep)

  implicit def reader: ConfDecoder[ImportsOrder] =
    ReaderUtil.fromMap(all.map(x => x.toString -> x).toMap)

  implicit def writer: ConfEncoder[ImportsOrder] =
    ConfEncoder.instance(v => Conf.Str(v.toString))
}

sealed trait ImportSelectorsOrder

object ImportSelectorsOrder {
  case object Ascii extends ImportSelectorsOrder
  case object SymbolsFirst extends ImportSelectorsOrder
  case object Keep extends ImportSelectorsOrder

  def all: List[ImportSelectorsOrder] =
    List(Ascii, SymbolsFirst, Keep)

  implicit def reader: ConfDecoder[ImportSelectorsOrder] =
    ReaderUtil.fromMap(all.map(x => x.toString -> x).toMap)

  implicit def writer: ConfEncoder[ImportSelectorsOrder] =
    ConfEncoder.instance(v => Conf.Str(v.toString))
}

sealed trait GroupedImports

object GroupedImports {
  case object AggressiveMerge extends GroupedImports
  case object Merge extends GroupedImports
  case object Explode extends GroupedImports
  case object Keep extends GroupedImports

  def all: List[GroupedImports] =
    List(AggressiveMerge, Merge, Explode, Keep)

  implicit def reader: ConfDecoder[GroupedImports] =
    ReaderUtil.fromMap(all.map(x => x.toString -> x).toMap)

  implicit def writer: ConfEncoder[GroupedImports] =
    ConfEncoder.instance(v => Conf.Str(v.toString))
}

sealed trait BlankLines

object BlankLines {
  case object Auto extends BlankLines
  case object Manual extends BlankLines

  def all: List[BlankLines] =
    List(Auto, Manual)

  implicit def reader: ConfDecoder[BlankLines] =
    ReaderUtil.fromMap(all.map(x => x.toString -> x).toMap)

  implicit def writer: ConfEncoder[BlankLines] =
    ConfEncoder.instance(v => Conf.Str(v.toString))
}

sealed trait Preset

object Preset {
  case object DEFAULT extends Preset
  case object INTELLIJ_2020_3 extends Preset

  def all: List[Preset] =
    List(DEFAULT, INTELLIJ_2020_3)

  implicit def reader: ConfDecoder[Preset] =
    ReaderUtil.fromMap(all.map(x => x.toString -> x).toMap)

  implicit def writer: ConfEncoder[Preset] =
    ConfEncoder.instance(v => Conf.Str(v.toString))
}

sealed trait TargetDialect
object TargetDialect {
  case object Auto extends TargetDialect
  case object Scala2 extends TargetDialect
  case object Scala3 extends TargetDialect
  case object StandardLayout extends TargetDialect

  def all: List[TargetDialect] =
    List(Auto, Scala2, Scala3, StandardLayout)

  implicit def reader: ConfDecoder[TargetDialect] =
    ReaderUtil.fromMap(all.map(x => x.toString -> x).toMap)

  implicit def writer: ConfEncoder[TargetDialect] =
    ConfEncoder.instance(v => Conf.Str(v.toString))
}

final case class OrganizeImportsConfig(
    blankLines: BlankLines = BlankLines.Auto,
    coalesceToWildcardImportThreshold: Option[Int] = None,
    expandRelative: Boolean = false,
    groupExplicitlyImportedImplicitsSeparately: Boolean = false,
    groupedImports: GroupedImports = GroupedImports.Explode,
    groups: Seq[String] = Seq(
      "*",
      "re:(javax?|scala)\\."
    ),
    importSelectorsOrder: ImportSelectorsOrder = ImportSelectorsOrder.Ascii,
    importsOrder: ImportsOrder = ImportsOrder.Ascii,
    preset: Preset = Preset.DEFAULT,
    removeUnused: Boolean = true,
    targetDialect: TargetDialect = TargetDialect.StandardLayout
)

object OrganizeImportsConfig {
  val default: OrganizeImportsConfig = OrganizeImportsConfig()

  implicit val surface: Surface[OrganizeImportsConfig] =
    deriveSurface
  implicit val encoder: ConfEncoder[OrganizeImportsConfig] =
    deriveEncoder
  implicit val decoder: ConfDecoder[OrganizeImportsConfig] =
    deriveDecoder(default)

  val presets: Map[Preset, OrganizeImportsConfig] = Map(
    Preset.DEFAULT -> OrganizeImportsConfig(),
    Preset.INTELLIJ_2020_3 -> OrganizeImportsConfig(
      coalesceToWildcardImportThreshold = Some(5),
      groupedImports = GroupedImports.Merge
    )
  )
}
