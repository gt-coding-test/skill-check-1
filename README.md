# sysctl-conf

Linuxの[sysctl.conf(5)](https://man7.org/linux/man-pages/man5/sysctl.conf.5.html)と同じ文法のファイルをロードし、Mapに格納するScala 3ライブラリです。

## 使い方

```scala
import java.nio.file.Paths
import sysctlconf.*

SysctlConf.load(Paths.get("app.conf")) match {
  case Right(conf) =>
    conf.tree            // キーを"."で階層化したMap[String, SysctlValue]
    conf.toMap           // フラットなMap[String, String]
    conf.get("log.file") // Some("/var/log/console.log")
  case Left(error) =>
    println(error.message) // 例: "line 3: expected 'token = value' but '=' was not found"
}
```

文字列を直接パースする場合は`SysctlConf.parse(text)`を使います。

## 入力と出力の例

課題の入力例1・2を`SysctlConf.load`でロードしたときに、変数に格納される内容です（同じファイルが`src/test/resources/`にあり、テストで検証しています）。

### 入力例1

入力（`example1.conf`）

```
endpoint = localhost:3000
debug = true
log.file = /var/log/console.log
```

出力（`conf.tree`）

```scala
Map(
  "endpoint" -> Leaf("localhost:3000"),
  "debug"    -> Leaf("true"),
  "log"      -> Node(Map(
    "file" -> Leaf("/var/log/console.log")
  ))
)
```

### 入力例2

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

同じ入力に対する`conf.toMap`（階層化しない形）

```scala
Map(
  "endpoint" -> "localhost:3000",
  "log.file" -> "/var/log/console.log",
  "log.name" -> "default.log"
)
```

## API

| 名前 | 内容 |
| --- | --- |
| `SysctlConf.load(path)` | ファイルをUTF-8で読み込んでパースする。`Either[SysctlError, SysctlConf]` |
| `SysctlConf.parse(text)` | 文字列をパースする。`Either[SysctlError, SysctlConf]` |
| `conf.tree` | キーを`.`で階層化した`Map[String, SysctlValue]`（`Leaf(value)` / `Node(children)`） |
| `conf.toMap` | 階層化しない`Map[String, String]` |
| `conf.get(key)` | ドット区切りのキーで値を引く |
| `conf.entries` | 出現順の全行（キー・値・`-`の有無・行番号） |

例外は投げず、失敗は`SysctlError`（`Io` / `Syntax` / `KeyConflict`）で返します。

## 文法と仕様

sysctl.conf(5)に書かれているもの:

- 1行は`token = value`形式。`=`の前後の空白は任意
- 空行、および`#`か`;`で始まる行は無視
- 値に引用符は不要で、空白を含められる（`kernel.modprobe = /sbin/mod probe`）
- 行頭の`-`は「設定に失敗しても無視する」の意味。キーには含めず`Entry.ignoreErrors`として保持する

manページに明記が無く、このライブラリで決めたもの:

| ケース | 挙動 | 理由 |
| --- | --- | --- |
| 行頭・行末の空白 | 取り除く（インデントされたコメントも無視） | procpsの実装と同じ |
| 値に含まれる`=` `#` `;` | 値の一部として扱う（最初の`=`で分割、行内コメントは無い） | procpsの実装と同じ |
| 値が空（`key =`） | 空文字列として格納 | 汎用の設定ファイルとして「空」を表現できるようにするため |
| `=`が無い行、キーが空 | `Syntax`エラー（行番号つき） | 黙って読み飛ばすと設定ミスに気付けないため |
| キーに空白・空セグメント（`a..b`） | `Syntax`エラー | 階層化したときに意味を持たないため |
| 同じキーの重複 | 後勝ち。`entries`には全て残る | sysctlは上から順に適用するので後の行が有効になる |
| `log = a`と`log.file = b`の同居 | `KeyConflict`エラー | 1つのキーが値と階層の両方になり、Mapに格納できないため |
| 値の型 | すべて`String` | sysctl.confに型は無く、`true`や`3000`の解釈は利用側の責務 |
| 順序 | 定義順を保持 | 出力やデバッグ時にファイルと対応が取りやすい |

対象外: `/`区切りのキー（`net/ipv4/ip_forward`）、キーのglob、値の最大長4096文字（カーネルの/proc由来の制限）のチェック。

## 開発

必要なもの: JDK 21、sbt 1.13（[mise](https://mise.jdx.dev/)を使う場合は`mise install`で揃います）

```bash
sbt test        # テスト
sbt scalafmtAll # フォーマット
sbt check       # CIと同じチェック（フォーマット確認 + テスト）
```

## 構成

```
src/main/scala/sysctlconf/
  SysctlConf.scala   ロード・パース・階層化
  SysctlValue.scala  階層化した値（Leaf / Node）
  SysctlError.scala  エラー型
src/test/scala/      テスト
src/test/resources/  テスト用の設定ファイル（課題の入力例1・2、文法を一通り含むfull-syntax.conf）
```
