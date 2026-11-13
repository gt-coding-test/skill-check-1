package sysctlconf

import java.nio.file.Paths

/** 手元のファイルで動作を確かめるための実行用の入口。
  *
  * {{{
  * sbt "run app.conf"
  * sbt "run app.conf app.schema"
  * sbt "run app.conf app.schema --allow-unknown-keys"
  * }}}
  *
  * ロードできれば格納された内容を標準出力に表示する。失敗した場合はエラーを標準エラー出力に表示し、終了コード1で終わる。
  * ライブラリとして使う場合は[[SysctlConf.load]]を直接呼ぶ。
  */
object Main {

  private val AllowUnknownKeys = "--allow-unknown-keys"

  private val Usage =
    s"""usage: sbt "run <conf-file> [<schema-file>] [$AllowUnknownKeys]""""

  def main(args: Array[String]): Unit =
    run(args.toList) match {
      case Right(output) => println(output)
      case Left(error)   =>
        System.err.println(error)
        sys.exit(1)
    }

  /** 引数を解釈してロードし、表示する文字列（`Right`）かエラーメッセージ（`Left`）を返す。 */
  private[sysctlconf] def run(args: List[String]): Either[String, String] = {
    val (flags, paths) = args.partition(_.startsWith("--"))
    val allowUnknown   = flags.contains(AllowUnknownKeys)
    val loaded         = (paths, flags.filterNot(_ == AllowUnknownKeys)) match {
      case (conf :: Nil, Nil) if !allowUnknown =>
        SysctlConf.load(Paths.get(conf)).left.map(_.message)
      case (conf :: schemaPath :: Nil, Nil) =>
        Schema
          .load(Paths.get(schemaPath))
          .left
          .map(SysctlError.InvalidSchema(_))
          .map(schema => if (allowUnknown) schema.allowUnknownKeys else schema)
          .flatMap(SysctlConf.load(Paths.get(conf), _))
          .left
          .map(_.message)
      case _ => Left(Usage)
    }
    loaded.map(conf => render(conf.tree, indent = 0))
  }

  /** 階層化したMapを、課題の期待値と同じJSON風の形で表示用の文字列にする。 */
  private def render(tree: Map[String, SysctlValue], indent: Int): String =
    if (tree.isEmpty) "{}"
    else {
      val pad = "  " * (indent + 1)
      tree
        .map {
          case (key, SysctlValue.Leaf(value))    => s"$pad${quote(key)}: ${quote(value)}"
          case (key, SysctlValue.Node(children)) =>
            s"$pad${quote(key)}: ${render(children, indent + 1)}"
        }
        .mkString("{\n", ",\n", s"\n${"  " * indent}}")
    }

  private def quote(s: String): String =
    s.flatMap {
      case '"'  => "\\\""
      case '\\' => "\\\\"
      case '\t' => "\\t"
      case c    => c.toString
    }.mkString("\"", "", "\"")
}
