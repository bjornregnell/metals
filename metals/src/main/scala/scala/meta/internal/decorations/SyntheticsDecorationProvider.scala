package scala.meta.internal.decorations

import java.nio.charset.Charset
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.Try

import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.ClientConfiguration
import scala.meta.internal.metals.Diagnostics
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.mtags.Md5Fingerprints
import scala.meta.internal.mtags.SemanticdbClasspath
import scala.meta.internal.mtags.Semanticdbs
import scala.meta.internal.pc.HoverMarkup
import scala.meta.internal.semanticdb.Scala._
import scala.meta.internal.semanticdb.TextDocument
import scala.meta.internal.semanticdb.TextDocuments
import scala.meta.io.AbsolutePath

import org.eclipse.lsp4j.TextDocumentPositionParams
import org.eclipse.{lsp4j => l}

final class SyntheticsDecorationProvider(
    workspace: AbsolutePath,
    semanticdbs: Semanticdbs,
    buffer: Buffers,
    client: DecorationClient,
    fingerprints: Md5Fingerprints,
    charset: Charset,
    diagnostics: Diagnostics,
    focusedDocument: () => Option[AbsolutePath],
    clientConfig: ClientConfiguration,
    userConfig: () => UserConfiguration
)(implicit ec: ExecutionContext) {

  import SemanticdbTreePrinter.printSyntheticInfo

  private object Document {
    /* We update it with each compilation in order not read the same file on
     * each change. When typing documents stay the same most of the time.
     */
    private val document = new AtomicReference[TextDocument]

    def currentDocument: Option[TextDocument] = Option(document.get())

    def set(doc: TextDocument): Unit = document.set(doc)
  }

  def publishSynthetics(path: AbsolutePath): Future[Unit] = {

    // If focused document is not supported we can't be sure what is currently open
    def isFocusedDocument =
      focusedDocument().contains(path) || !clientConfig.isDidFocusProvider()

    /**
     * Worksheets currently do not use semanticdb, which is why this will not work.
     * If at any time worksheets will support it, we need to make sure that the
     * evaluation will not be replaced with implicit decorations.
     */
    if (!path.isWorksheet && isFocusedDocument) {
      val textDocument = currentDocument(path)
      textDocument match {
        case Some(doc) =>
          publishSyntheticDecorations(path, doc)
        case _ =>
          Future.successful(())
      }

    } else
      Future.successful(())
  }

  def onChange(textDocument: TextDocuments, path: Path): Unit = {
    for {
      focused <- focusedDocument()
      filePath = AbsolutePath(path)
      sourcePath <- SemanticdbClasspath.toScala(workspace, filePath)
      if sourcePath == focused || !clientConfig.isDidFocusProvider()
      textDoc <- textDocument.documents.headOption
      source <- fingerprints.loadLastValid(sourcePath, textDoc.md5, charset)
    } {
      val docWithText = textDoc.withText(source)
      Document.set(docWithText)
      publishSyntheticDecorations(sourcePath, docWithText)
    }
  }

  def refresh(): Unit = {
    focusedDocument().foreach(publishSynthetics)
  }

  def addSyntheticsHover(
      params: TextDocumentPositionParams,
      pcHover: Option[l.Hover]
  ): Option[l.Hover] =
    if (isSyntheticsEnabled) {
      val path = params.getTextDocument().getUri().toAbsolutePath
      val line = params.getPosition().getLine()
      val newHover = currentDocument(path) match {
        case Some(textDocument) =>
          val edit = buffer.tokenEditDistance(path, textDocument.text)

          val syntheticsAtLine = for {
            synthetic <- textDocument.synthetics
            (fullSnippet, range) <- printSyntheticInfo(
              textDocument,
              synthetic,
              toHoverString(textDocument),
              userConfig(),
              isHover = true,
              isInlineProvider = clientConfig.isInlineDecorationProvider()
            )
            realRange <- edit.toRevisedStrict(range).toIterable
            if realRange.getEnd.getLine == line
          } yield (realRange, fullSnippet)

          if (syntheticsAtLine.size > 0) {
            if (clientConfig.isInlineDecorationProvider()) {
              createHoverAtPoint(
                path,
                syntheticsAtLine,
                pcHover,
                params.getPosition()
              )
            } else {
              createHoverAtLine(path, syntheticsAtLine, pcHover).orElse(
                pcHover
              )
            }
          } else {
            None
          }
        case None => None
      }
      newHover.orElse(pcHover)
    } else {
      pcHover
    }

  private def isSyntheticsEnabled: Boolean = {
    userConfig().showImplicitArguments || userConfig().showInferredType || userConfig().showImplicitConversionsAndClasses
  }

  private def createHoverAtPoint(
      path: AbsolutePath,
      syntheticsAtLine: Seq[(l.Range, String)],
      pcHover: Option[l.Hover],
      position: l.Position
  ): Option[l.Hover] = {
    val interestingSynthetics = syntheticsAtLine.collect {
      case (range, text)
          if range.getEnd().getCharacter() == position.getCharacter() =>
        text
    }.distinct

    if (interestingSynthetics.nonEmpty)
      addToHover(
        pcHover,
        "**Synthetics**:\n"
          +
            HoverMarkup(
              interestingSynthetics.mkString("\n")
            )
      )
    else None
  }

  private def createHoverAtLine(
      path: AbsolutePath,
      syntheticsAtLine: Seq[(l.Range, String)],
      pcHover: Option[l.Hover]
  ): Option[l.Hover] =
    Try {
      val line = syntheticsAtLine.head._1.getEnd().getLine()
      for {
        currentText <- buffer.get(path)
        lineText <- currentText.linesIterator.drop(line).headOption
        created <- createLine(syntheticsAtLine, pcHover, lineText)
      } yield created
    }.toOption.flatten

  private def createLine(
      syntheticsAtLine: Seq[(l.Range, String)],
      pcHover: Option[l.Hover],
      lineText: String
  ) = {
    val withEnd = syntheticsAtLine
      .map { case (range, str) =>
        (range.getEnd().getCharacter(), str)
      }
      .sortBy(_._1) :+ (lineText.size, "")
    val lineWithDecorations = withEnd
      .foldLeft(("", 0)) { case ((current, index), (char, text)) =>
        val toAdd = lineText.substring(index, char) + text
        (current + toAdd, char)
      }
      ._1

    addToHover(
      pcHover,
      "**With synthetics added**:\n"
        + HoverMarkup(
          lineWithDecorations.trim()
        )
    )
  }

  private def addToHover(
      pcHover: Option[l.Hover],
      text: String
  ): Option[l.Hover] = {
    // Left is not handled currently, but we do not use it in Metals
    if (pcHover.exists(_.getContents().isLeft())) {
      None
    } else {
      val previousContent = pcHover
        .filter(_.getContents().isRight())
        .map(_.getContents().getRight.getValue + "\n")
        .getOrElse("")
      Some(
        new l.Hover(
          new l.MarkupContent(
            l.MarkupKind.MARKDOWN,
            previousContent + "\n" + text
          )
        )
      )
    }
  }

  private def currentDocument(path: AbsolutePath): Option[TextDocument] = {
    Document.currentDocument match {
      case Some(doc) if workspace.resolve(doc.uri) == path => Some(doc)
      case _ =>
        val textDocument = semanticdbs
          .textDocument(path)
          .documentIncludingStale
        textDocument.foreach(Document.set)
        textDocument
    }
  }

  private def toSymbolName(symbol: String, textDoc: TextDocument): String = {
    if (symbol.isLocal)
      textDoc.symbols
        .find(_.symbol == symbol)
        .map(_.displayName)
        .getOrElse(symbol)
    else symbol
  }

  private def toHoverString(textDoc: TextDocument)(symbol: String): String = {
    toSymbolName(symbol, textDoc)
      .replace("/", ".")
      .stripSuffix(".")
      .stripSuffix("#")
      .stripPrefix("_empty_.")
      .replace("#", ".")
      .replace("()", "")
  }

  private def toDecorationString(
      textDoc: TextDocument
  )(symbol: String): String = {
    if (symbol.isLocal)
      textDoc.symbols
        .find(_.symbol == symbol)
        .map(_.displayName)
        .getOrElse(symbol)
    else symbol.desc.name.value
  }

  private def decorationOptions(
      lspRange: l.Range,
      decorationText: String
  ) = {
    // We don't add hover due to https://github.com/microsoft/vscode/issues/105302
    new DecorationOptions(
      lspRange,
      renderOptions = ThemableDecorationInstanceRenderOptions(
        after = ThemableDecorationAttachmentRenderOptions(
          decorationText,
          color = "grey",
          fontStyle = "italic",
          opacity = 0.7
        )
      )
    )
  }

  private def publishSyntheticDecorations(
      path: AbsolutePath,
      textDocument: TextDocument
  ): Future[Unit] =
    Future {
      if (clientConfig.isInlineDecorationProvider()) {
        val edit = buffer.tokenEditDistance(path, textDocument.text)
        val decorations = for {
          synthetic <- textDocument.synthetics
          (decoration, range) <- printSyntheticInfo(
            textDocument,
            synthetic,
            toDecorationString(textDocument),
            userConfig(),
            isHover = false
          )
          lspRange <- edit.toRevisedStrict(range).toIterable
        } yield decorationOptions(lspRange, decoration)

        val params =
          new PublishDecorationsParams(
            path.toURI.toString(),
            decorations.toArray
          )

        client.metalsPublishDecorations(params)
      }
    }

}
