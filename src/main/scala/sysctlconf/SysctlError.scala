package sysctlconf

import java.nio.file.Path

/** ロード・パース時のエラー。 */
enum SysctlError {

  /** ファイルを読み込めなかった（存在しない、権限がない、UTF-8でない等）。 */
  case Io(path: Path, cause: Throwable)

  /** `line`行目（1始まり）がsysctl.confの文法に従っていない。 */
  case Syntax(line: Int, reason: String)

  /** `line`行目の`key`が、既に定義済みのキーと階層上で衝突している。
    *
    * 例: `log = a`と`log.file = b`は、`log`が値と階層の両方になるため同居できない。
    */
  case KeyConflict(line: Int, key: String)

  def message: String = this match {
    case Io(path, cause)        => s"failed to read $path: $cause"
    case Syntax(line, reason)   => s"line $line: $reason"
    case KeyConflict(line, key) =>
      s"line $line: key '$key' conflicts with a previously defined key"
  }
}
