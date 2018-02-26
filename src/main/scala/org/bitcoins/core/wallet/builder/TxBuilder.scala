package org.bitcoins.core.wallet.builder

import org.bitcoins.core.crypto.{ECPrivateKey, TxSigComponent}
import org.bitcoins.core.currency.{CurrencyUnit, CurrencyUnits}
import org.bitcoins.core.number.UInt32
import org.bitcoins.core.protocol.script._
import org.bitcoins.core.protocol.transaction._
import org.bitcoins.core.script.constant.ScriptNumber
import org.bitcoins.core.script.crypto.HashType
import org.bitcoins.core.script.locktime.LockTimeInterpreter
import org.bitcoins.core.util.BitcoinSLogger
import org.bitcoins.core.wallet.signer._

import scala.annotation.tailrec
import scala.util.Try

/** High level class to create a signed transaction that spends a set of
  * unspent transaction outputs.
  *
  * The most important method in this class is the 'sign' method. This will start the signing procedure for a
  * transaction and then return either a signed [[Transaction]] or a [[TxBuilderError]]
  *
  * For usage examples see TxBuilderSpec
  */
sealed abstract class TxBuilder {
  private val logger = BitcoinSLogger.logger
  type OutputInfo = (TransactionOutPoint, TransactionOutput, Seq[ECPrivateKey], Option[ScriptPubKey], Option[ScriptWitness], HashType)
  type OutPointMap = Map[TransactionOutPoint, (TransactionOutput, Seq[ECPrivateKey], Option[ScriptPubKey], Option[ScriptWitness], HashType)]

  /** The outputs which we are spending bitcoins to */
  def destinations: Seq[TransactionOutput]

  /** The [[ScriptPubKey]]'s we are spending bitcoins to */
  def destinationSPKs: Seq[ScriptPubKey] = destinations.map(_.scriptPubKey)

  /** A sequence of the amounts we are spending in this transaction */
  def destinationAmounts: Seq[CurrencyUnit] = destinations.map(_.value)

  /** The spent amount of bitcoins we are sending in the transaction, this does NOT include the fee */
  def spentAmount: CurrencyUnit = destinationAmounts.fold(CurrencyUnits.zero)(_ + _)

  /** The total amount of satoshis that are able to be spent by this transaction */
  def creditingAmount: CurrencyUnit = outPointsSpendingInfo.values.map(_._1.value).foldLeft(CurrencyUnits.zero)(_ + _)

  /** The fee in this transaction */
  def fee: CurrencyUnit = creditingAmount - spentAmount

  /** The transactions which contain the outputs we are spending. We need various bits of information from
    * these crediting transactions, like there txid, the output amount, and obviously the ouptut [[ScriptPubKey]]
    */
  def creditingTxs: Seq[Transaction]

  /** The list of [[org.bitcoins.core.protocol.transaction.TransactionOutPoint]]s we are attempting to spend
    * and the keys, redeem scripts, and script witnesses that might be needed to spend this outpoint.
    * This information is dependent on what the [[ScriptPubKey]] type is we are spending. For isntance, if we are spending a
    * regular [[P2PKHScriptPubKey]], we do not need a redeem script or script witness.
    *
    * If we are spending a [[P2WPKHWitnessSPKV0]] we do not need a redeem script, but we need a [[ScriptWitness]]
    */
  def outPointsSpendingInfo: OutPointMap

  /** All of the keys that need to be used to spend this transaction */
  def privKeys: Seq[ECPrivateKey] = outPointsSpendingInfo.values.flatMap(_._2).toSeq

  /** The outpoints that we are using in this transaction */
  def outPoints: Seq[TransactionOutPoint] = outPointsSpendingInfo.keys.toSeq

  /** The redeem scripts that are needed in this transaction */
  def redeemScriptOpt: Seq[Option[ScriptPubKey]] = outPointsSpendingInfo.values.map(_._3).toSeq

  /** The script witnesses that are needed in this transaction */
  def scriptWitOpt: Seq[Option[ScriptWitness]] = outPointsSpendingInfo.values.map(_._4).toSeq

  /**
    * Signs the given transaction and then returns a signed tx that spends
    * all of the given outputs.
    * Checks the given invariants when the signing process is done
    * An example of some invariants is that the fee on the signed transaction below a certain amount,
    * or that RBF is enabled on the signed transaction.
    *
    * @param invariants - invariants that should hold true when we are done signing the transaction
    * @return the signed transaction, or a [[TxBuilderError]] indicating what went wrong when signing the tx
    */
  def sign(invariants: Transaction => Boolean): Either[Transaction, TxBuilderError] = {
    @tailrec
    def loop(remaining: List[OutputInfo],
             txInProgress: Transaction): Either[Transaction,TxBuilderError] = remaining match {
      case Nil => Left(txInProgress)
      case info :: t =>
        val partiallySigned = sign(info, txInProgress)
        partiallySigned match {
          case Left(tx) => loop(t,tx)
          case Right(err) => Right(err)
        }
    }
    val utxos: List[OutputInfo] = outPointsSpendingInfo.map { c =>
      (c._1, c._2._1, c._2._2, c._2._3, c._2._4, c._2._5)
    }.toList
    val unsignedTxWit = TransactionWitness.fromWitOpt(scriptWitOpt)
    val tc = TransactionConstants
    val lockTime = calcLockTime(utxos)
    val inputs = calcSequenceForInputs(utxos)
    val unsigned = unsignedTxWit match {
      case EmptyWitness => BaseTransaction(tc.validLockVersion,inputs,destinations,lockTime)
      case wit: TransactionWitness => WitnessTransaction(tc.validLockVersion,inputs,destinations,lockTime,wit)
    }
    val signedTx = loop(utxos, unsigned)
    signedTx match {
      case l: Left[Transaction,TxBuilderError] =>
        if (!invariants(l.a)) {
          Right(TxBuilderError.FailedUserInvariants)
        } else {
          l
        }
      case r: Right[Transaction, TxBuilderError] => r
    }
  }

  /** This function creates a newly signed input, and then adds it to the unsigned transaction
    * @param info - the information needed to validly spend the given output
    * @param unsignedTx - the transaction that we are spending this output in
    * @return either the transaction with the signed input added, or a [[TxBuilderError]]
    */
  private def sign(info: OutputInfo, unsignedTx: Transaction): Either[Transaction, TxBuilderError] = {
    val outpoint = info._1
    val output = info._2
    val keys = info._3
    val redeemScriptOpt = info._4
    val scriptWitnessOpt = info._5
    val hashType = info._6
    val inputIndex = UInt32(unsignedTx.inputs.zipWithIndex.find(_._1.previousOutput == outpoint).get._2)
    val oldInput = unsignedTx.inputs(inputIndex.toInt)
    output.scriptPubKey match {
      case _: P2PKScriptPubKey =>
        P2PKSigner.sign(keys,output,unsignedTx,inputIndex,hashType).left.map(_.transaction)
      case _: P2PKHScriptPubKey => P2PKHSigner.sign(keys,output,unsignedTx,inputIndex,hashType).left.map(_.transaction)
      case _: MultiSignatureScriptPubKey => MultiSigSigner.sign(keys,output,unsignedTx,inputIndex,hashType).left.map(_.transaction)
      case lock: LockTimeScriptPubKey =>
        lock.nestedScriptPubKey match {
          case _: P2PKScriptPubKey => P2PKSigner.sign(keys,output,unsignedTx,inputIndex,hashType).left.map(_.transaction)
          case _: P2PKHScriptPubKey => P2PKHSigner.sign(keys,output,unsignedTx,inputIndex,hashType).left.map(_.transaction)
          case _: MultiSignatureScriptPubKey => MultiSigSigner.sign(keys,output,unsignedTx,inputIndex,hashType).left.map(_.transaction)
          case _: P2WPKHWitnessSPKV0 => P2WPKHSigner.sign(keys,output,unsignedTx,inputIndex,hashType).left.map(_.transaction)
          case _: P2SHScriptPubKey => Right(TxBuilderError.NestedP2SHSPK)
          case _: P2WSHWitnessSPKV0 => Right(TxBuilderError.NestedP2WSHSPK)
          case _: CSVScriptPubKey | _: CLTVScriptPubKey =>
            //TODO: Comeback to this later and see if we should have signer for nested locktime spks
            Right(TxBuilderError.NoSigner)
          case _: NonStandardScriptPubKey | _: WitnessCommitment | _: EscrowTimeoutScriptPubKey
               | EmptyScriptPubKey | _: UnassignedWitnessScriptPubKey => Right(TxBuilderError.NoSigner)
        }
      case _: P2SHScriptPubKey =>
        redeemScriptOpt match {
          case Some(redeemScript) =>
            val input = TransactionInput(outpoint,EmptyScriptSignature,oldInput.sequence)
            val updatedTx = unsignedTx match {
              case btx: BaseTransaction =>
                BaseTransaction(btx.version,unsignedTx.inputs.updated(inputIndex.toInt,input),btx.outputs,btx.lockTime)
              case wtx: WitnessTransaction =>
                WitnessTransaction(wtx.version,unsignedTx.inputs.updated(inputIndex.toInt,input),wtx.outputs,wtx.lockTime,wtx.witness)
            }
            val updatedOutput = TransactionOutput(output.value,redeemScript)
            val signedTxEither: Either[Transaction, TxBuilderError] = sign((outpoint,updatedOutput,keys,None,
              scriptWitnessOpt, hashType),updatedTx)
            signedTxEither.left.map { signedTx =>
              val i = signedTx.inputs(inputIndex.toInt)
              val p2sh = P2SHScriptSignature(i.scriptSignature,redeemScript)
              val signedInput = TransactionInput(i.previousOutput,p2sh,i.sequence)
              val signedInputs = signedTx.inputs.updated(inputIndex.toInt,signedInput)
              signedTx match {
                case btx: BaseTransaction =>
                  BaseTransaction(btx.version,signedInputs,btx.outputs,btx.lockTime)
                case wtx: WitnessTransaction =>
                  WitnessTransaction(wtx.version,signedInputs,wtx.outputs,wtx.lockTime,wtx.witness)
              }
            }
          case None => Right(TxBuilderError.NoRedeemScript)
        }
      case _: WitnessScriptPubKeyV0 =>
        //if we don't have a WitnessTransaction we need to convert our unsignedTx to a WitnessTransaction
        val unsignedWTx: WitnessTransaction = unsignedTx match {
          case btx: BaseTransaction => WitnessTransaction(btx.version, btx.inputs, btx.outputs,btx.lockTime, EmptyWitness)
          case wtx: WitnessTransaction => wtx
        }
        val result: Either[TxSigComponent, TxBuilderError] = scriptWitnessOpt match {
          case Some(scriptWit) =>
            scriptWit match {
              case _: P2WPKHWitnessV0 =>
                if (keys.size != 1) {
                  Right(TxBuilderError.TooManyKeys)
                } else {
                  P2WPKHSigner.sign(keys, output, unsignedWTx, inputIndex, hashType)
                }
              case p2wshScriptWit: P2WSHWitnessV0 =>
                val redeemScript = p2wshScriptWit.redeemScript
                redeemScript match {
                  case _: P2PKScriptPubKey => P2PKSigner.sign(keys,output,unsignedWTx,inputIndex,hashType)
                  case _: P2PKHScriptPubKey => P2PKHSigner.sign(keys,output,unsignedWTx,inputIndex,hashType)
                  case _: MultiSignatureScriptPubKey  => MultiSigSigner.sign(keys,output,unsignedWTx,inputIndex,hashType)
                  case _: P2WPKHWitnessSPKV0 | _: P2WSHWitnessSPKV0 => Right(TxBuilderError.NestedWitnessSPK)
                  case _: P2SHScriptPubKey => Right(TxBuilderError.NestedP2SHSPK)
                  case lock: LockTimeScriptPubKey =>
                    lock.nestedScriptPubKey match {
                      case _: P2PKScriptPubKey => P2PKSigner.sign(keys,output,unsignedTx,inputIndex,hashType)
                      case _: P2PKHScriptPubKey => P2PKHSigner.sign(keys,output,unsignedTx,inputIndex,hashType)
                      case _: MultiSignatureScriptPubKey => MultiSigSigner.sign(keys,output,unsignedTx,inputIndex,hashType)
                      case _: P2WPKHWitnessSPKV0 => P2WPKHSigner.sign(keys,output,unsignedTx,inputIndex,hashType)
                      case _: P2SHScriptPubKey => Right(TxBuilderError.NestedP2SHSPK)
                      case _: P2WSHWitnessSPKV0 => Right(TxBuilderError.NestedP2WSHSPK)
                      case _: CSVScriptPubKey | _: CLTVScriptPubKey | _: EscrowTimeoutScriptPubKey =>
                        //TODO: Comeback to this later and see if we should have signer for nested locktime spks
                        Right(TxBuilderError.NoSigner)
                      case _: NonStandardScriptPubKey | _: WitnessCommitment
                           | EmptyScriptPubKey | _: UnassignedWitnessScriptPubKey
                           | _: EscrowTimeoutScriptPubKey => Right(TxBuilderError.NoSigner)
                    }
                  case _: NonStandardScriptPubKey | _: WitnessCommitment | EmptyScriptPubKey
                        | _: UnassignedWitnessScriptPubKey | _: EscrowTimeoutScriptPubKey =>
                    Right(TxBuilderError.NoSigner)
                }
              case EmptyScriptWitness => Right(TxBuilderError.NoWitness)
            }
          case None => Right(TxBuilderError.NoWitness)
        }
        result.left.map(_.transaction)
      case _: NonStandardScriptPubKey | _: WitnessCommitment
           | EmptyScriptPubKey | _: UnassignedWitnessScriptPubKey
           | _: EscrowTimeoutScriptPubKey => Right(TxBuilderError.NoSigner)
    }
  }

/*  private def matchSigner(spk: ScriptPubKey): Option[Signer] = spk match {
    case _: P2PKScriptPubKey => Some(P2PKSigner)
    case _: P2PKHScriptPubKey => Some(P2PKHSigner)
    case _: MultiSignatureScriptPubKey => Some(MultiSigSigner)
    case _: P2SHScriptPubKey => None
    case _: CSVScriptPubKey | _: CLTVScriptPubKey => None
    case _: P2WPKHWitnessSPKV0 => Some(P2WPKHSigner)
    case _: WitnessCommitment | EmptyScriptPubKey
         | _: UnassignedWitnessScriptPubKey | _: NonStandardScriptPubKey => None
  }*/

  /** Returns a valid sequence number for the given [[ScriptNumber]]
    * A transaction needs a valid sequence number to spend a OP_CHECKSEQUENCEVERIFY script.
    * See BIP68/112 for more information
    * [[https://github.com/bitcoin/bips/blob/master/bip-0068.mediawiki]]
    * [[https://github.com/bitcoin/bips/blob/master/bip-0112.mediawiki]]
    * */
  private def solveSequenceForCSV(scriptNum: ScriptNumber): UInt32 = LockTimeInterpreter.isCSVLockByBlockHeight(scriptNum) match {
    case true =>
      //means that we need to have had scriptNum blocks bassed since this tx was included a block to be able to spend this output
      val blocksPassed = scriptNum.toLong & TransactionConstants.sequenceLockTimeMask.toLong
      val sequence = UInt32(blocksPassed)
      sequence
    case false =>
      //means that we need to have had 512 * n seconds passed since the tx was included in a block passed
      val n = scriptNum.toLong
      val sequence = UInt32(n & TransactionConstants.sequenceLockTimeMask.toLong)
      //set sequence number to indicate this is relative locktime
      sequence | TransactionConstants.sequenceLockTimeTypeFlag
  }

  /** This helper function calculates the appropriate locktime for a transaction.
    * To be able to spend [[CLTVScriptPubKey]]'s you need to have the transaction's
    * locktime set to the same value (or higher) than the output it is spending.
    * See BIP65 for more info
    */
  private def calcLockTime(utxos: Seq[OutputInfo]): UInt32 = {
    @tailrec
    def loop(remaining: Seq[OutputInfo], currentLockTime: UInt32): UInt32 = remaining match {
      case Nil => currentLockTime
      case (outpoint,output,keys,redeemScriptOpt,scriptWitOpt, hashType) :: t => output.scriptPubKey match {
        case cltv: CLTVScriptPubKey =>
          val l = UInt32(cltv.locktime.toLong)
          if (currentLockTime < l) loop(t,l)
          else loop(t,currentLockTime)
        case _: P2SHScriptPubKey | _: P2WSHWitnessSPKV0 =>
          if (redeemScriptOpt.isDefined) {
            //recursively call with redeem script as output script
            val o = TransactionOutput(output.value,redeemScriptOpt.get)
            val i = (outpoint,o, keys, None,scriptWitOpt, hashType)
            loop(i +: t, currentLockTime)
          } else if (scriptWitOpt.isDefined) {
            scriptWitOpt.get match {
              case EmptyScriptWitness => loop(t,currentLockTime)
              case _: P2WPKHWitnessV0 => loop(t,currentLockTime)
              case p2wsh: P2WSHWitnessV0 =>
                //recursively call with the witness redeem script as the script
                val o = TransactionOutput(output.value, p2wsh.redeemScript)
                val i = (outpoint,o,keys,redeemScriptOpt,None, hashType)
                loop(i +: t, currentLockTime)
            }
          } else {
            loop(t,currentLockTime)
          }
        case _: P2PKScriptPubKey | _: P2PKHScriptPubKey | _: MultiSignatureScriptPubKey | _: P2SHScriptPubKey
             | _: P2WPKHWitnessSPKV0 | _: P2WSHWitnessSPKV0 | _: NonStandardScriptPubKey | _: WitnessCommitment
             | EmptyScriptPubKey | _: UnassignedWitnessScriptPubKey | _: CSVScriptPubKey
             | _: EscrowTimeoutScriptPubKey =>
          loop(t,currentLockTime)
      }
    }
    loop(utxos,TransactionConstants.lockTime)
  }

  /** This helper function calculates the appropriate sequence number for each transaction input.
    * [[CLTVScriptPubKey]] and [[CSVScriptPubKey]]'s need certain sequence numbers on the inputs
    * to make them spendable.
    * See BIP68/112 and BIP65 for more info
    */
  private def calcSequenceForInputs(utxos: Seq[OutputInfo]): Seq[TransactionInput] = {
    @tailrec
    def loop(remaining: Seq[OutputInfo], accum: Seq[TransactionInput]): Seq[TransactionInput] = remaining match {
      case Nil => accum.reverse
      case (outpoint,output,keys,redeemScriptOpt,scriptWitOpt, hashType) :: t =>
        output.scriptPubKey match {
          case csv: CSVScriptPubKey =>
            val sequence = solveSequenceForCSV(csv.locktime)
            val i = TransactionInput(outpoint,EmptyScriptSignature,sequence)
            loop(t,i +: accum)
          case _: CLTVScriptPubKey =>
            val sequence = UInt32.zero
            val i = TransactionInput(outpoint,EmptyScriptSignature,sequence)
            loop(t,i +: accum)
          case _: P2SHScriptPubKey | _: P2WSHWitnessSPKV0 =>
            if (redeemScriptOpt.isDefined) {
              //recursively call with the redeem script in the output
              val o = TransactionOutput(output.value,redeemScriptOpt.get)
              val i = (outpoint,o,keys,None,scriptWitOpt, hashType)
              loop(i +: t, accum)
            } else if (scriptWitOpt.isDefined) {
              scriptWitOpt.get match {
                case EmptyScriptWitness => loop(t,accum)
                case _: P2WPKHWitnessV0 => loop(t,accum)
                case p2wsh: P2WSHWitnessV0 =>
                  val o = TransactionOutput(output.value,p2wsh.redeemScript)
                  val i = (outpoint,o,keys,redeemScriptOpt,None, hashType)
                  loop(i +: t, accum)
              }
            } else loop(t,accum)
          case _: P2PKScriptPubKey | _: P2PKHScriptPubKey | _: MultiSignatureScriptPubKey
               | _: P2WPKHWitnessSPKV0 | _: NonStandardScriptPubKey | _: WitnessCommitment
               | EmptyScriptPubKey | _: UnassignedWitnessScriptPubKey | _: EscrowTimeoutScriptPubKey =>
            val input = TransactionInput(outpoint,EmptyScriptSignature,TransactionConstants.sequence)
            loop(t, input +: accum)
        }
    }
    val inputs = loop(utxos,Nil)
    inputs
  }
}


object TxBuilder {
  private case class TransactionBuilderImpl(destinations: Seq[TransactionOutput],
                                            creditingTxs: Seq[Transaction],
                                            outPointsSpendingInfo: Map[TransactionOutPoint,
                                              (TransactionOutput, Seq[ECPrivateKey], Option[ScriptPubKey], Option[ScriptWitness], HashType)]) extends TxBuilder {
    require(outPoints.exists(o => creditingTxs.exists(_.txId == o.txId)))
  }

  def apply(destinations: Seq[TransactionOutput], creditingTxs: Seq[Transaction],
            outPointsSpendingInfo: Map[TransactionOutPoint, (TransactionOutput, Seq[ECPrivateKey], Option[ScriptPubKey],
              Option[ScriptWitness], HashType)]): Try[TxBuilder] = {
    Try(TransactionBuilderImpl(destinations,creditingTxs,outPointsSpendingInfo))
  }
}
