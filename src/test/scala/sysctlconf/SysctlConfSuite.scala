package sysctlconf

import java.nio.file.{Files, Path, Paths}
import sysctlconf.SysctlValue.{Leaf, Node}

class SysctlConfSuite extends munit.FunSuite {

  private def resource(name: String): Path =
    Paths.get(getClass.getClassLoader.getResource(name).toURI)

  private def parsed(input: String): SysctlConf =
    SysctlConf.parse(input).fold(e => fail(e.message), identity)

  // --- 課題の入力例 ---------------------------------------------------------

  test("入力例1: ファイルをロードして階層化されたMapに格納する") {
    val conf = SysctlConf.load(resource("example1.conf")).fold(e => fail(e.message), identity)
    assertEquals(
      conf.tree,
      Map(
        "endpoint" -> Leaf("localhost:3000"),
        "debug"    -> Leaf("true"),
        "log"      -> Node(Map("file" -> Leaf("/var/log/console.log")))
      )
    )
  }

  test("入力例2: コメント行を無視し、同じ親を持つキーをまとめる") {
    val conf = SysctlConf.load(resource("example2.conf")).fold(e => fail(e.message), identity)
    assertEquals(
      conf.tree,
      Map(
        "endpoint" -> Leaf("localhost:3000"),
        "log"      -> Node(
          Map("file" -> Leaf("/var/log/console.log"), "name" -> Leaf("default.log"))
        )
      )
    )
  }

  test("文法を一通り含むファイルをロードできる") {
    val conf = SysctlConf.load(resource("full-syntax.conf")).fold(e => fail(e.message), identity)
    assertEquals(
      conf.tree,
      Map(
        "kernel" -> Node(
          Map("domainname" -> Leaf("override.example.com"), "modprobe" -> Leaf("/sbin/mod probe"))
        ),
        "net" -> Node(
          Map(
            "ipv4" -> Node(
              Map("ip_local_reserved_ports" -> Leaf("8080,9148"), "ip_forward" -> Leaf("1"))
            ),
            "ipv6" -> Node(
              Map("conf" -> Node(Map("all" -> Node(Map("disable_ipv6" -> Leaf("1"))))))
            )
          )
        ),
        "app" -> Node(Map("options" -> Leaf("a=1,b=2 # not a comment"), "empty" -> Leaf("")))
      )
    )
    assertEquals(
      conf.entries.filter(_.ignoreErrors).map(_.key),
      Vector("net.ipv6.conf.all.disable_ipv6")
    )
  }

  // --- sysctl.conf(5)の文法 ------------------------------------------------

  test("manページのEXAMPLESをパースできる") {
    val conf = parsed(
      """#
        |#
        |kernel.domainname = example.com
        |; A value containing a space is written to the sysctl.
        |kernel.modprobe = /sbin/mod probe
        |# A list of items for the value
        |net.ipv4.ip_local_reserved_ports=8080,9148
        |""".stripMargin
    )
    assertEquals(
      conf.toMap,
      Map(
        "kernel.domainname"                -> "example.com",
        "kernel.modprobe"                  -> "/sbin/mod probe",
        "net.ipv4.ip_local_reserved_ports" -> "8080,9148"
      )
    )
  }

  test("空行と、#または;で始まる行は無視する（行頭に空白があっても）") {
    val conf = parsed("\n   \n# c\n; c\n  # indented\n\t; indented\na = 1\n")
    assertEquals(conf.toMap, Map("a" -> "1"))
  }

  test("キーと値の前後の空白は取り除き、値の中の空白は保持する") {
    assertEquals(parsed("  a.b   =   x  y  \t").toMap, Map("a.b" -> "x  y"))
  }

  test("=の前後に空白が無くてもよい") {
    assertEquals(parsed("a=1").toMap, Map("a" -> "1"))
  }

  test("最初の=で分割するので、値は=を含められる") {
    assertEquals(parsed("opts = a=1,b=2").toMap, Map("opts" -> "a=1,b=2"))
  }

  test("値の途中の#や;はコメントではなく値の一部") {
    assertEquals(parsed("a = b # c ; d").toMap, Map("a" -> "b # c ; d"))
  }

  test("値は空でもよい") {
    assertEquals(parsed("a =").toMap, Map("a" -> ""))
  }

  test("行頭の-はキーに含めずignoreErrorsとして保持する") {
    val conf = parsed("-a.b = 1\n  - c = 2\nd = 3")
    assertEquals(
      conf.entries,
      Vector(
        Entry("a.b", "1", ignoreErrors = true, line = 1),
        Entry("c", "2", ignoreErrors = true, line = 2),
        Entry("d", "3", ignoreErrors = false, line = 3)
      )
    )
    assertEquals(
      conf.tree,
      Map("a" -> Node(Map("b" -> Leaf("1"))), "c" -> Leaf("2"), "d" -> Leaf("3"))
    )
  }

  test("CRLF改行と先頭のBOMを扱える") {
    assertEquals(parsed("﻿a = 1\r\nb = 2\r\n").toMap, Map("a" -> "1", "b" -> "2"))
  }

  test("空の入力は空の設定になる") {
    val conf = parsed("")
    assertEquals(conf.entries, Vector.empty)
    assertEquals(conf.tree, Map.empty)
  }

  // --- 格納の仕様 -----------------------------------------------------------

  test("3階層以上のキーも階層化する") {
    assertEquals(
      parsed("a.b.c = 1\na.b.d = 2\na.e = 3").tree,
      Map("a" -> Node(Map("b" -> Node(Map("c" -> Leaf("1"), "d" -> Leaf("2"))), "e" -> Leaf("3"))))
    )
  }

  test("定義順を保持する") {
    val conf = parsed("z = 1\na = 2\nm.b = 3\nm.a = 4")
    assertEquals(conf.tree.keys.toList, List("z", "a", "m"))
    assertEquals(conf.toMap.keys.toList, List("z", "a", "m.b", "m.a"))
  }

  test("同じキーが複数あれば後勝ち。entriesには全て残る") {
    val conf = parsed("a.b = 1\na.b = 2")
    assertEquals(conf.tree, Map("a" -> Node(Map("b" -> Leaf("2")))))
    assertEquals(conf.get("a.b"), Some("2"))
    assertEquals(conf.entries.map(_.value), Vector("1", "2"))
  }

  test("getはドット区切りのキーで値を引く") {
    val conf = parsed("log.file = x")
    assertEquals(conf.get("log.file"), Some("x"))
    assertEquals(conf.get("log"), None)
    assertEquals(conf.get("nope"), None)
  }

  // --- エラー ---------------------------------------------------------------

  test("=が無い行は行番号つきのSyntaxエラー") {
    assertEquals(
      SysctlConf.parse("a = 1\n\nbroken line").left.map(_.message),
      Left("line 3: expected 'token = value' but '=' was not found")
    )
  }

  test("キーが空ならエラー") {
    assert(SysctlConf.parse("= 1").left.exists(_.isInstanceOf[SysctlError.Syntax]))
    assert(SysctlConf.parse("- = 1").left.exists(_.isInstanceOf[SysctlError.Syntax]))
  }

  test("キーに空のセグメントや空白があればエラー") {
    List(".a = 1", "a. = 1", "a..b = 1", "a b = 1").foreach { input =>
      assert(SysctlConf.parse(input).left.exists(_.isInstanceOf[SysctlError.Syntax]), input)
    }
  }

  test("値を持つキーの下に階層を作ろうとするとKeyConflict") {
    assertEquals(
      SysctlConf.parse("log = a\nlog.file = b"),
      Left(SysctlError.KeyConflict(2, "log.file"))
    )
  }

  test("階層を持つキーに値を入れようとするとKeyConflict") {
    assertEquals(SysctlConf.parse("log.file = b\nlog = a"), Left(SysctlError.KeyConflict(2, "log")))
  }

  test("存在しないファイルはIoエラー") {
    val missing = Paths.get("does-not-exist.conf")
    SysctlConf.load(missing) match {
      case Left(SysctlError.Io(path, _)) => assertEquals(path, missing)
      case other                         => fail(s"unexpected: $other")
    }
  }

  test("UTF-8として不正なファイルはIoエラー") {
    val file = Files.createTempFile("sysctl", ".conf")
    try {
      Files.write(file, Array[Byte](0x61, 0x3d, 0xff.toByte))
      assert(SysctlConf.load(file).left.exists(_.isInstanceOf[SysctlError.Io]))
    } finally Files.delete(file)
  }
}
