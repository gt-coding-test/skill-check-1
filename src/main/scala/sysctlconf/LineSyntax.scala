package sysctlconf

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

/** 設定ファイルとスキーマファイルに共通する、ファイル・行・キーの扱い。 */
private[sysctlconf] object LineSyntax {

  private val Bom = "\uFEFF"

  def readFile(path: Path): Either[IOException, String] =
    try Right(Files.readString(path, StandardCharsets.UTF_8))
    catch { case e: IOException => Left(e) }

  /** 入力を行に分け、前後の空白を除いた行と行番号（1始まり）の組にする。先頭のBOMは取り除く。 */
  def lines(input: String): Iterator[(String, Int)] =
    input.stripPrefix(Bom).linesIterator.zipWithIndex.map { case (raw, index) =>
      (stripRight(stripLeft(raw)), index + 1)
    }

  /** 空行、または`#`か`;`で始まるコメント行か。前後の空白を除いた行を渡す。 */
  def isIgnored(text: String): Boolean =
    text.isEmpty || text.startsWith("#") || text.startsWith(";")

  /** Cの`isspace`と同じ空白の定義。行・キー・値の前後の除去に使う。 */
  private def isSpace(c: Char): Boolean =
    c == ' ' || c == '\t' || c == '\n' || c == '\u000B' || c == '\f' || c == '\r'

  /** 全角スペースやNBSPを含む、Unicodeの空白。キーの検査に使う。
    *
    * 前後の除去はsysctl(8)に合わせてASCIIの空白だけを対象にするので、全角スペースはキーの側に残る。 見た目で気付けない入力ミスになるため、キーに含まれていたらエラーにする。
    */
  private def isAnySpace(c: Char): Boolean = isSpace(c) || c.isWhitespace || c.isSpaceChar

  def stripLeft(s: String): String  = s.dropWhile(isSpace)
  def stripRight(s: String): String = s.take(s.lastIndexWhere(!isSpace(_)) + 1)

  /** キーを検査し、`.`区切りに正規化して返す。 */
  def normalizeKey(raw: String): Either[SyntaxReason, String] = {
    val key = toDotted(raw)
    if (raw.isEmpty) Left(SyntaxReason.EmptyKey)
    else if (raw.exists(isAnySpace)) Left(SyntaxReason.WhitespaceInKey)
    else if (key.split("\\.", -1).exists(_.isEmpty)) Left(SyntaxReason.EmptySegment)
    else Right(key)
  }

  /** 正規化済みのキー`ancestor`が、`key`の階層の途中にあるか（`log`と`log.file`）。 */
  def isAncestor(ancestor: String, key: String): Boolean = key.startsWith(ancestor + ".")

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
}
