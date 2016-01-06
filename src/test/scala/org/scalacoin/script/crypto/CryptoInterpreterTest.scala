package org.scalacoin.script.crypto

import org.scalatest.{MustMatchers, FlatSpec}

/**
 * Created by chris on 1/6/16.
 */
class CryptoInterpreterTest extends FlatSpec with MustMatchers with CryptoInterpreter {

  "CryptoInterpreter" must "evaluate OP_HASH160 correctly when it is on top of the script stack" in {
    val stack = List("02218AD6CDC632E7AE7D04472374311CEBBBBF0AB540D2D08C3400BB844C654231".toLowerCase)
    val script = List(OP_HASH160)
    val (newStack,newScript) = hash160(stack,script)
    newStack.head must be ("5238C71458E464D9FF90299ABCA4A1D7B9CB76AB".toLowerCase)
    newScript.size must be(0)

  }
}
