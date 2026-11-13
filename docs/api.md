# API

公開している関数とエラーの一覧です。使い方の例は[README](../README.md)、仕様は[spec.md](spec.md)にあります。

## ロード

| 名前 | 内容 |
| --- | --- |
| `SysctlConf.load(path)` | ファイルをUTF-8で読み込んでパースする |
| `SysctlConf.parse(text)` | 文字列をパースする |
| `SysctlConf.load(path, schemaPath)` | 設定とスキーマをファイルからロードして検証する |
| `SysctlConf.load(path, schema)` | ファイルをロードし、ロード済みのスキーマで検証する |
| `SysctlConf.parse(text, schema)` | 文字列をパースし、ロード済みのスキーマで検証する |

戻り値はいずれも`Either[SysctlError, SysctlConf]`です。

## ロードした設定（`SysctlConf`）

| 名前 | 内容 |
| --- | --- |
| `conf.tree` | キーを`.`で階層化した`Map[String, SysctlValue]`（`Leaf(value)` / `Node(children)`） |
| `conf.toMap` | 階層化しない`Map[String, String]` |
| `conf.get(key)` | ドット区切りのキーで値を引く |
| `conf.entries` | 出現順の全設定行（キー・値・`-`の有無・行番号） |
| `conf.exclusions` | `-key`形式の除外行（キー・行番号） |

## スキーマ（`Schema`）

| 名前 | 内容 |
| --- | --- |
| `Schema.load(path)` / `Schema.parse(text)` | スキーマをロードする。`Either[SchemaError, Schema]` |
| `schema.allowUnknownKeys` | スキーマに無いキーを許容するスキーマを返す |
| `schema.validate(conf)` | パース済みの設定を検証する。`Either[Vector[Violation], SysctlConf]` |
| `schema.fields` | スキーマの定義（キー・型・必須かどうか・行番号） |

スキーマに無いキーを許容してロードする例:

```scala
for {
  schema <- Schema.load(Paths.get("app.schema")).left.map(SysctlError.InvalidSchema(_))
  conf   <- SysctlConf.load(Paths.get("app.conf"), schema.allowUnknownKeys)
} yield conf
```

## エラー（`SysctlError`）

| エラー | 内容 |
| --- | --- |
| `Io(path, cause)` | 設定ファイルを読み込めない |
| `Syntax(line, reason)` | 設定ファイルの文法エラー。`reason`は`SyntaxReason`（`MissingEquals` / `EmptyKey` / `RepeatedDash` / `WhitespaceInKey` / `EmptySegment`） |
| `KeyConflict(line, key, definedAt)` | `key`が`definedAt`行目のキーと階層上で衝突している |
| `InvalidSchema(error)` | スキーマファイル自体に不備がある。`error`は`SchemaError`（`Io` / `Syntax` / `DuplicateKey` / `KeyConflict`） |
| `SchemaViolation(violations)` | 設定がスキーマに従っていない。`violations`は`Violation`（`TypeMismatch` / `UnknownKey` / `MissingKey`）の一覧 |

`InvalidSchema`と`SchemaViolation`は、スキーマつきでロードした場合にだけ返ります。
