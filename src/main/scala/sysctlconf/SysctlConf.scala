package sysctlconf

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.collection.immutable.VectorMap

/** sysctl.conf形式の1行分の設定。
  *
  * @param key
  *   `=`の左辺（前後の空白と先頭の`-`を除いたもの）
  * @param value
  *   `=`の右辺（前後の空白を除いたもの）
  * @param ignoreErrors
  *   行頭に`-`が付いていたか。sysctl(8)では「この値の設定に失敗しても無視する」という意味。
  * @param line
  *   行番号（1始まり）
  */
final case class Entry(key: String, value: String, ignoreErrors: Boolean, line: Int)

/** パース済みのsysctl.conf。
  *
  * @param entries
  *   ファイルに書かれていた設定を出現順にすべて保持する（重複キーも含む）
  * @param tree
  *   キーを`.`で階層化したMap。同じキーが複数回書かれていた場合は後勝ち。
  */
final case class SysctlConf private (entries: Vector[Entry], tree: Map[String, SysctlValue]) {

  /** キーを階層化しないフラットなMap（`"log.file" -> "/var/log/console.log"`）。重複キーは後勝ち。 */
  def toMap: Map[String, String] =
    entries.foldLeft(VectorMap.empty[String, String])((acc, e) => acc.updated(e.key, e.value))

  /** ドット区切りのキーで値を引く。キーが無い、または階層の途中を指している場合は`None`。 */
  def get(key: String): Option[String] = toMap.get(key)
}

object SysctlConf {

  /** ファイルをUTF-8として読み込んでパースする。 */
  def load(path: Path): Either[SysctlError, SysctlConf] =
    readFile(path).flatMap(parse)

  /** sysctl.conf形式の文字列をパースする。
    *
    * 文法はsysctl.conf(5)に従う:
    *   - 1行は`token = value`形式。`=`の前後の空白は任意
    *   - 空行、および`#`か`;`で始まる行は無視する
    *   - 行頭の`-`は「設定失敗を無視する」フラグとして[[Entry.ignoreErrors]]に保持する
    *   - 値は引用符不要で、空白や`=`を含んでよい（最初の`=`で分割する）
    *
    * 最初に見つかったエラーを`Left`で返す。
    */
  def parse(input: String): Either[SysctlError, SysctlConf] = {
    val lines = input.stripPrefix("﻿").linesIterator.zipWithIndex

    val init: Either[SysctlError, (Vector[Entry], Tree)] = Right((Vector.empty, VectorMap.empty))
    lines
      .foldLeft(init) { case (acc, (raw, index)) =>
        for {
          state <- acc
          (entries, tree) = state
          parsed <- parseLine(raw, index + 1)
          next   <- parsed match {
            case None        => Right(state)
            case Some(entry) =>
              insert(tree, entry.key.split('.').toList, entry).map((entries :+ entry, _))
          }
        } yield next
      }
      .map { case (entries, tree) => new SysctlConf(entries, tree) }
  }

  private type Tree = VectorMap[String, SysctlValue]

  private def readFile(path: Path): Either[SysctlError, String] =
    try Right(Files.readString(path, StandardCharsets.UTF_8))
    catch { case e: IOException => Left(SysctlError.Io(path, e)) }

  /** 1行をパースする。空行・コメント行は`Right(None)`。 */
  private def parseLine(raw: String, line: Int): Either[SysctlError, Option[Entry]] = {
    val trimmed = raw.trim
    if (trimmed.isEmpty || trimmed.startsWith("#") || trimmed.startsWith(";")) Right(None)
    else {
      val ignoreErrors = trimmed.startsWith("-")
      val body         = if (ignoreErrors) trimmed.drop(1) else trimmed
      body.indexOf('=') match {
        case -1 => Left(SysctlError.Syntax(line, "expected 'token = value' but '=' was not found"))
        case i  =>
          val key   = body.take(i).trim
          val value = body.drop(i + 1).trim
          validateKey(key, line).map(_ => Some(Entry(key, value, ignoreErrors, line)))
      }
    }
  }

  private def validateKey(key: String, line: Int): Either[SysctlError, Unit] =
    if (key.isEmpty) Left(SysctlError.Syntax(line, "key is empty"))
    else if (key.exists(_.isWhitespace))
      Left(SysctlError.Syntax(line, s"key '$key' must not contain whitespace"))
    else if (key.split("\\.", -1).exists(_.isEmpty))
      Left(SysctlError.Syntax(line, s"key '$key' has an empty segment"))
    else Right(())

  /** `path`の位置に値を置いた新しいツリーを返す。値と階層が同じ位置に来る場合は衝突。 */
  private def insert(tree: Tree, path: List[String], entry: Entry): Either[SysctlError, Tree] = {
    def conflict = Left(SysctlError.KeyConflict(entry.line, entry.key))
    path match {
      case Nil         => Right(tree)
      case leaf :: Nil =>
        tree.get(leaf) match {
          case Some(_: SysctlValue.Node) => conflict
          case _                         => Right(tree.updated(leaf, SysctlValue.Leaf(entry.value)))
        }
      case head :: rest =>
        val child: Either[SysctlError, Tree] = tree.get(head) match {
          case None                             => Right(VectorMap.empty)
          case Some(SysctlValue.Node(children)) => Right(VectorMap.from(children))
          case Some(_: SysctlValue.Leaf)        => conflict
        }
        child
          .flatMap(insert(_, rest, entry))
          .map(sub => tree.updated(head, SysctlValue.Node(sub)))
    }
  }
}
