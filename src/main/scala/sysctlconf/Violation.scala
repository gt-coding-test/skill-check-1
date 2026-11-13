package sysctlconf

/** 設定がスキーマに従っていない箇所。 */
enum Violation {

  /** 設定ファイルの`line`行目の`key`の値`actual`が、型`expected`として解釈できない。 */
  case TypeMismatch(line: Int, key: String, expected: ValueType, actual: String)

  /** 設定ファイルの`line`行目の`key`が、スキーマに定義されていない。 */
  case UnknownKey(line: Int, key: String)

  /** スキーマファイルの`schemaLine`行目で必須とされた`key`が、設定ファイルに無い。 */
  case MissingKey(key: String, schemaLine: Int)

  def message: String = this match {
    case TypeMismatch(line, key, expected, actual) =>
      s"line $line: key '$key' expects ${expected.name} but got '$actual'"
    case UnknownKey(line, key)       => s"line $line: key '$key' is not defined in the schema"
    case MissingKey(key, schemaLine) =>
      s"required key '$key' is missing (required at schema line $schemaLine)"
  }
}
