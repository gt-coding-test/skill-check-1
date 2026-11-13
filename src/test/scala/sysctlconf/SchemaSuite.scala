package sysctlconf

import java.nio.file.{Files, Path, Paths}

class SchemaSuite extends munit.FunSuite {

  private def resource(name: String): Path =
    Paths.get(getClass.getClassLoader.getResource(name).toURI)

  private def schema(input: String): Schema =
    Schema.parse(input).fold(e => fail(e.message), identity)

  private def conf(input: String): SysctlConf =
    SysctlConf.parse(input).fold(e => fail(e.message), identity)

  private def withTempFile[A](bytes: Array[Byte])(body: Path => A): A = {
    val file = Files.createTempFile("sysctl", ".tmp")
    try {
      Files.write(file, bytes)
      body(file)
    } finally Files.delete(file)
  }

  private def withTempFile[A](content: String)(body: Path => A): A =
    withTempFile(content.getBytes("UTF-8"))(body)

  // --- 課題の例 -------------------------------------------------------------

  test("課題のスキーマ例をロードできる（!が無いのですべて省略可）") {
    val loaded = Schema.load(resource("example.schema")).fold(e => fail(e.message), identity)
    assertEquals(
      loaded.fields,
      Vector(
        SchemaField("endpoint", ValueType.String, required = false, line = 1),
        SchemaField("debug", ValueType.Bool, required = false, line = 2),
        SchemaField("log.file", ValueType.String, required = false, line = 3),
        SchemaField("retry", ValueType.Integer, required = false, line = 4)
      )
    )
    assertEquals(loaded.unknownKeysAllowed, false)
  }

  test("課題のスキーマ例に従っている設定ファイルは、スキーマとともにロードできる") {
    val loaded = SysctlConf.load(resource("with-retry.conf"), resource("example.schema"))
    assertEquals(
      loaded.map(_.toMap),
      Right(
        Map(
          "endpoint" -> "localhost:3000",
          "debug"    -> "true",
          "log.file" -> "/var/log/console.log",
          "retry"    -> "3"
        )
      )
    )
  }

  test("課題1の入力例1は、課題のスキーマ例に従っている（retryは省略可）") {
    val withSchema = SysctlConf.load(resource("example1.conf"), resource("example.schema"))
    assertEquals(withSchema, SysctlConf.load(resource("example1.conf")))
    assert(withSchema.isRight)
  }

  test("課題1の入力例2は、スキーマに無いlog.nameがあるのでエラー") {
    assertEquals(
      SysctlConf.load(resource("example2.conf"), resource("example.schema")),
      Left(
        SysctlError.SchemaViolation(Vector(Violation.UnknownKey(line = 4, key = "log.name")))
      )
    )
  }

  test("課題1の入力例2も、スキーマに無いキーを許容すれば通る") {
    val relaxed = Schema.load(resource("example.schema")).map(_.allowUnknownKeys)
    val result  = relaxed.left
      .map(SysctlError.InvalidSchema(_))
      .flatMap(SysctlConf.load(resource("example2.conf"), _))
    assert(result.isRight)
  }

  test("READMEの「不備のある設定の例」は、3種類の不備をすべて報告する") {
    val result = SysctlConf.load(resource("invalid.conf"), resource("strict.schema"))
    assertEquals(
      result,
      Left(
        SysctlError.SchemaViolation(
          Vector(
            Violation.TypeMismatch(line = 2, key = "debug", ValueType.Bool, actual = "yes"),
            Violation.UnknownKey(line = 4, key = "log.name"),
            Violation.MissingKey(key = "retry", schemaLine = 5)
          )
        )
      )
    )
    assertEquals(
      result.left.map(_.message),
      Left(
        """line 2: key 'debug' expects bool but got 'yes'
          |line 4: key 'log.name' is not defined in the schema
          |required key 'retry' is missing (required at schema line 5)""".stripMargin
      )
    )
  }

  // --- スキーマの書式 -------------------------------------------------------

  test("空行とコメント行を無視し、->の前後の空白は任意") {
    val parsed = schema("# c\n\n; c\n  a->string\n\tb.c   ->   bool  \n")
    assertEquals(
      parsed.fields,
      Vector(
        SchemaField("a", ValueType.String, required = false, line = 4),
        SchemaField("b.c", ValueType.Bool, required = false, line = 5)
      )
    )
  }

  test("4種類の型を指定できる") {
    assertEquals(
      schema("a -> string\nb -> bool\nc -> integer\nd -> float").fields.map(_.valueType),
      Vector(ValueType.String, ValueType.Bool, ValueType.Integer, ValueType.Float)
    )
  }

  test("型の後ろに!を付けると必須になり、付けなければ省略可") {
    assertEquals(
      schema("a -> integer!\nb -> integer !\nc -> integer").fields.map(f => f.key -> f.required),
      Vector("a" -> true, "b" -> true, "c" -> false)
    )
  }

  test("/区切りのキーは設定ファイルと同じく.区切りに正規化する") {
    assertEquals(
      schema("net/ipv4/ip_forward -> integer").fields.map(_.key),
      Vector("net.ipv4.ip_forward")
    )
  }

  test("CRLF改行と先頭のBOMを扱える") {
    assertEquals(schema("\uFEFFa -> string\r\nb -> bool\r\n").fields.map(_.key), Vector("a", "b"))
  }

  test("空のスキーマは定義なしになる") {
    assertEquals(schema("").fields, Vector.empty)
  }

  // --- スキーマのエラー -----------------------------------------------------

  private def syntaxError(line: Int, reason: SchemaSyntaxReason): Either[SchemaError, Schema] =
    Left(SchemaError.Syntax(line, reason))

  test("->が無い行は行番号つきのSyntaxエラー") {
    val result = Schema.parse("a -> string\n\nb = bool")
    assertEquals(result, syntaxError(3, SchemaSyntaxReason.MissingArrow))
    assertEquals(
      result.left.map(_.message),
      Left("line 3: expected 'key -> type' but '->' was not found")
    )
  }

  test("キーが不正ならエラー") {
    val cases = List(
      "-> string"      -> SyntaxReason.EmptyKey,
      "a b -> string"  -> SyntaxReason.WhitespaceInKey,
      "a..b -> string" -> SyntaxReason.EmptySegment
    )
    cases.foreach { case (input, reason) =>
      assertEquals(
        Schema.parse(input),
        syntaxError(1, SchemaSyntaxReason.InvalidKey(reason)),
        input
      )
    }
    assertEquals(
      Schema.parse("a b -> string").left.map(_.message),
      Left("line 1: key must not contain whitespace")
    )
  }

  test("-で始まるキーは、一致する設定を書けないのでエラー") {
    List("-debug -> bool", "--a -> string").foreach { input =>
      assertEquals(Schema.parse(input), syntaxError(1, SchemaSyntaxReason.KeyStartsWithDash), input)
    }
    assertEquals(
      Schema.parse("-debug -> bool").left.map(_.message),
      Left("line 1: key must not start with '-'")
    )
  }

  test("=を含むキーは、一致する設定を書けないのでエラー") {
    val result = Schema.parse("a=b -> string")
    assertEquals(result, syntaxError(1, SchemaSyntaxReason.KeyContainsEquals))
    assertEquals(result.left.map(_.message), Left("line 1: key must not contain '='"))
  }

  test("型が無ければエラー") {
    List("a ->", "a -> !").foreach { input =>
      assertEquals(Schema.parse(input), syntaxError(1, SchemaSyntaxReason.MissingType), input)
    }
    assertEquals(Schema.parse("a ->").left.map(_.message), Left("line 1: type is missing"))
  }

  test("知らない型はエラー（大文字小文字や別名、?や!!も受け付けない）") {
    val cases = List(
      "int"         -> "int",
      "boolean"     -> "boolean",
      "String"      -> "String",
      "integer?"    -> "integer?",
      "integer!!"   -> "integer!",
      "string list" -> "string list"
    )
    cases.foreach { case (written, reported) =>
      assertEquals(
        Schema.parse(s"a -> $written"),
        syntaxError(1, SchemaSyntaxReason.UnknownType(reported)),
        written
      )
    }
    assertEquals(
      Schema.parse("a -> int").left.map(_.message),
      Left("line 1: unknown type 'int' (expected one of: string, bool, integer, float)")
    )
  }

  test("同じキーを2回定義するとDuplicateKey（正規化後のキーで判定する）") {
    val result = Schema.parse("a.b -> string\nc -> bool\na/b -> integer")
    assertEquals(result, Left(SchemaError.DuplicateKey(line = 3, key = "a.b", definedAt = 1)))
    assertEquals(
      result.left.map(_.message),
      Left("line 3: key 'a.b' is already defined at line 1")
    )
  }

  test("階層が衝突するキーの組は、両方を持つ設定を書けないのでKeyConflict") {
    val result = Schema.parse("log -> string\nx -> bool\nlog.file -> string")
    assertEquals(result, Left(SchemaError.KeyConflict(line = 3, key = "log.file", definedAt = 1)))
    assertEquals(
      result.left.map(_.message),
      Left("line 3: key 'log.file' conflicts with the key defined at line 1")
    )
    assertEquals(
      Schema.parse("log.file.name -> string\nlog -> string"),
      Left(SchemaError.KeyConflict(line = 2, key = "log", definedAt = 1))
    )
  }

  test("前方一致するだけで階層の関係に無いキーは衝突しない") {
    assert(Schema.parse("log -> string\nlogs.file -> string\nlog_file -> string").isRight)
  }

  test("存在しないスキーマファイルはIoエラー") {
    val missing = Paths.get("does-not-exist.schema")
    Schema.load(missing) match {
      case Left(SchemaError.Io(path, _)) => assertEquals(path, missing)
      case other                         => fail(s"unexpected: $other")
    }
  }

  test("BOM付きのUTF-8のスキーマファイルをロードできる") {
    val bytes =
      Array[Byte](0xef.toByte, 0xbb.toByte, 0xbf.toByte) ++ "a -> string\n".getBytes("UTF-8")
    withTempFile(bytes) { file =>
      assertEquals(Schema.load(file).map(_.fields.map(_.key)), Right(Vector("a")))
    }
  }

  test("UTF-8として不正なスキーマファイルはIoエラー") {
    withTempFile(Array[Byte](0x61, 0x2d, 0x3e, 0xff.toByte)) { file =>
      assert(Schema.load(file).left.exists(_.isInstanceOf[SchemaError.Io]))
    }
  }

  // --- 型の判定 -------------------------------------------------------------

  private def checkType(valueType: ValueType)(valid: String*)(invalid: String*): Unit = {
    valid.foreach(v => assert(valueType.accepts(v), s"'$v' should be ${valueType.name}"))
    invalid.foreach(v => assert(!valueType.accepts(v), s"'$v' should not be ${valueType.name}"))
  }

  test("stringは空文字列を含む任意の文字列") {
    checkType(ValueType.String)("", "localhost:3000", "a b", "true", "1")()
  }

  test("boolは小文字のtrueとfalseのみ") {
    checkType(ValueType.Bool)("true", "false")("", "True", "FALSE", "1", "0", "yes", "no", "true ")
  }

  test("integerは64ビットに収まる10進の整数") {
    checkType(ValueType.Integer)(
      "0",
      "3",
      "-1",
      "007",
      "9223372036854775807",
      "-9223372036854775808"
    )("", "+1", "1.0", "1e3", "abc", "1 000", "1_000", "0x10", "9223372036854775808", "-")
  }

  test("floatは10進の小数。整数も受け付ける") {
    checkType(ValueType.Float)("0", "3", "-0.5", "1.5e3", "1E-3", "10.25")(
      "",
      ".5",
      "1.",
      "+1.0",
      "NaN",
      "Infinity",
      "1e999",
      "1f",
      "0x1p3",
      "abc"
    )
  }

  // --- 検証 -----------------------------------------------------------------

  private val exampleSchema = schema("endpoint -> string!\ndebug -> bool!\nretry -> integer")

  test("スキーマに従っていれば設定をそのまま返す") {
    val input = conf("endpoint = localhost:3000\ndebug = false\nretry = 3")
    assertEquals(exampleSchema.validate(input), Right(input))
  }

  test("!の無いキーは省略できる") {
    assert(exampleSchema.validate(conf("endpoint = x\ndebug = true")).isRight)
  }

  test("型として解釈できない値は行番号つきのTypeMismatch") {
    val result = exampleSchema.validate(conf("endpoint = x\ndebug = yes\nretry = 3"))
    assertEquals(
      result,
      Left(Vector(Violation.TypeMismatch(line = 2, key = "debug", ValueType.Bool, actual = "yes")))
    )
    assertEquals(
      result.left.map(_.map(_.message)),
      Left(Vector("line 2: key 'debug' expects bool but got 'yes'"))
    )
  }

  test("省略可のキーも、書かれていれば型を検証する") {
    assertEquals(
      exampleSchema.validate(conf("endpoint = x\ndebug = true\nretry = many")),
      Left(Vector(Violation.TypeMismatch(3, "retry", ValueType.Integer, "many")))
    )
  }

  test("スキーマに無いキーはUnknownKey") {
    assertEquals(
      exampleSchema.validate(conf("endpoint = x\ndebug = true\nretrys = 3")),
      Left(Vector(Violation.UnknownKey(line = 3, key = "retrys")))
    )
  }

  test("allowUnknownKeysでスキーマに無いキーを許容できる。型と必須の検証は変わらない") {
    val relaxed = exampleSchema.allowUnknownKeys
    assertEquals(relaxed.unknownKeysAllowed, true)
    assertEquals(relaxed.fields, exampleSchema.fields)
    assert(relaxed.validate(conf("endpoint = x\ndebug = true\nretrys = 3")).isRight)
    assertEquals(
      relaxed.validate(conf("extra = 1\ndebug = oops")),
      Left(
        Vector(
          Violation.TypeMismatch(2, "debug", ValueType.Bool, "oops"),
          Violation.MissingKey("endpoint", schemaLine = 1)
        )
      )
    )
  }

  test("必須のキーが無ければ、スキーマの行番号つきのMissingKey") {
    val result = exampleSchema.validate(conf("retry = 3"))
    assertEquals(
      result,
      Left(
        Vector(
          Violation.MissingKey("endpoint", schemaLine = 1),
          Violation.MissingKey("debug", schemaLine = 2)
        )
      )
    )
    assertEquals(
      result.left.map(_.map(_.message)),
      Left(
        Vector(
          "required key 'endpoint' is missing (required at schema line 1)",
          "required key 'debug' is missing (required at schema line 2)"
        )
      )
    )
  }

  test("不備は最初の1件で止めず、すべて返す（設定の行の順、続いて不足しているキー）") {
    assertEquals(
      exampleSchema.validate(conf("extra = 1\nretry = x\ndebug = 1")),
      Left(
        Vector(
          Violation.UnknownKey(1, "extra"),
          Violation.TypeMismatch(2, "retry", ValueType.Integer, "x"),
          Violation.TypeMismatch(3, "debug", ValueType.Bool, "1"),
          Violation.MissingKey("endpoint", schemaLine = 1)
        )
      )
    )
  }

  test("同じキーが複数あれば、後勝ちで有効になる値だけを検証する") {
    assert(exampleSchema.validate(conf("endpoint = x\ndebug = oops\ndebug = true")).isRight)
    assertEquals(
      exampleSchema.validate(conf("endpoint = x\ndebug = true\ndebug = oops")),
      Left(Vector(Violation.TypeMismatch(3, "debug", ValueType.Bool, "oops")))
    )
  }

  test("行頭に-の付いた設定も検証し、除外行は検証しない") {
    assert(exampleSchema.validate(conf("-endpoint = x\ndebug = true\n-not.in.schema")).isRight)
    assertEquals(
      exampleSchema.validate(conf("endpoint = x\n-debug = oops")),
      Left(Vector(Violation.TypeMismatch(2, "debug", ValueType.Bool, "oops")))
    )
  }

  test("階層のあるキーはドット区切りの完全なキーで照合する") {
    val nested = schema("log.file -> string\nlog.level -> integer")
    assert(nested.validate(conf("log.file = a\nlog.level = 2")).isRight)
    assertEquals(
      nested.validate(conf("log.file = a\nlog.level = 2\nlog.name = b")),
      Left(Vector(Violation.UnknownKey(3, "log.name")))
    )
  }

  test("設定とスキーマで区切り文字の書き方が違っても、正規化後のキーで照合する") {
    assert(schema("log.file -> string!").validate(conf("log/file = y")).isRight)
    assert(schema("log/file -> string!").validate(conf("log.file = y")).isRight)
  }

  test("空のスキーマでは、設定のキーがすべてUnknownKeyになる") {
    assertEquals(
      schema("").validate(conf("a = 1\nb = 2")),
      Left(Vector(Violation.UnknownKey(1, "a"), Violation.UnknownKey(2, "b")))
    )
    assert(schema("").allowUnknownKeys.validate(conf("a = 1\nb = 2")).isRight)
  }

  test("空の設定は、必須のキーが無ければ通り、あればMissingKeyになる") {
    assert(schema("a -> string\nb -> bool").validate(conf("")).isRight)
    assertEquals(
      schema("a -> string\nb -> bool!").validate(conf("")),
      Left(Vector(Violation.MissingKey("b", schemaLine = 2)))
    )
  }

  // --- スキーマつきのロード -------------------------------------------------

  test("スキーマファイルに不備があればInvalidSchema") {
    withTempFile("endpoint -> text\n") { schemaFile =>
      val result = SysctlConf.load(resource("example1.conf"), schemaFile)
      assertEquals(
        result,
        Left(
          SysctlError.InvalidSchema(SchemaError.Syntax(1, SchemaSyntaxReason.UnknownType("text")))
        )
      )
      assertEquals(
        result.left.map(_.message),
        Left(
          "invalid schema: line 1: unknown type 'text' (expected one of: string, bool, integer, float)"
        )
      )
    }
  }

  test("スキーマファイルが存在しなければInvalidSchema") {
    val result = SysctlConf.load(resource("example1.conf"), Paths.get("does-not-exist.schema"))
    assert(result.left.exists {
      case SysctlError.InvalidSchema(_: SchemaError.Io) => true
      case _                                            => false
    })
  }

  test("設定ファイルの文法エラーは、スキーマの検証より先に報告する") {
    withTempFile("a -> string\n") { schemaFile =>
      withTempFile("broken line\n") { confFile =>
        assertEquals(
          SysctlConf.load(confFile, schemaFile),
          Left(SysctlError.Syntax(1, SyntaxReason.MissingEquals))
        )
      }
    }
  }

  test("設定とスキーマの両方に不備があれば、スキーマの不備を先に報告する") {
    withTempFile("a -> text\n") { schemaFile =>
      val missingConf = Paths.get("does-not-exist.conf")
      assertEquals(
        SysctlConf.load(missingConf, schemaFile),
        Left(
          SysctlError.InvalidSchema(SchemaError.Syntax(1, SchemaSyntaxReason.UnknownType("text")))
        )
      )
    }
    withTempFile("a -> string\n") { schemaFile =>
      val missingConf = Paths.get("does-not-exist.conf")
      assert(SysctlConf.load(missingConf, schemaFile).left.exists(_.isInstanceOf[SysctlError.Io]))
    }
  }

  test("ロード済みのスキーマを渡して検証することもできる") {
    val loaded = Schema.load(resource("strict.schema")).fold(e => fail(e.message), identity)
    assert(SysctlConf.load(resource("with-retry.conf"), loaded).isRight)
    assert(SysctlConf.load(resource("example1.conf"), loaded).isLeft)
  }

  test("文字列をパースして検証することもできる") {
    assertEquals(
      SysctlConf.parse("endpoint = x\ndebug = true", exampleSchema).map(_.toMap),
      Right(Map("endpoint" -> "x", "debug" -> "true"))
    )
    assertEquals(
      SysctlConf.parse("endpoint = x\ndebug = 1", exampleSchema),
      Left(
        SysctlError.SchemaViolation(Vector(Violation.TypeMismatch(2, "debug", ValueType.Bool, "1")))
      )
    )
    assertEquals(
      SysctlConf.parse("broken", exampleSchema),
      Left(SysctlError.Syntax(1, SyntaxReason.MissingEquals))
    )
  }
}
