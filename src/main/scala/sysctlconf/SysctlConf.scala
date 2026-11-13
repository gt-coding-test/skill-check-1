package sysctlconf

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.collection.immutable.VectorMap

/** `key = value`形式の1行分の設定。
  *
  * @param key
  *   `=`の左辺。前後の空白と行頭の`-`を除き、`.`区切りに正規化したもの
  * @param value
  *   `=`の右辺（前後の空白を除いたもの）
  * @param ignoreErrors
  *   行頭に`-`が付いていたか。sysctl(8)では「この値の設定に失敗しても無視する」という意味。
  * @param line
  *   行番号（1始まり）
  */
final case class Entry(key: String, value: String, ignoreErrors: Boolean, line: Int)

/** `-key`形式（`=`無し）の除外行。
  *
  * sysctl(8)では「このキー（globパターン可）には設定しない」という意味。値を持たないのでMapには入れない。
  */
final case class Exclusion(key: String, line: Int)

/** パース済みのsysctl.conf。
  *
  * @param entries
  *   ファイルに書かれていた設定を出現順にすべて保持する（重複キーも含む）
  * @param exclusions
  *   `-key`形式の除外行を出現順に保持する
  * @param tree
  *   キーを`.`で階層化したMap。同じキーが複数回書かれていた場合は後勝ち。
  */
final case class SysctlConf private (
    entries: Vector[Entry],
    exclusions: Vector[Exclusion],
    tree: Map[String, SysctlValue]
) {

  /** キーを階層化しないフラットなMap（`"log.file" -> "/var/log/console.log"`）。重複キーは後勝ち。 */
  lazy val toMap: Map[String, String] =
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
    * 文法はsysctl.conf(5)と、procpsのsysctl(8)の実装に従う:
    *   - 1行は`token = value`形式。`=`の前後の空白は任意
    *   - 空行、および`#`か`;`で始まる行は無視する
    *   - 行頭の`-`は「設定失敗を無視する」フラグとして[[Entry.ignoreErrors]]に保持する
    *   - `=`の無い`-key`は除外行として[[SysctlConf.exclusions]]に保持する
    *   - 値は引用符不要で、空白や`=`を含んでよい（最初の`=`で分割する）
    *   - キーは`/`区切りでも書ける（`net/ipv4/ip_forward`）。`.`区切りに正規化する
    *
    * 最初に見つかったエラーを`Left`で返す。
    */
  def parse(input: String): Either[SysctlError, SysctlConf] = {
    val empty: Either[SysctlError, SysctlConf] =
      Right(new SysctlConf(Vector.empty, Vector.empty, VectorMap.empty))
    input.stripPrefix(Bom).linesIterator.zipWithIndex.foldLeft(empty) { case (acc, (raw, index)) =>
      for {
        conf   <- acc
        parsed <- parseLine(raw, index + 1)
        next   <- parsed match {
          case Line.Skip               => Right(conf)
          case Line.Exclude(exclusion) =>
            Right(new SysctlConf(conf.entries, conf.exclusions :+ exclusion, conf.tree))
          case Line.Setting(entry) =>
            insert(VectorMap.from(conf.tree), entry.key.split('.').toList, entry.value)
              .toRight(conflict(conf.entries, entry))
              .map(tree => new SysctlConf(conf.entries :+ entry, conf.exclusions, tree))
        }
      } yield next
    }
  }

  private val Bom = "\uFEFF"

  private type Tree = VectorMap[String, SysctlValue]

  private enum Line {
    case Skip
    case Setting(entry: Entry)
    case Exclude(exclusion: Exclusion)
  }

  private def readFile(path: Path): Either[SysctlError, String] =
    try Right(Files.readString(path, StandardCharsets.UTF_8))
    catch { case e: IOException => Left(SysctlError.Io(path, e)) }

  /** Cの`isspace`と同じ空白の定義。行・キー・値の前後の除去に使う。 */
  private def isSpace(c: Char): Boolean =
    c == ' ' || c == '\t' || c == '\n' || c == '\u000B' || c == '\f' || c == '\r'

  /** 全角スペースやNBSPを含む、Unicodeの空白。キーの検査に使う。
    *
    * 前後の除去はsysctl(8)に合わせてASCIIの空白だけを対象にするので、全角スペースはキーの側に残る。 見た目で気付けない入力ミスになるため、キーに含まれていたらエラーにする。
    */
  private def isAnySpace(c: Char): Boolean = isSpace(c) || c.isWhitespace || c.isSpaceChar

  private def stripLeft(s: String): String  = s.dropWhile(isSpace)
  private def stripRight(s: String): String = s.take(s.lastIndexWhere(!isSpace(_)) + 1)

  private def parseLine(raw: String, line: Int): Either[SysctlError, Line] = {
    val text = stripRight(stripLeft(raw))
    if (text.isEmpty || text.startsWith("#") || text.startsWith(";")) Right(Line.Skip)
    else {
      val dash = text.startsWith("-")
      val body = if (dash) text.drop(1) else text
      body.indexOf('=') match {
        case -1 if dash => normalizeKey(body, line).map(key => Line.Exclude(Exclusion(key, line)))
        case -1         => Left(SysctlError.Syntax(line, SyntaxReason.MissingEquals))
        case i          =>
          val value = stripLeft(body.drop(i + 1))
          normalizeKey(stripRight(body.take(i)), line)
            .map(key => Line.Setting(Entry(key, value, ignoreErrors = dash, line)))
      }
    }
  }

  /** キーを検査し、`.`区切りに正規化して返す。 */
  private def normalizeKey(raw: String, line: Int): Either[SysctlError, String] = {
    def error(reason: SyntaxReason) = Left(SysctlError.Syntax(line, reason))

    val key = toDotted(raw)
    if (raw.isEmpty) error(SyntaxReason.EmptyKey)
    else if (raw.startsWith("-")) error(SyntaxReason.RepeatedDash)
    else if (raw.exists(isAnySpace)) error(SyntaxReason.WhitespaceInKey)
    else if (key.split("\\.", -1).exists(_.isEmpty)) error(SyntaxReason.EmptySegment)
    else Right(key)
  }

  /** sysctl(8)と同じ規則で区切り文字を`.`に揃える。
    *
    * 最初に現れた区切りが`/`なら、`/`と`.`を入れ替える。`net/ipv4/conf/eth0.100/rp_filter`は
    * `net.ipv4.conf.eth0/100.rp_filter`になり、名前に`.`を含むインターフェースも1セグメントとして扱える。
    */
  private def toDotted(key: String): String =
    key.find(c => c == '.' || c == '/') match {
      case Some('/') =>
        key.map {
          case '/' => '.'
          case '.' => '/'
          case c   => c
        }
      case _ => key
    }

  /** `path`の位置に値を置いた新しいツリーを返す。値と階層が同じ位置に来る場合は`None`。 */
  private def insert(tree: Tree, path: List[String], value: String): Option[Tree] =
    path match {
      case Nil         => Some(tree)
      case leaf :: Nil =>
        tree.get(leaf) match {
          case Some(_: SysctlValue.Node) => None
          case _                         => Some(tree.updated(leaf, SysctlValue.Leaf(value)))
        }
      case head :: rest =>
        val child: Option[Tree] = tree.get(head) match {
          case None                             => Some(VectorMap.empty)
          case Some(SysctlValue.Node(children)) => Some(VectorMap.from(children))
          case Some(_: SysctlValue.Leaf)        => None
        }
        child
          .flatMap(insert(_, rest, value))
          .map(sub => tree.updated(head, SysctlValue.Node(sub)))
    }

  /** `entry`と衝突している定義済みのキー（一方がもう一方の階層の途中にあるもの）を探してエラーにする。 */
  private def conflict(entries: Vector[Entry], entry: Entry): SysctlError = {
    def isAncestor(a: String, b: String) = b.startsWith(a + ".")

    val definedAt = entries
      .findLast(e => isAncestor(e.key, entry.key) || isAncestor(entry.key, e.key))
      .fold(entry.line)(_.line)
    SysctlError.KeyConflict(entry.line, entry.key, definedAt)
  }
}
