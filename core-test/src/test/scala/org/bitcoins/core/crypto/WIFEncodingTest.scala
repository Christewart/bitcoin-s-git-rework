package org.bitcoins.core.crypto

import org.bitcoins.core.config.{MainNet, RegTest, SigNet, TestNet3}
import org.bitcoins.crypto.ECPrivateKey
import org.bitcoins.testkitcore.gen.{ChainParamsGenerator, CryptoGenerators}
import org.bitcoins.testkitcore.util.BitcoinSUnitTest

class WIFEncodingTest extends BitcoinSUnitTest {

  it must "determine if a private key corresponds to a compressed public key or not" in {
    val compressedKey = "L1RrrnXkcKut5DEMwtDthjwRcTTwED36thyL1DebVrKuwvohjMNi"
    val uncompressedKey = "93DVKyFYwSN6wEo3E2fCrFPUp17FtrtNi2Lf7n4G3garFb16CRj"
    ECPrivateKeyUtil.isCompressed(compressedKey) must be(true)
    ECPrivateKeyUtil.isCompressed(uncompressedKey) must be(false)
  }
  it must "serialize a private key to WIF and then be able to deserialize it" in {

    val hex = "2cecbfb72f8d5146d7fe7e5a3f80402c6dd688652c332dff2e44618d2d3372"
    val privKey = ECPrivateKey(hex)
    val wif = ECPrivateKeyUtil.toWIF(privKey, TestNet3)
    val privKeyFromWIF = ECPrivateKeyUtil.fromWIFToPrivateKey(wif)
    privKeyFromWIF must be(privKey)

    val privKeyDecompressed = ECPrivateKey.fromHex(hex, isCompressed = false)
    val wifDecompressed = ECPrivateKeyUtil.toWIF(privKeyDecompressed, TestNet3)
    val privKeyDecompressedFromWIF =
      ECPrivateKeyUtil.fromWIFToPrivateKey(wifDecompressed)
    privKeyDecompressedFromWIF must be(privKeyDecompressed)
  }

  it must "serialize a private key to WIF when the private key is prefixed with 0 bytes" in {
    val hex =
      "00fc391adf4d6063a16a2e38b14d2be10133c4dacd4348b49d23ee0ce5ff4f1701"
    val privKey = ECPrivateKey(hex)
    val wif = ECPrivateKeyUtil.toWIF(privKey, TestNet3)
    val privKeyFromWIF = ECPrivateKeyUtil.fromWIFToPrivateKey(wif)
    privKeyFromWIF must be(privKey)
  }

  it must "correctly decode a private key from WIF" in {
    val privateKey = ECPrivateKeyUtil.fromWIFToPrivateKey(
      "cTPg4Zc5Jis2EZXy3NXShgbn487GWBTapbU63BerLDZM3w2hQSjC")
    //derived hex on bitcore's playground
    privateKey.hex must be(
      "ad59fb6aadf617fb0f93469741fcd9a9f48700f1d1f465ddc0f26fa7f7bfa1ac")
  }

  it must "decode a WIF private key corresponding to uncompressed public key" in {
    val wif = "5Kg1gnAjaLfKiwhhPpGS3QfRg2m6awQvaj98JCZBZQ5SuS2F15C"
    val privKey = ECPrivateKeyUtil.fromWIFToPrivateKey(wif)
    privKey.publicKey.hex must be(
      "045b81f0017e2091e2edcd5eecf10d5bdd120a5514cb3ee65b8447ec18bfc4575c6d5bf415e54e03b1067934a0f0ba76b01c6b9ab227142ee1d543764b69d901e0")
  }

  it must "have serialization symmetry for WIF format" in {
    forAll(CryptoGenerators.privateKey, ChainParamsGenerator.networkParams) {
      (privKey, network) =>
        val wif = ECPrivateKeyUtil.toWIF(privKey, network)
        network match {
          case MainNet =>
            assert(ECPrivateKeyUtil.parseNetworkFromWIF(wif).get == network)
          case TestNet3 | RegTest | SigNet =>
            assert(ECPrivateKeyUtil.parseNetworkFromWIF(wif).get == TestNet3)
        }
        assert(ECPrivateKeyUtil.fromWIFToPrivateKey(wif) == privKey)
    }
  }

  it must "fail to parse unknown WIF networks" in {
    // Litecoin privkey
    val wif = "6uSDaezGtedUbYk4F9CNVXbDWw9DuEuw7czU596t1CzmeAJ77P8"
    assert(ECPrivateKeyUtil.parseNetworkFromWIF(wif).isFailure)
  }

  it must "fail to parse non-WIF strings" in {
    assert(ECPrivateKeyUtil.parseNetworkFromWIF("hello there").isFailure)
  }

}
