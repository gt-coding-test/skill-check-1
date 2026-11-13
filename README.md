# sysctl-conf

Linuxの[sysctl.conf(5)](https://man7.org/linux/man-pages/man5/sysctl.conf.5.html)と同じ文法のファイルをロードし、Mapに格納するScala 3ライブラリです。スキーマファイルを一緒に渡すと、値の型やキーの過不足を検証できます。

スキルチェック課題との対応は次のとおりです。

| 課題 | 内容 | このREADMEの該当部分 | コード |
| --- | --- | --- | --- |
| 課題1 | ファイルをロードしてMapに格納する | [設定ファイルのロード](#設定ファイルのロード) | 提出時点は`task-1`タグ |
| 課題2 | スキーマファイルで入力値を検証する | [スキーマによる検証](#スキーマによる検証) | 提出時点は`task-2`タグ（`task-1`タグからの差分が課題2の範囲） |

細かい仕様は[docs/spec.md](docs/spec.md)、関数とエラーの一覧は[docs/api.md](docs/api.md)にあります。

## 使い方

```scala
import java.nio.file.Paths
import sysctlconf.*

// 設定ファイルをロードする
SysctlConf.load(Paths.get("app.conf")) match {
  case Right(conf) =>
    conf.tree            // キーを"."で階層化したMap[String, SysctlValue]
    conf.toMap           // フラットなMap[String, String]
    conf.get("log.file") // Some("/var/log/console.log")
  case Left(error) =>
    println(error.message) // 例: "line 3: expected 'token = value' but '=' was not found"
}

// スキーマファイルとともにロードし、スキーマに従っているか検証する
SysctlConf.load(Paths.get("app.conf"), Paths.get("app.schema")) match {
  case Right(conf) => conf.tree // 検証を通った設定
  case Left(error) => println(error.message) // 例: "line 2: key 'debug' expects bool but got 'yes'"
}
```

例外は投げず、失敗はすべて`Either`の`Left`で返します。文字列を直接パースする場合は`SysctlConf.parse(text)`と`SysctlConf.parse(text, schema)`を使います。

### 手元のファイルで試す

ライブラリを呼び出す小さな実行用コード（`Main`）を用意しています。設定ファイルと、任意でスキーマファイルのパスを渡すと、格納された内容かエラーを表示します。

```bash
sbt "run src/test/resources/example1.conf"
sbt "run src/test/resources/example1.conf src/test/resources/example.schema"
sbt "run src/test/resources/example2.conf src/test/resources/example.schema --allow-unknown-keys"
```

1つ目のコマンドの出力

```
{
  "endpoint": "localhost:3000",
  "debug": "true",
  "log": {
    "file": "/var/log/console.log"
  }
}
```

失敗した場合はエラーを標準エラー出力に表示し、終了コード1で終わります。sbt経由で実行しているので、このときsbt自身の`[error] nonzero exit code returned from runner: 1`という行も続けて表示されます。`--allow-unknown-keys`は、スキーマに無いキーを許容する指定です。

## 設定ファイルのロード

sysctl.conf形式のファイルをロードし、キーを`.`で階層化したMapに格納します。値はすべて文字列として保持します。

課題1の入力例2をロードした結果です。入力例1の結果と、階層化しない`conf.toMap`の形は[docs/spec.md](docs/spec.md#ロードの例)にあります。

入力（`example2.conf`）

```
endpoint = localhost:3000
# debug = true
log.file = /var/log/console.log
log.name = default.log
```

出力（`conf.tree`）

```scala
Map(
  "endpoint" -> Leaf("localhost:3000"),
  "log"      -> Node(Map(
    "file" -> Leaf("/var/log/console.log"),
    "name" -> Leaf("default.log")
  ))
)
```

## スキーマによる検証

設定ファイルと一緒にスキーマファイルを渡すと、設定がスキーマに従っているかを検証します。従っていなければ、不備をすべてまとめたエラーを返します。検証を通った設定の中身は、スキーマ無しでロードした場合と同じです。

### スキーマファイルの書式

```
# コメント
endpoint -> string!
debug    -> bool
log.file -> string
retry    -> integer
```

- 1行は`key -> type`形式。`->`の前後の空白は任意
- 型の後ろに`!`を付けると必須のキーになる。付けなければ省略可で、設定に書かれている場合だけ型を検証する
- 空行、および`#`か`;`で始まる行は無視（設定ファイルと同じ）
- キーの書き方は設定ファイルと同じで、階層のあるキーは`log.file`のように完全なキーで書く

課題のスキーマ例（`endpoint -> string`など4行）は、この書式のままロードできます。`!`が無いので、4つのキーはすべて省略可になります。

型は`string`、`bool`（`true` / `false`）、`integer`、`float`の4種類です。受け付ける値の詳細は[docs/spec.md](docs/spec.md#スキーマファイルの書式)にあります。

### 課題のスキーマ例と、課題1の入力例

課題のスキーマ例（`src/test/resources/example.schema`）

```
endpoint -> string
debug -> bool
log.file -> string
retry -> integer
```

このスキーマで課題1の入力例1・2を検証した結果です。

| 入力 | 結果 | 理由 |
| --- | --- | --- |
| 入力例1（`example1.conf`） | 通る | 書かれている3つのキーは型に合っている。`retry`は`!`が無いので省略できる |
| 入力例2（`example2.conf`） | エラー: `line 4: key 'log.name' is not defined in the schema` | `log.name`がスキーマに無い。キーの書き間違いと区別できないので、既定ではエラーにする |
| 入力例2（スキーマに無いキーを許容） | 通る | `schema.allowUnknownKeys`、または`sbt run`の`--allow-unknown-keys`を指定した場合 |

### 不備のある設定の例

スキーマ（`src/test/resources/strict.schema`）

```
# 必須のキーには型の後ろに!を付ける
endpoint -> string!
debug    -> bool
log.file -> string
retry    -> integer!
```

入力（`src/test/resources/invalid.conf`）

```
endpoint = localhost:3000
debug = yes
log.file = /var/log/console.log
log.name = default.log
```

出力: `Left(SchemaViolation(...))`。`error.message`は次のとおり

```
line 2: key 'debug' expects bool but got 'yes'
line 4: key 'log.name' is not defined in the schema
required key 'retry' is missing (required at schema line 5)
```

## 主な設計判断

課題文に書かれていない点のうち、迷ったものと決めた理由です。仕様の全体は[docs/spec.md](docs/spec.md)にあります。

### 設定ファイルのロード

| 決めたこと | 理由 |
| --- | --- |
| 値はすべて文字列で保持する | sysctl.confに型は無い。型を確かめたい場合はスキーマを使う |
| manページに無い文法は、procpsのsysctl(8)の実装に合わせる | `=`の無い`-key`（除外行）や`/`区切りのキーを含む、実在の設定ファイルも読めるようにしたい |
| `=`の無い行はエラーにする | sysctl(8)は警告して続行するが、読み飛ばすと設定ミスに気付けない |
| `log = a`と`log.file = b`の同居はエラーにする | 1つのキーが値と階層の両方になり、Mapに格納できない |

### スキーマによる検証

| 決めたこと | 理由 |
| --- | --- |
| スキーマのキーは既定で省略可、`!`を付けると必須 | 課題のスキーマ例の`retry`は課題1の入力例に無い。書いただけで必須になると、例同士が通らなくなる |
| スキーマに無いキーは既定でエラー | キーの書き間違いを見つけたい。一部のキーだけ検証したい場合は`schema.allowUnknownKeys`で許容できる |
| 検証のエラーは全件を返す | 文法エラーは設定の内容を確定できないので最初の1件で止めるが、検証は内容が確定した後なので、まとめて返せる |
| 誤記と思われるスキーマはスキーマのエラーにする | キーの重複や、1つの設定で同時には持てない`log`と`log.file`の両方の定義は、どちらかが誤記の可能性が高い。検証が素通りしたり、設定側のエラーとして報告されたりすると原因が分かりにくい |
| 課題1のAPIと格納形式は変えない | 検証はスキーマを渡す`load`と`parse`として足した。検証後の値も文字列のまま |

## 開発

必要なもの: JDK 21、sbt 1.13（[mise](https://mise.jdx.dev/)を使う場合は`mise install`で揃います）

```bash
sbt test        # テスト
sbt scalafmtAll # フォーマット
sbt check       # CIと同じチェック（フォーマット確認 + テスト）
sbt "run <設定ファイル> [<スキーマファイル>]" # 手元のファイルで試す
```

## 構成

```
src/main/scala/sysctlconf/
  SysctlConf.scala   ロード・パース・階層化
  SysctlValue.scala  階層化した値（Leaf / Node）
  SysctlError.scala  ロード時のエラー型
  Schema.scala       スキーマのロード・パースと検証
  SchemaError.scala  スキーマファイルのエラー型
  Violation.scala    検証で見つかった不備
  LineSyntax.scala   設定とスキーマに共通する、行とキーの扱い（内部用）
  Main.scala         手元のファイルで試すための実行用コード
src/test/scala/      テスト
src/test/resources/  テスト用のファイル
  example1.conf, example2.conf  課題1の入力例1・2
  full-syntax.conf              文法を一通り含む設定
  example.schema                課題2のスキーマ例
  with-retry.conf               課題2のスキーマ例の4つのキーをすべて持つ設定
  strict.schema, invalid.conf   必須つきのスキーマと、不備のある設定（READMEの例）
docs/
  spec.md            文法と検証の細かい仕様
  api.md             関数とエラーの一覧
```
