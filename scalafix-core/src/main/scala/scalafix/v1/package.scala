package scalafix

import scala.meta.Term
import scala.meta.Tree
import scala.meta.inputs.Position
import scalafix.util.Api

package object v1 extends Api {
  implicit class XtensionTreeScalafixSemantic(tree: Tree) {
    def symbol(implicit doc: SemanticDoc): Symbol = doc.internal.symbol(tree)
  }
  implicit class XtensionTermScalafixSemantic(term: Term) {
    def synthetic(implicit doc: SemanticDoc): Option[STree] =
      doc.internal.synthetic(term.pos)
  }
  implicit class XtensionTermApplyInfixScalafixSemantic(
      infix: Term.ApplyInfix) {
    def syntheticOperator(implicit doc: SemanticDoc): Option[STree] = {
      val operatorPosition =
        Position.Range(infix.pos.input, infix.pos.start, infix.op.pos.end)
      doc.internal.synthetic(operatorPosition)
    }
  }

  implicit class XtensionSyntheticTree(tree: STree) {
    def symbol(implicit sdoc: SemanticDoc): Option[Symbol] = tree match {
      case t: ApplyTree =>
        t.fn.symbol
      case t: TypeApplyTree =>
        t.fn.symbol
      case t: SelectTree =>
        Some(t.id.symbol)
      case t: IdTree =>
        Some(t.symbol)
      case t: OriginalTree =>
        t.tree.symbol.asNonEmpty
      case _: MacroExpansionTree | _: LiteralTree | _: FunctionTree | NoTree =>
        None
    }
  }
}