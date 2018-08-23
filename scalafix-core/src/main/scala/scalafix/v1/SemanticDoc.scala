package scalafix.v1

import scala.meta.io.RelativePath
import scala.meta._
import scala.meta.contrib.AssociatedComments
import scala.meta.internal.symtab.SymbolTable
import scalafix.util.MatchingParens
import scalafix.util.TokenList
import scala.meta.internal.{semanticdb => s}
import scalafix.internal.v1._

final class SemanticDoc private[scalafix] (
    private[scalafix] val internal: InternalSemanticDoc
) extends SemanticContext {
  def tree: Tree = internal.doc.tree
  def tokens: Tokens = internal.doc.tokens
  def input: Input = internal.doc.input
  def matchingParens: MatchingParens = internal.doc.matchingParens
  def tokenList: TokenList = internal.doc.tokenList
  def comments: AssociatedComments = internal.doc.comments
  def symbol(tree: Tree): Symbol = {
    val result = internal.symbols(TreePos.symbol(tree))
    if (result.hasNext) result.next() // Discard multi symbols
    else Symbol.None
  }
  override def toString: String = s"SemanticDoc(${input.syntax})"
}

object SemanticDoc {
  sealed abstract class Error(msg: String) extends Exception(msg)
  object Error {
    final case class MissingSemanticdb(reluri: String)
        extends Error(s"SemanticDB not found: $reluri")
    final case class MissingTextDocument(reluri: String)
        extends Error(s"TextDocument.uri not found: $reluri")
  }

  def fromPath(
      doc: Doc,
      path: RelativePath,
      classLoader: ClassLoader,
      symtab: SymbolTable
  ): SemanticDoc = {
    val semanticdbReluri = s"META-INF/semanticdb/$path.semanticdb"
    Option(classLoader.getResourceAsStream(semanticdbReluri)) match {
      case Some(inputStream) =>
        val sdocs =
          try s.TextDocuments.parseFrom(inputStream).documents
          finally inputStream.close()
        val reluri = path.toRelativeURI.toString
        val sdoc = sdocs.find(_.uri == reluri).getOrElse {
          throw Error.MissingTextDocument(reluri)
        }
        val impl = new InternalSemanticDoc(doc, sdoc, symtab)
        new SemanticDoc(impl)
      case None =>
        throw Error.MissingSemanticdb(semanticdbReluri)
    }
  }
}
