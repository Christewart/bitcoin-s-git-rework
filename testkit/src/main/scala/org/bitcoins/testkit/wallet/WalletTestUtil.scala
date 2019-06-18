package org.bitcoins.testkit.wallet

import org.bitcoins.core.config.RegTest
import org.bitcoins.core.crypto._
import org.bitcoins.core.number.UInt32
import org.bitcoins.core.protocol.blockchain.{
  ChainParams,
  RegTestNetChainParams
}
import org.bitcoins.core.protocol.script.ScriptPubKey
import org.bitcoins.testkit.core.gen.CryptoGenerators
import org.bitcoins.wallet.models.AccountDb
import scodec.bits.HexStringSyntax
import org.bitcoins.core.hd._
import org.bitcoins.core.protocol.script.ScriptWitness
import org.bitcoins.core.protocol.script.P2WPKHWitnessV0
import org.bitcoins.core.protocol.transaction.TransactionOutput
import org.bitcoins.core.protocol.transaction.TransactionOutPoint
import org.bitcoins.wallet.models.NativeV0UTXOSpendingInfoDb
import org.bitcoins.core.currency._
import org.bitcoins.wallet.models.LegacyUTXOSpendingInfoDb

object WalletTestUtil {

  val chainParams: ChainParams = RegTestNetChainParams
  val networkParam: RegTest.type = RegTest

  val hdCoinType: HDCoinType = HDCoinType.Testnet

  /**
    * Useful if you want wallet test runs
    * To use the same key values each time
    */
  val sampleMnemonic =
    MnemonicCode.fromWords(
      Vector("portion",
             "uniform",
             "owner",
             "crime",
             "duty",
             "floor",
             "sketch",
             "stumble",
             "outer",
             "south",
             "relax",
             "car"))

  lazy val sampleSegwitPath =
    SegWitHDPath(hdCoinType,
                 accountIndex = 0,
                 HDChainType.External,
                 addressIndex = 0)

  /** Sample legacy HD path */
  lazy val sampleLegacyPath = LegacyHDPath(hdCoinType,
                                           accountIndex = 0,
                                           HDChainType.Change,
                                           addressIndex = 0)

  def freshXpub: ExtPublicKey =
    CryptoGenerators.extPublicKey.sample.getOrElse(freshXpub)

  val firstAccount = HDAccount(HDCoin(HDPurposes.SegWit, hdCoinType), 0)
  def firstAccountDb = AccountDb(freshXpub, firstAccount)

  lazy val sampleTxid: DoubleSha256Digest = DoubleSha256Digest(
    hex"a910523c0b6752fbcb9c24303b4e068c505825d074a45d1c787122efb4649215")
  lazy val sampleVout: UInt32 = UInt32.zero
  lazy val sampleSPK: ScriptPubKey =
    ScriptPubKey.fromAsmBytes(hex"001401b2ac67587e4b603bb3ad709a8102c30113892d")

  lazy val sampleSegwitUtxo: NativeV0UTXOSpendingInfoDb = {
    val outpoint =
      TransactionOutPoint(WalletTestUtil.sampleTxid, WalletTestUtil.sampleVout)
    val output = TransactionOutput(1.bitcoin, WalletTestUtil.sampleSPK)
    val scriptWitness = WalletTestUtil.sampleScriptWitness
    val privkeyPath = WalletTestUtil.sampleSegwitPath
    NativeV0UTXOSpendingInfoDb(id = None,
                               outPoint = outpoint,
                               output = output,
                               spent = false,
                               privKeyPath = privkeyPath,
                               scriptWitness = scriptWitness)
  }

  lazy val sampleLegacyUtxo = {
    val outpoint =
      TransactionOutPoint(WalletTestUtil.sampleTxid, WalletTestUtil.sampleVout)
    val output = TransactionOutput(1.bitcoin, WalletTestUtil.sampleSPK)
    val privKeyPath = WalletTestUtil.sampleLegacyPath
    LegacyUTXOSpendingInfoDb(id = None,
                             outPoint = outpoint,
                             output = output,
                             spent = false,
                             privKeyPath = privKeyPath)
  }
  lazy val sampleScriptWitness: ScriptWitness = P2WPKHWitnessV0(freshXpub.key)
}
