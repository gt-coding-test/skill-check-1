package sysctlconf

import java.nio.file.Path

/** スキーマファイルのロード・パース時のエラー。 */
enum SchemaError {

  /** スキーマファイルを読み込めなかった（存在しない、権限がない、UTF-8でない等）。 */
  case Io(path: Path, cause: Throwable)

  /** `line`行目（1始まり）がスキーマの書式に従っていない。 */
  case Syntax(line: Int, reason: SchemaSyntaxReason)

  /** `line`行目の`key`が、`definedAt`行目で定義済み。 */
  case DuplicateKey(line: Int, key: String, definedAt: Int)

  /** `line`行目の`key`が、`definedAt`行目で定義済みのキーと階層上で衝突している。
    *
    * 例: `log`と`log.file`は1つの設定で同時には持てないので、どちらかが誤記の可能性が高い。必須（`!`）かどうかに関係なくエラーにする。
    */
  case KeyConflict(line: Int, key: String, definedAt: Int)

  def message: String = this match {
    case Io(path, cause)                    => s"failed to read $path: $cause"
    case Syntax(line, reason)               => s"line $line: ${reason.message}"
    case DuplicateKey(line, key, definedAt) =>
      s"line $line: key '$key' is already defined at line $definedAt"
    case KeyConflict(line, key, definedAt) =>
      s"line $line: key '$key' conflicts with the key defined at line $definedAt"
  }
}

/** [[SchemaError.Syntax]]の理由。 */
enum SchemaSyntaxReason {

  /** `->`が無い。 */
  case MissingArrow

  /** キーが`-`で始まる。設定ファイルの行頭の`-`はキーの一部ではないので、このキーに一致する設定は書けない。 */
  case KeyStartsWithDash

  /** キーに`=`が含まれる。設定ファイルは最初の`=`でキーと値に分けるので、このキーに一致する設定は書けない。 */
  case KeyContainsEquals

  /** キーが不正（空、空白を含む、空のセグメントがある）。 */
  case InvalidKey(reason: SyntaxReason)

  /** `->`の後ろに型が無い（`key ->`や`key -> !`）。 */
  case MissingType

  /** 型の名前が`string`、`bool`、`integer`、`float`のいずれでもない。 */
  case UnknownType(name: String)

  def message: String = this match {
    case MissingArrow       => "expected 'key -> type' but '->' was not found"
    case KeyStartsWithDash  => "key must not start with '-'"
    case KeyContainsEquals  => "key must not contain '='"
    case InvalidKey(reason) => reason.message
    case MissingType        => "type is missing"
    case UnknownType(name)  =>
      s"unknown type '$name' (expected one of: ${ValueType.values.map(_.name).mkString(", ")})"
  }
}
