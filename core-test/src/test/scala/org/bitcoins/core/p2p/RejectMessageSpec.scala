package org.bitcoins.core.p2p

import org.bitcoins.testkit.gen.ControlMessageGenerator
import org.bitcoins.testkit.util.BitcoinSUnitTest

class RejectMessageSpec extends BitcoinSUnitTest {

  it must "have serialization symmetry" in {
    forAll(ControlMessageGenerator.rejectMessage) { rejectMsg =>
      assert(RejectMessage(rejectMsg.hex) == rejectMsg)
    }
  }
}