package org.bitcoins.rpc.client

import org.bitcoins.core.crypto.{DoubleSha256Digest, ECPrivateKey}
import org.bitcoins.core.currency.Bitcoins
import org.bitcoins.core.number.UInt32
import org.bitcoins.core.protocol.BitcoinAddress
import org.bitcoins.core.protocol.script.ScriptPubKey
import play.api.libs.json.{Json, Writes}
import org.bitcoins.rpc.serializers.JsonSerializers._

object RpcOpts {
  case class FundRawTransactionOptions(
      changeAddress: Option[BitcoinAddress] = None,
      changePosition: Option[Int] = None,
      includeWatching: Boolean = false,
      lockUnspents: Boolean = false,
      reverseChangeKey: Boolean = true,
      feeRate: Option[Bitcoins] = None,
      subtractFeeFromOutputs: Option[Array[Int]])

  implicit val fundRawTransactionOptionsWrites: Writes[
    FundRawTransactionOptions] = Json.writes[FundRawTransactionOptions]

  case class SignRawTransactionOutputParameter(
      txid: DoubleSha256Digest,
      vout: Int,
      scriptPubKey: ScriptPubKey,
      reedemScript: Option[ScriptPubKey] = None,
      amount: Bitcoins)

  implicit val signRawTransactionOutputParameterWrites: Writes[
    SignRawTransactionOutputParameter] =
    Json.writes[SignRawTransactionOutputParameter]

  case class ImportMultiRequest(
      scriptPubKey: ScriptPubKey,
      timestamp: UInt32, // Needs writes
      reedemscript: Option[ScriptPubKey],
      pubkeys: Option[Vector[ScriptPubKey]],
      keys: Option[Vector[ECPrivateKey]], // Needs writes
      internal: Boolean = false,
      watchonly: Boolean = false,
      label: String = ""
  )
}
