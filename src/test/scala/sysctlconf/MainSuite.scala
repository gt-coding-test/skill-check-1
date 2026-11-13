package sysctlconf

class MainSuite extends munit.FunSuite {

  private def resource(name: String): String =
    java.nio.file.Paths.get(getClass.getClassLoader.getResource(name).toURI).toString

  test("設定ファイルだけを渡すと、格納された内容を課題の期待値と同じ形で表示する") {
    assertEquals(
      Main.run(List(resource("example1.conf"))),
      Right(
        """{
          |  "endpoint": "localhost:3000",
          |  "debug": "true",
          |  "log": {
          |    "file": "/var/log/console.log"
          |  }
          |}""".stripMargin
      )
    )
  }

  test("スキーマファイルも渡すと検証し、従っていなければエラーメッセージを返す") {
    assert(Main.run(List(resource("example1.conf"), resource("example.schema"))).isRight)
    assertEquals(
      Main.run(List(resource("example2.conf"), resource("example.schema"))),
      Left("line 4: key 'log.name' is not defined in the schema")
    )
  }

  test("--allow-unknown-keysを付けると、スキーマに無いキーを許容する") {
    val args = List(resource("example2.conf"), resource("example.schema"), "--allow-unknown-keys")
    assert(Main.run(args).isRight)
  }

  test("空の設定は{}と表示する") {
    val empty = java.nio.file.Files.createTempFile("sysctl", ".conf")
    try assertEquals(Main.run(List(empty.toString)), Right("{}"))
    finally java.nio.file.Files.delete(empty)
  }

  test("引数が不正なら使い方を返す") {
    List(
      Nil,
      List("a", "b", "c"),
      List("a", "--unknown-flag"),
      List("a", "--allow-unknown-keys")
    ).foreach { args =>
      assert(Main.run(args).left.exists(_.startsWith("usage:")), args.toString)
    }
  }
}
