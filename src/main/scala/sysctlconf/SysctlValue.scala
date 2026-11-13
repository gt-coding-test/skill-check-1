package sysctlconf

/** 設定ツリーの1ノード。
  *
  * キーを`.`で分割した階層構造を表す。`log.file = /var/log/console.log`は
  * `Node(Map("log" -> Node(Map("file" -> Leaf("/var/log/console.log")))))`になる。
  */
enum SysctlValue {

  /** 末端の値。sysctl.confに型は無いので常に文字列で保持する。 */
  case Leaf(value: String)

  /** 子を持つノード。`children`は定義順を保持する。 */
  case Node(children: Map[String, SysctlValue])
}
