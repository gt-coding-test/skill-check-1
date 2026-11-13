package sysctlconf

import java.nio.file.Path
import scala.collection.immutable.VectorMap

/** スキーマで指定できる値の型。sysctl.confの値はすべて文字列なので、その文字列が型として解釈できるかを判定する。 */
enum ValueType(val name: String) {

  /** 任意の文字列（空文字列を含む）。 */
  case String extends ValueType("string")

  /** `true`または`false`（小文字のみ）。 */
  case Bool extends ValueType("bool")

  /** 10進の整数（`-`は可、`+`は不可）。64ビット符号付き整数に収まるもの。 */
  case Integer extends ValueType("integer")

  /** 10進の小数（`3`、`-0.5`、`1.5e3`）。整数も受け付ける。 */
  case Float extends ValueType("float")

  def accepts(value: Predef.String): Boolean = this match {
    case String  => true
    case Bool    => value == "true" || value == "false"
    case Integer => value.matches("-?[0-9]+") && value.toLongOption.isDefined
    case Float   =>
      value.matches("-?[0-9]+(\\.[0-9]+)?([eE][+-]?[0-9]+)?") &&
      value.toDoubleOption.exists(!_.isInfinite)
  }
}

/** スキーマの1行分の定義。
  *
  * @param key
  *   設定のキー（`.`区切りに正規化したもの）
  * @param valueType
  *   値の型
  * @param required
  *   必須かどうか。型の後ろに`!`を付けると必須になる。付けなければ省略可
  * @param line
  *   スキーマファイル内の行番号（1始まり）
  */
final case class SchemaField(key: String, valueType: ValueType, required: Boolean, line: Int)

/** パース済みのスキーマ。
  *
  * @param fields
  *   スキーマの定義を出現順に保持する
  * @param unknownKeysAllowed
  *   スキーマに無いキーが設定にあっても許容するか。既定は`false`（エラーにする）
  */
final case class Schema private (fields: Vector[SchemaField], unknownKeysAllowed: Boolean) {

  private lazy val byKey: Map[String, SchemaField] = fields.map(f => f.key -> f).toMap

  /** スキーマに無いキーを許容するスキーマを返す。設定ファイルの一部のキーだけを検証したい場合に使う。 */
  def allowUnknownKeys: Schema = new Schema(fields, unknownKeysAllowed = true)

  /** 設定がスキーマに従っているか検証する。
    *
    *   - スキーマにあるキーの値が、指定された型として解釈できること
    *   - 必須（`!`つき）のキーが設定に存在すること
    *   - スキーマに無いキーが設定に存在しないこと（[[allowUnknownKeys]]で許容できる）
    *
    * 同じキーが複数回書かれている場合は、後勝ちで有効になる値だけを検証する。除外行（`-key`）は検証しない。
    *
    * 従っていれば`conf`をそのまま返す。不備があれば最初の1件で止めず、すべてを`Left`で返す（設定ファイルの行の順、続いて不足しているキーをスキーマの定義順）。
    */
  def validate(conf: SysctlConf): Either[Vector[Violation], SysctlConf] = {
    val effective = conf.entries.foldLeft(VectorMap.empty[String, Entry]) { (acc, entry) =>
      acc.removed(entry.key).updated(entry.key, entry)
    }
    val invalid = effective.values.toVector.flatMap { entry =>
      byKey.get(entry.key) match {
        case None if unknownKeysAllowed => None
        case None                       => Some(Violation.UnknownKey(entry.line, entry.key))
        case Some(field) if !field.valueType.accepts(entry.value) =>
          Some(Violation.TypeMismatch(entry.line, entry.key, field.valueType, entry.value))
        case Some(_) => None
      }
    }
    val missing = fields.collect {
      case field if field.required && !effective.contains(field.key) =>
        Violation.MissingKey(field.key, field.line)
    }
    val violations = invalid ++ missing
    if (violations.isEmpty) Right(conf) else Left(violations)
  }
}

object Schema {

  /** スキーマファイルをUTF-8として読み込んでパースする。 */
  def load(path: Path): Either[SchemaError, Schema] =
    LineSyntax.readFile(path).left.map(SchemaError.Io(path, _)).flatMap(parse)

  /** スキーマをパースする。
    *
    * {{{
    * # コメント
    * endpoint -> string!
    * debug    -> bool
    * log.file -> string
    * retry    -> integer
    * }}}
    *
    *   - 1行は`key -> type`形式。`->`の前後の空白は任意
    *   - 型は`string`、`bool`、`integer`、`float`のいずれか
    *   - 型の後ろに`!`を付けると必須のキーになる。付けなければ省略可で、書かれている場合だけ型を検証する
    *   - 空行、および`#`か`;`で始まる行は無視する（設定ファイルと同じ）
    *   - キーの書き方は設定ファイルと同じ（`/`区切りは`.`区切りに正規化する）
    *
    * 誤記と思われる定義はエラーにする: 同じキーの重複、1つの設定で同時には持てない階層が衝突するキーの組（`log`と`log.file`）、
    * 設定ファイルに書けない`-`で始まるキーと`=`を含むキー。
    *
    * 最初に見つかったエラーを`Left`で返す。
    */
  def parse(input: String): Either[SchemaError, Schema] = {
    val empty: Either[SchemaError, Vector[SchemaField]] = Right(Vector.empty)
    LineSyntax
      .lines(input)
      .foldLeft(empty) { case (acc, (text, line)) =>
        for {
          fields <- acc
          parsed <- parseLine(text, line)
          next   <- parsed match {
            case None        => Right(fields)
            case Some(field) => checkAgainst(fields, field).map(_ => fields :+ field)
          }
        } yield next
      }
      .map(new Schema(_, unknownKeysAllowed = false))
  }

  private val Arrow = "->"

  /** 定義済みのキーとの重複と、階層の衝突を調べる。 */
  private def checkAgainst(
      fields: Vector[SchemaField],
      field: SchemaField
  ): Either[SchemaError, Unit] =
    fields
      .collectFirst {
        case defined if defined.key == field.key =>
          SchemaError.DuplicateKey(field.line, field.key, defined.line)
        case defined
            if LineSyntax.isAncestor(defined.key, field.key) ||
              LineSyntax.isAncestor(field.key, defined.key) =>
          SchemaError.KeyConflict(field.line, field.key, defined.line)
      }
      .toLeft(())

  /** 前後の空白を除いた1行をパースする。空行・コメント行は`Right(None)`。 */
  private def parseLine(text: String, line: Int): Either[SchemaError, Option[SchemaField]] =
    if (LineSyntax.isIgnored(text)) Right(None)
    else {
      val result = text.indexOf(Arrow) match {
        case -1 => Left(SchemaSyntaxReason.MissingArrow)
        case i  =>
          for {
            key   <- parseKey(LineSyntax.stripRight(text.take(i)))
            typed <- parseType(LineSyntax.stripLeft(text.drop(i + Arrow.length)))
            (valueType, required) = typed
          } yield Some(SchemaField(key, valueType, required, line))
      }
      result.left.map(SchemaError.Syntax(line, _))
    }

  /** キーを検査して正規化する。設定ファイルに書けないキー（`-`で始まる、`=`を含む）はここで弾く。 */
  private def parseKey(raw: String): Either[SchemaSyntaxReason, String] =
    if (raw.startsWith("-")) Left(SchemaSyntaxReason.KeyStartsWithDash)
    else if (raw.contains('=')) Left(SchemaSyntaxReason.KeyContainsEquals)
    else LineSyntax.normalizeKey(raw).left.map(SchemaSyntaxReason.InvalidKey(_))

  /** `integer`や`integer!`を、型と必須かどうかの組にする。 */
  private def parseType(text: String): Either[SchemaSyntaxReason, (ValueType, Boolean)] = {
    val required = text.endsWith("!")
    val name     = if (required) LineSyntax.stripRight(text.dropRight(1)) else text
    if (name.isEmpty) Left(SchemaSyntaxReason.MissingType)
    else
      ValueType.values
        .find(_.name == name)
        .map((_, required))
        .toRight(SchemaSyntaxReason.UnknownType(name))
  }
}
