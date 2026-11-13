package sysctlconf

import java.nio.file.Path

/** ロード・パース時のエラー。 */
enum SysctlError {

  /** ファイルを読み込めなかった（存在しない、権限がない、UTF-8でない等）。 */
  case Io(path: Path, cause: Throwable)

  /** `line`行目（1始まり）がsysctl.confの文法に従っていない。 */
  case Syntax(line: Int, reason: SyntaxReason)

  /** `line`行目の`key`が、`definedAt`行目で定義済みのキーと階層上で衝突している。
    *
    * 例: `log = a`と`log.file = b`は、`log`が値と階層の両方になるため同居できない。
    */
  case KeyConflict(line: Int, key: String, definedAt: Int)

  /** スキーマファイル自体をロードできなかった（読み込めない、書式が誤っている等）。 */
  case InvalidSchema(error: SchemaError)

  /** 設定がスキーマに従っていない。`violations`は1件以上で、見つかった不備をすべて含む。 */
  case SchemaViolation(violations: Vector[Violation])

  def message: String = this match {
    case Io(path, cause)                   => s"failed to read $path: $cause"
    case Syntax(line, reason)              => s"line $line: ${reason.message}"
    case KeyConflict(line, key, definedAt) =>
      s"line $line: key '$key' conflicts with the key defined at line $definedAt"
    case InvalidSchema(error)        => s"invalid schema: ${error.message}"
    case SchemaViolation(violations) => violations.map(_.message).mkString("\n")
  }
}

/** [[SysctlError.Syntax]]の理由。 */
enum SyntaxReason(val message: String) {

  /** `=`が無い（`-key`形式の除外行を除く）。 */
  case MissingEquals extends SyntaxReason("expected 'token = value' but '=' was not found")

  /** キーが空（`= 1`や`-`だけの行）。 */
  case EmptyKey extends SyntaxReason("key is empty")

  /** 行頭の`-`が2つ以上ある（`--key = 1`）。 */
  case RepeatedDash extends SyntaxReason("only a single leading '-' is allowed")

  /** キーに空白が含まれる（`a b = 1`や`- key = 1`）。全角スペースなど非ASCIIの空白も対象。 */
  case WhitespaceInKey extends SyntaxReason("key must not contain whitespace")

  /** キーに空のセグメントがある（`a..b`、`.a`、`a.`）。 */
  case EmptySegment extends SyntaxReason("key must not contain an empty segment")
}
